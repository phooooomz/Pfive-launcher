// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// fd helpers, the Reader and Writer bases, format detection and DOS times.
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstring>
#include <ctime>

#include "archive_internal.hpp"

namespace lx::archive {

namespace detail {

Status writeErrorStatus(int err) {
#ifdef EDQUOT
    if (err == EDQUOT) return Status::NoSpace;
#endif
    return err == ENOSPC ? Status::NoSpace : Status::IoError;
}

ssize_t readFull(int fd, uint8_t* buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        const ssize_t r = ::read(fd, buf + got, n - got);
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) break;
        got += static_cast<size_t>(r);
    }
    return static_cast<ssize_t>(got);
}

ssize_t preadFull(int fd, uint8_t* buf, size_t n, uint64_t offset) {
    size_t got = 0;
    while (got < n) {
        const ssize_t r = ::pread(fd, buf + got, n - got, static_cast<off_t>(offset + got));
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) break;
        got += static_cast<size_t>(r);
    }
    return static_cast<ssize_t>(got);
}

Status writeAll(int fd, const uint8_t* buf, size_t n, std::string& message) {
    size_t done = 0;
    while (done < n) {
        const ssize_t w = ::write(fd, buf + done, n - done);
        if (w < 0) {
            if (errno == EINTR) continue;
            const int err = errno;
            message = std::string("write failed: ") + std::strerror(err);
            return writeErrorStatus(err);
        }
        if (w == 0) {
            message = "write made no progress";
            return Status::IoError;
        }
        done += static_cast<size_t>(w);
    }
    return Status::Ok;
}

bool ProgressTicker::add(uint64_t n) {
    total_ += n;
    if (!progress_ || total_ < nextReport_) return true;
    nextReport_ = total_ + kProgressStep;
    return progress_(total_);
}

bool ProgressTicker::finish() { return !progress_ || progress_(total_); }

Status FdSink::write(const uint8_t* data, size_t n) {
    offset_ += n;
    while (n > 0) {
        const size_t room = kOutBufferSize - buf_.size();
        if (room == 0) {
            const Status s = flush();
            if (s != Status::Ok) return s;
            continue;
        }
        const size_t take = n < room ? n : room;
        buf_.insert(buf_.end(), data, data + take);
        data += take;
        n -= take;
    }
    return Status::Ok;
}

Status FdSink::flush() {
    if (buf_.empty()) return Status::Ok;
    const Status s = writeAll(fd_, buf_.data(), buf_.size(), message_);
    buf_.clear();
    return s;
}

int64_t floorDiv(int64_t a, int64_t b) {
    int64_t q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) --q;
    return q;
}

int64_t dosToUnixMs(uint16_t time, uint16_t date) {
    std::tm tm{};
    tm.tm_year = ((date >> 9) & 0x7F) + 80;
    tm.tm_mon = ((date >> 5) & 0x0F) - 1;
    tm.tm_mday = date & 0x1F;
    tm.tm_hour = (time >> 11) & 0x1F;
    tm.tm_min = (time >> 5) & 0x3F;
    tm.tm_sec = (time & 0x1F) * 2;
    tm.tm_isdst = -1;
    const std::time_t t = std::mktime(&tm);
    if (t == static_cast<std::time_t>(-1)) return 0;
    return static_cast<int64_t>(t) * 1000;
}

void unixMsToDos(int64_t ms, uint16_t& time, uint16_t& date) {
    const auto t = static_cast<std::time_t>(floorDiv(ms, 1000));
    std::tm tm{};
    if (!localtime_r(&t, &tm) || tm.tm_year < 80) {
        time = 0;
        date = (1 << 5) | 1;  // 1980-01-01, the earliest DOS date
        return;
    }
    if (tm.tm_year > 207) {
        time = static_cast<uint16_t>((23 << 11) | (59 << 5) | 29);
        date = static_cast<uint16_t>((127 << 9) | (12 << 5) | 31);
        return;
    }
    time = static_cast<uint16_t>((tm.tm_hour << 11) | (tm.tm_min << 5) | (tm.tm_sec / 2));
    date = static_cast<uint16_t>(((tm.tm_year - 80) << 9) | ((tm.tm_mon + 1) << 5) | tm.tm_mday);
}

