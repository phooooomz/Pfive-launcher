// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "binder.hpp"

#include <fcntl.h>
#include <linux/android/binder.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

extern "C" long lx_syscall(long number, long a, long b, long c, long d, long e, long f);

namespace lxdb {

void nodeCommand(int fd, uint32_t cmd, Node* node, const binder_ptr_cookie& refs);

struct Death {
    uint32_t handle = 0;
    std::function<void()> onDeath;
    std::atomic<bool> cancelled{false};
};

namespace {

constexpr uint32_t kPing = B_PACK_CHARS('_', 'P', 'N', 'G');
constexpr uint32_t kInterface = B_PACK_CHARS('_', 'N', 'T', 'F');
constexpr uint32_t kDump = B_PACK_CHARS('_', 'D', 'M', 'P');
constexpr size_t kReadBuffer = 512;
constexpr long kPageGuess = 4096;

/** One thread's half of the conversation with the driver. */
struct Thread {
    std::vector<uint8_t> out;
    uint8_t in[kReadBuffer];
    size_t inSize = 0;
    size_t inPos = 0;
};

thread_local Thread self;
std::mutex nodeLock;

long ioctlDriver(int fd, unsigned long request, void* arg) {
    return lx_syscall(__NR_ioctl, fd, static_cast<long>(request), reinterpret_cast<long>(arg), 0, 0, 0);
}

template <typename T> void put(const T& v) {
    const size_t at = self.out.size();
    self.out.resize(at + sizeof(T));
    std::memcpy(self.out.data() + at, &v, sizeof(T));
}

template <typename T> T take() {
    T v{};
    if (self.inSize - self.inPos >= sizeof(T)) {
        std::memcpy(&v, self.in + self.inPos, sizeof(T));
        self.inPos += sizeof(T);
    } else {
        self.inPos = self.inSize;
    }
    return v;
}

int talk(int fd, bool receive) {
    binder_write_read bwr{};
    const bool drained = self.inPos >= self.inSize;
    bwr.write_size = self.out.size();
    bwr.write_buffer = reinterpret_cast<binder_uintptr_t>(self.out.data());
    if (receive && drained) {
        bwr.read_size = sizeof(self.in);
        bwr.read_buffer = reinterpret_cast<binder_uintptr_t>(self.in);
    }
    if (bwr.write_size == 0 && bwr.read_size == 0) return 0;
    long r;
    do {
        r = ioctlDriver(fd, BINDER_WRITE_READ, &bwr);
    } while (r == -EINTR);
    if (r < 0) return static_cast<int>(r);
    if (bwr.write_consumed >= self.out.size()) {
        self.out.clear();
    } else if (bwr.write_consumed > 0) {
        self.out.erase(self.out.begin(), self.out.begin() + static_cast<long>(bwr.write_consumed));
    }
    if (bwr.read_consumed > 0) {
        self.inSize = bwr.read_consumed;
        self.inPos = 0;
    }
    return 0;
}

void putTransaction(uint32_t command, uint32_t handle, uint32_t code, uint32_t flags, const void* data, size_t size,
                    const uint64_t* offsets, size_t objects) {
    binder_transaction_data tr{};
    tr.target.handle = handle;
    tr.code = code;
    tr.flags = flags;
    tr.data_size = size;
    tr.offsets_size = objects * sizeof(binder_size_t);
    tr.data.ptr.buffer = reinterpret_cast<binder_uintptr_t>(data);
    tr.data.ptr.offsets = reinterpret_cast<binder_uintptr_t>(offsets);
    put<uint32_t>(command);
    put(tr);
}

/** Runs one command from the driver. A transaction runs here too, on whichever thread read it. */
void handleCommand(int fd, uint32_t cmd);

void dropBuffer(int fd, const void* buffer) {
    put<uint32_t>(BC_FREE_BUFFER);
    put(reinterpret_cast<binder_uintptr_t>(buffer));
    talk(fd, false);
}

/** Reads until the transaction this thread sent is answered. A call back into this process is served meanwhile. */
int waitForReply(int fd, Reply* reply) {
    for (;;) {
        if (self.inPos >= self.inSize) {
            const int r = talk(fd, true);
            if (r < 0) return r;
            if (self.inPos >= self.inSize) continue;
        }
        const auto cmd = take<uint32_t>();
        switch (cmd) {
            case BR_TRANSACTION_COMPLETE:
#ifdef BR_TRANSACTION_PENDING_FROZEN
            case BR_TRANSACTION_PENDING_FROZEN:
#endif
                if (reply == nullptr) return 0;
                break;
            case BR_DEAD_REPLY:
                return kDeadObject;
            case BR_FAILED_REPLY:
                return kFailedTransaction;
#ifdef BR_FROZEN_REPLY
            case BR_FROZEN_REPLY:
                return kFrozen;
#endif
            case BR_REPLY: {
                const auto tr = take<binder_transaction_data>();
                if ((tr.flags & TF_STATUS_CODE) != 0) {
                    int32_t status = kFailedTransaction;
                    if (tr.data_size >= sizeof(status)) std::memcpy(&status, reinterpret_cast<const void*>(tr.data.ptr.buffer), sizeof(status));
                    dropBuffer(fd, reinterpret_cast<const void*>(tr.data.ptr.buffer));
                    return status;
                }
                if (reply != nullptr) reply->adopt(tr);
                else dropBuffer(fd, reinterpret_cast<const void*>(tr.data.ptr.buffer));
                return 0;
            }
            default:
                handleCommand(fd, cmd);
        }
    }
}

void serve(int fd, const binder_transaction_data& tr) {
    auto* node = reinterpret_cast<Node*>(tr.cookie);
    ParcelReader in(reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer), tr.data_size,
                    reinterpret_cast<const uint64_t*>(tr.data.ptr.offsets), tr.offsets_size / sizeof(binder_size_t));
    Parcel out;
    int status = 0;
    switch (tr.code) {
        case kPing:
            break;
        case kInterface:
            out.writeString16(node->descriptor());
            break;
        case kDump:
            break;
        default: {
            Caller caller;
            caller.pid = tr.sender_pid;
            caller.uid = tr.sender_euid;
            status = node->onTransact(tr.code, in, out, caller);
        }
    }
    const bool oneway = (tr.flags & TF_ONE_WAY) != 0;
    // Input freed only now keeps binders it holds alive while the handler passes them on.
    put<uint32_t>(BC_FREE_BUFFER);
    put(static_cast<binder_uintptr_t>(tr.data.ptr.buffer));
    if (oneway) {
        talk(fd, false);
        return;
    }
    if (status != 0) {
        const int32_t code = status;
        putTransaction(BC_REPLY, 0, 0, TF_STATUS_CODE, &code, sizeof(code), nullptr, 0);
    } else {
        putTransaction(BC_REPLY, 0, 0, 0, out.data(), out.size(), out.offsets(), out.objectCount());
    }
    waitForReply(fd, nullptr);
}

void handleCommand(int fd, uint32_t cmd) {
    switch (cmd) {
        case BR_NOOP:
        case BR_OK:
        case BR_SPAWN_LOOPER:
        case BR_FINISHED:
            break;
        case BR_ERROR:
            take<int32_t>();
            break;
        case BR_INCREFS:
        case BR_ACQUIRE:
        case BR_RELEASE:
        case BR_DECREFS: {
            const auto refs = take<binder_ptr_cookie>();
            nodeCommand(fd, cmd, reinterpret_cast<Node*>(refs.cookie), refs);
            break;
        }
        case BR_TRANSACTION:
            serve(fd, take<binder_transaction_data>());
            break;
        case BR_DEAD_BINDER: {
            const auto cookie = take<binder_uintptr_t>();
            auto* death = reinterpret_cast<Death*>(cookie);
            if (!death->cancelled.load()) death->onDeath();
            put<uint32_t>(BC_DEAD_BINDER_DONE);
            put(cookie);
            talk(fd, false);
            break;
        }
        case BR_CLEAR_DEATH_NOTIFICATION_DONE:
            delete reinterpret_cast<Death*>(take<binder_uintptr_t>());
            break;
        default: {
            // A command newer than this source: its size is in its number.
            const size_t size = _IOC_SIZE(cmd);
            self.inPos = size > self.inSize - self.inPos ? self.inSize : self.inPos + size;
        }
    }
}

}  // namespace

