// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Shared pieces of the archive code: fd I/O with EINTR handling, little-endian
// fields, progress pacing and the format back ends behind Reader and Writer.
#pragma once

#include <sys/types.h>

#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "lx/archive.hpp"

namespace lx::archive::detail {

constexpr size_t kInBufferSize = 64 * 1024;
constexpr size_t kOutBufferSize = 128 * 1024;
constexpr uint64_t kProgressStep = 1024 * 1024;

// ENOSPC and EDQUOT are NoSpace, everything else IoError.
Status writeErrorStatus(int err);
// read() and pread() that retry EINTR and loop until n bytes or end of file.
// Return the byte count, or -1 with errno set.
ssize_t readFull(int fd, uint8_t* buf, size_t n);
ssize_t preadFull(int fd, uint8_t* buf, size_t n, uint64_t offset);
// Writes all n bytes. On failure returns the status and sets message.
Status writeAll(int fd, const uint8_t* buf, size_t n, std::string& message);

inline uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
inline uint32_t le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}
inline uint64_t le64(const uint8_t* p) { return le32(p) | (static_cast<uint64_t>(le32(p + 4)) << 32); }
inline void put16(std::string& out, uint64_t v) {
    out.push_back(static_cast<char>(v & 0xFF));
    out.push_back(static_cast<char>((v >> 8) & 0xFF));
}
inline void put32(std::string& out, uint64_t v) {
    put16(out, v & 0xFFFF);
    put16(out, (v >> 16) & 0xFFFF);
}
inline void put64(std::string& out, uint64_t v) {
    put32(out, v & 0xFFFFFFFFull);
    put32(out, v >> 32);
}

// Calls the progress callback about once per kProgressStep bytes and at the end.
class ProgressTicker {
public:
    explicit ProgressTicker(const Progress& progress) : progress_(progress) {}
    // False when the callback asked to stop.
    bool add(uint64_t n);
    bool finish();
    uint64_t total() const { return total_; }

private:
    const Progress& progress_;
    uint64_t total_ = 0;
    uint64_t nextReport_ = kProgressStep;
};

// Buffered writer that counts every byte it was given.
class FdSink {
public:
    explicit FdSink(int fd) : fd_(fd) { buf_.reserve(kOutBufferSize); }
    Status write(const uint8_t* data, size_t n);
    Status write(const std::string& s) { return write(reinterpret_cast<const uint8_t*>(s.data()), s.size()); }
    Status flush();
    uint64_t offset() const { return offset_; }
    const std::string& message() const { return message_; }

private:
    int fd_;
    std::vector<uint8_t> buf_;
    uint64_t offset_ = 0;
    std::string message_;
};

// The normalised paths of the symlinks a reader has seen, so that later
// entries that would be written through one can be refused.
class LinkTracker {
public:
    // Memory the recorded paths may take before the reader gives up.
    static constexpr size_t kMaxBytes = 8 * 1024 * 1024;
    // False once the recorded paths pass kMaxBytes.
    bool add(std::string_view path);
    // True when path is a recorded symlink or lies below one. Prefix hashes
    // are built in one pass, so a deep path costs O(length), not O(length^2).
    bool crosses(std::string_view path) const;

private:
    bool contains(uint64_t hash, std::string_view prefix) const;

    // FNV-1a hash of the path -> the path, to rule out collisions.
    std::unordered_multimap<uint64_t, std::string> paths_;
    size_t bytes_ = 0;
};

// DOS date and time fields are local time.
int64_t dosToUnixMs(uint16_t time, uint16_t date);
void unixMsToDos(int64_t ms, uint16_t& time, uint16_t& date);
int64_t floorDiv(int64_t a, int64_t b);

// True when the 512-byte block carries a valid tar header checksum
// (unsigned or signed sum).
bool tarChecksumOk(const uint8_t* block);

std::unique_ptr<Reader> openZipReader(int fd, const Limits& limits, Status* status, std::string* error);
std::unique_ptr<Reader> openTarReader(int fd, bool gzip, const Limits& limits, Status* status, std::string* error);
std::unique_ptr<Writer> openZipWriter(int fd, const WriterOptions& options);
std::unique_ptr<Writer> openTarWriter(int fd, bool gzip, int level);

}  // namespace lx::archive::detail