bool tarChecksumOk(const uint8_t* block) {
    // The stored value is octal, space or NUL terminated, possibly with leading spaces.
    const uint8_t* f = block + 148;
    size_t i = 0;
    while (i < 8 && f[i] == ' ') ++i;
    uint64_t stored = 0;
    size_t digits = 0;
    for (; i < 8 && f[i] >= '0' && f[i] <= '7'; ++i, ++digits) stored = stored * 8 + (f[i] - '0');
    if (digits == 0) return false;
    for (; i < 8; ++i)
        if (f[i] != ' ' && f[i] != 0) return false;
    uint64_t unsignedSum = 0;
    int64_t signedSum = 0;
    for (size_t k = 0; k < 512; ++k) {
        const uint8_t b = (k >= 148 && k < 156) ? static_cast<uint8_t>(' ') : block[k];
        unsignedSum += b;
        signedSum += static_cast<int8_t>(b);
    }
    return stored == unsignedSum || static_cast<int64_t>(stored) == signedSum;
}

namespace {

constexpr uint64_t kFnvOffset = 14695981039346656037ull;
constexpr uint64_t kFnvPrime = 1099511628211ull;

uint64_t fnvStep(uint64_t h, char c) { return (h ^ static_cast<uint8_t>(c)) * kFnvPrime; }

}  // namespace

bool LinkTracker::add(std::string_view path) {
    uint64_t h = kFnvOffset;
    for (char c : path) h = fnvStep(h, c);
    if (contains(h, path)) return true;
    // The string plus a rough cost for the hash node.
    bytes_ += path.size() + 64;
    if (bytes_ > kMaxBytes) return false;
    paths_.emplace(h, std::string(path));
    return true;
}

bool LinkTracker::contains(uint64_t hash, std::string_view prefix) const {
    const auto [first, last] = paths_.equal_range(hash);
    for (auto it = first; it != last; ++it)
        if (it->second == prefix) return true;
    return false;
}

bool LinkTracker::crosses(std::string_view path) const {
    if (paths_.empty()) return false;
    uint64_t h = kFnvOffset;
    for (size_t i = 0; i < path.size(); ++i) {
        if (path[i] == '/' && contains(h, path.substr(0, i))) return true;
        h = fnvStep(h, path[i]);
    }
    return contains(h, path);
}

}  // namespace detail

using namespace detail;

Reader::Reader(int fd, const Limits& limits) : fd_(fd), limits_(limits) {}

Reader::~Reader() {
    if (fd_ >= 0) ::close(fd_);
}

Status Reader::fail(Status status, std::string message, bool sticky) {
    error_ = std::move(message);
    if (sticky) closed_ = status;
    return status;
}

Status Reader::applyLinkRules(Entry& entry) {
    if (entry.verdict != PathVerdict::Ok) return Status::Ok;
    const bool symlink = entry.type == EntryType::Symlink;
    // Recorded even when its target is refused below: the app does not create
    // it then, and a real folder in its place is harmless to refuse as well.
    std::string recordAs = symlink ? entry.path : std::string();
    PathVerdict v = PathVerdict::Ok;
    if (links_ && links_->crosses(entry.path)) {
        v = PathVerdict::DotDot;
    } else if (symlink || entry.type == EntryType::Hardlink) {
        std::string target;
        v = checkLinkTarget(entry.path, entry.link, !symlink, &target);
        // A hard link made through a symlinked folder would link the file the symlink points at.
        if (v == PathVerdict::Ok && !symlink && links_ && links_->crosses(target)) v = PathVerdict::DotDot;
    }
    if (v != PathVerdict::Ok) {
        entry.verdict = v;
        entry.path.clear();
    }
    if (!recordAs.empty()) {
        if (!links_) links_ = std::make_unique<LinkTracker>();
        if (!links_->add(recordAs)) return fail(Status::LimitExceeded, "too many symbolic links", true);
    }
    return Status::Ok;
}

Status Reader::admitEntry(uint64_t declaredSize) {
    ++entries_;
    if (limits_.maxEntries && entries_ > limits_.maxEntries)
        return fail(Status::LimitExceeded, "more entries than the limit allows", true);
    if (limits_.maxEntryBytes && declaredSize > limits_.maxEntryBytes)
        return fail(Status::LimitExceeded, "entry is larger than the limit", true);
    declaredTotal_ += declaredSize;
    if (declaredTotal_ < declaredSize || (limits_.maxTotalBytes && declaredTotal_ > limits_.maxTotalBytes))
        return fail(Status::LimitExceeded, "archive content is larger than the limit", true);
    return Status::Ok;
}

namespace {

bool ratioAbove(const Limits& limits, uint64_t out, uint64_t in) {
    return limits.maxRatioX100 && out > limits.ratioGraceBytes &&
           static_cast<double>(out) * 100.0 > static_cast<double>(limits.maxRatioX100) * static_cast<double>(in);
}

}  // namespace