Binder& Binder::get() {
    static Binder binder;
    return binder;
}

bool Binder::open(std::string* why) {
    const long fd = lx_syscall(__NR_openat, AT_FDCWD, reinterpret_cast<long>("/dev/binder"), O_RDWR | O_CLOEXEC, 0, 0, 0);
    if (fd < 0) {
        *why = std::string("cannot open /dev/binder: ") + strerror(static_cast<int>(-fd));
        return false;
    }
    binder_version version{};
    if (ioctlDriver(static_cast<int>(fd), BINDER_VERSION, &version) < 0 || version.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION) {
        *why = "the kernel speaks another Binder protocol";
        return false;
    }
    // libbinder's size: the driver keeps the last two pages of the megabyte for itself.
    const long page = sysconf(_SC_PAGESIZE);
    const long size = 1024 * 1024 - 2 * (page > 0 ? page : kPageGuess);
    const long map = lx_syscall(__NR_mmap, 0, size, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, fd, 0);
    if (map < 0 && map > -4096) {
        *why = std::string("cannot map the driver: ") + strerror(static_cast<int>(-map));
        return false;
    }
    fd_ = static_cast<int>(fd);
    return true;
}

void Binder::startLoopers(int count) {
    const int fd = fd_;
    for (int i = 0; i < count; i++) {
        std::thread([fd] {
            put<uint32_t>(BC_ENTER_LOOPER);
            for (;;) {
                if (talk(fd, true) < 0) return;
                while (self.inPos < self.inSize) handleCommand(fd, take<uint32_t>());
            }
        }).detach();
    }
}

