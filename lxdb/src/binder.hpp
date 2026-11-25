// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// A Binder client and server spoken straight to /dev/binder, in the protocol libbinder uses. Android's own services take
// lxdb for any other native process, and LX's process sees an ordinary IBinder.
#pragma once

#include <sys/types.h>

#include <cstdint>
#include <functional>
#include <string>
#include <string_view>

#include "parcel.hpp"

struct binder_transaction_data;
struct binder_ptr_cookie;

namespace lxdb {

constexpr uint32_t kFirstCallTransaction = 1;
constexpr uint32_t kOneway = 0x01;  // TF_ONE_WAY

/** Failure of the transport itself, as opposed to an exception in a reply. */
constexpr int kDeadObject = -32;       // -EPIPE
constexpr int kFailedTransaction = -5;  // -EIO
constexpr int kUnknownTransaction = -74;  // UNKNOWN_TRANSACTION
constexpr int kFrozen = -11;           // -EAGAIN

/** Who is calling. The kernel fills it in, so it cannot be forged. */
struct Caller {
    pid_t pid = 0;
    uid_t uid = 0;
};

/** A binder object that lives in this process. */
class Node {
public:
    virtual ~Node() = default;
    virtual std::string_view descriptor() const = 0;
    /** Fills [reply]; anything but 0 goes back as a binder status. */
    virtual int onTransact(uint32_t code, ParcelReader& in, Parcel& reply, const Caller& caller) = 0;

    void writeTo(Parcel& p) {
        shared_ = true;
        p.writeLocalBinder(this);
    }
    /** The owner has finished with this node. It goes when the kernel has no reference left either. */
    void abandon();

private:
    friend void nodeCommand(int fd, uint32_t cmd, Node* node, const binder_ptr_cookie& refs);
    bool shared_ = false;
    bool strong_ = false;
    bool weak_ = false;
    bool abandoned_ = false;
};

/** A binder with nothing to say: the identity of a wake lock, or of this process to Android. */
class Token : public Node {
public:
    std::string_view descriptor() const override { return {}; }
    int onTransact(uint32_t, ParcelReader&, Parcel&, const Caller&) override { return 0; }
};

/** The buffer of a reply. It stays valid until this object goes, and then returns to the driver. */
class Reply {
public:
    Reply() = default;
    ~Reply();
    Reply(const Reply&) = delete;
    Reply& operator=(const Reply&) = delete;

    ParcelReader& reader() { return reader_; }
    /** The uid that wrote the reply, as the kernel saw it. */
    uid_t senderUid() const { return sender_; }
    /** Takes over a reply the driver delivered. */
    void adopt(const binder_transaction_data& tr);

private:
    const void* buffer_ = nullptr;
    ParcelReader reader_;
    uid_t sender_ = 0;
};

struct Death;

class Binder {
public:
    static Binder& get();

    bool open(std::string* why);
    /** Joins [count] threads to the pool that serves this process's nodes. */
    void startLoopers(int count);

    int transact(uint32_t handle, uint32_t code, const Parcel& data, Reply* reply, uint32_t flags = 0);

    /** Keeps a handle past the buffer it arrived in. */
    void acquire(uint32_t handle);
    void release(uint32_t handle);
    /** [onDeath] runs on a binder thread when the handle's process ends. */
    Death* linkToDeath(uint32_t handle, std::function<void()> onDeath);
    /** Before release(), or the kernel has no reference left to clear. */
    void unlinkToDeath(Death* death);

private:
    friend class Reply;
    Binder() = default;
    void freeBuffer(const void* buffer);
    int fd_ = -1;
};

}  // namespace lxdb