Status Reader::checkRatio(uint64_t archiveOut, uint64_t archiveIn) {
    if (ratioAbove(limits_, archiveOut, archiveIn))
        return fail(Status::LimitExceeded, "compression ratio is above the limit", true);
    return Status::Ok;
}

Status Reader::accountOutput(uint64_t chunk, uint64_t entryProduced, uint64_t entryIn, uint64_t archiveOut,
                             uint64_t archiveIn) {
    producedTotal_ += chunk;
    if (limits_.maxEntryBytes && entryProduced > limits_.maxEntryBytes)
        return fail(Status::LimitExceeded, "entry is larger than the limit", true);
    if (limits_.maxTotalBytes && producedTotal_ > limits_.maxTotalBytes)
        return fail(Status::LimitExceeded, "archive content is larger than the limit", true);
    if (entryIn && ratioAbove(limits_, entryProduced, entryIn))
        return fail(Status::LimitExceeded, "compression ratio is above the limit", true);
    return checkRatio(archiveOut, archiveIn);
}

std::unique_ptr<Reader> Reader::open(int fd, Format format, const Limits& limits, Status* status,
                                     std::string* error) {
    Status ignoredStatus;
    std::string ignoredError;
    if (!status) status = &ignoredStatus;
    if (!error) error = &ignoredError;
    if (fd < 0) {
        *status = Status::IoError;
        *error = "invalid file descriptor";
        return nullptr;
    }
    if (format == Format::Unknown) {
        // detect() could not give back the bytes it read from a pipe.
        if (::lseek(fd, 0, SEEK_CUR) < 0) {
            ::close(fd);
            *status = Status::Unsupported;
            *error = "the format must be given for a stream that cannot seek";
            return nullptr;
        }
        format = detect(fd);
    }
    switch (format) {
        case Format::Zip: return openZipReader(fd, limits, status, error);
        case Format::Tar: return openTarReader(fd, false, limits, status, error);
        case Format::TarGz: return openTarReader(fd, true, limits, status, error);
        case Format::Unknown: break;
    }
    ::close(fd);
    *status = Status::FormatError;
    *error = "not a ZIP, TAR or TAR.GZ archive";
    return nullptr;
}

Writer::~Writer() {
    if (fd_ >= 0) ::close(fd_);
}

Status Writer::fail(Status status, std::string message, bool sticky) {
    error_ = std::move(message);
    if (sticky) broken_ = status;
    return status;
}

Status Writer::closeFd() {
    const int fd = fd_;
    fd_ = -1;
    // close() is not retried on EINTR: on Linux the fd is gone either way.
    if (::close(fd) != 0) {
        const int err = errno;
        if (err == EINTR) return Status::Ok;
        return fail(writeErrorStatus(err), std::string("close failed: ") + std::strerror(err));
    }
    return Status::Ok;
}

std::unique_ptr<Writer> Writer::open(int fd, Format format, const WriterOptions& options, std::string* error) {
    std::string ignored;
    if (!error) error = &ignored;
    const bool known = format == Format::Zip || format == Format::Tar || format == Format::TarGz;
    if (fd < 0 || !known || options.level < 0 || options.level > 9) {
        if (fd >= 0) ::close(fd);
        *error = fd < 0 ? "invalid file descriptor" : !known ? "unknown archive format" : "level must be 0 to 9";
        return nullptr;
    }
    if (format == Format::Zip) return openZipWriter(fd, options);
    return openTarWriter(fd, format == Format::TarGz, options.level);
}

Format detect(int fd) {
    if (fd < 0) return Format::Unknown;
    const off_t start = ::lseek(fd, 0, SEEK_CUR);
    uint8_t head[512];
    ssize_t n = start >= 0 ? preadFull(fd, head, sizeof head, static_cast<uint64_t>(start)) : -1;
    if (n < 0) {
        // A pipe, or one of the few seekable fds that refuse pread.
        n = readFull(fd, head, sizeof head);
        if (start >= 0) ::lseek(fd, start, SEEK_SET);
    }
    if (n < 2) return Format::Unknown;
    if (head[0] == 0x1F && head[1] == 0x8B) return Format::TarGz;
    if (n >= 4 && head[0] == 'P' && head[1] == 'K' &&
        ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6) || (head[2] == 7 && head[3] == 8)))
        return Format::Zip;
    if (n == 512 && (std::memcmp(head + 257, "ustar", 5) == 0 || tarChecksumOk(head))) return Format::Tar;
    return Format::Unknown;
}

}  // namespace lx::archive