int Binder::transact(uint32_t handle, uint32_t code, const Parcel& data, Reply* reply, uint32_t flags) {
    if (fd_ < 0) return kFailedTransaction;
    putTransaction(BC_TRANSACTION, handle, code, flags | TF_ACCEPT_FDS, data.data(), data.size(), data.offsets(), data.objectCount());
    const bool oneway = (flags & TF_ONE_WAY) != 0;
    return waitForReply(fd_, oneway ? nullptr : reply);
}

void Binder::freeBuffer(const void* buffer) { dropBuffer(fd_, buffer); }

void Binder::acquire(uint32_t handle) {
    put<uint32_t>(BC_INCREFS);
    put(handle);
    put<uint32_t>(BC_ACQUIRE);
    put(handle);
    talk(fd_, false);
}

void Binder::release(uint32_t handle) {
    put<uint32_t>(BC_RELEASE);
    put(handle);
    put<uint32_t>(BC_DECREFS);
    put(handle);
    talk(fd_, false);
}

Death* Binder::linkToDeath(uint32_t handle, std::function<void()> onDeath) {
    auto* death = new Death;
    death->handle = handle;
    death->onDeath = std::move(onDeath);
    binder_handle_cookie request{};
    request.handle = handle;
    request.cookie = reinterpret_cast<binder_uintptr_t>(death);
    put<uint32_t>(BC_REQUEST_DEATH_NOTIFICATION);
    put(request);
    talk(fd_, false);
    return death;
}

void Binder::unlinkToDeath(Death* death) {
    death->cancelled.store(true);
    binder_handle_cookie request{};
    request.handle = death->handle;
    request.cookie = reinterpret_cast<binder_uintptr_t>(death);
    put<uint32_t>(BC_CLEAR_DEATH_NOTIFICATION);
    put(request);
    talk(fd_, false);
}

void nodeCommand(int fd, uint32_t cmd, Node* node, const binder_ptr_cookie& refs) {
    bool gone = false;
    {
        const std::lock_guard<std::mutex> guard(nodeLock);
        switch (cmd) {
            case BR_INCREFS:
                node->weak_ = true;
                put<uint32_t>(BC_INCREFS_DONE);
                put(refs);
                break;
            case BR_ACQUIRE:
                node->strong_ = true;
                put<uint32_t>(BC_ACQUIRE_DONE);
                put(refs);
                break;
            case BR_RELEASE:
                node->strong_ = false;
                break;
            case BR_DECREFS:
                node->weak_ = false;
                break;
            default:
                break;
        }
        gone = node->abandoned_ && !node->strong_ && !node->weak_;
    }
    talk(fd, false);
    if (gone) delete node;
}

void Node::abandon() {
    bool gone = false;
    {
        const std::lock_guard<std::mutex> guard(nodeLock);
        abandoned_ = true;
        // Once the kernel has seen a node it reports the end of its references; until then the node must stay.
        gone = !shared_;
    }
    if (gone) delete this;
}

Reply::~Reply() {
    if (buffer_ != nullptr) Binder::get().freeBuffer(buffer_);
}

void Reply::adopt(const binder_transaction_data& tr) {
    buffer_ = reinterpret_cast<const void*>(tr.data.ptr.buffer);
    sender_ = tr.sender_euid;
    reader_ = ParcelReader(reinterpret_cast<const uint8_t*>(tr.data.ptr.buffer), tr.data_size,
                           reinterpret_cast<const uint64_t*>(tr.data.ptr.offsets), tr.offsets_size / sizeof(binder_size_t));
}

}  // namespace lxdb
