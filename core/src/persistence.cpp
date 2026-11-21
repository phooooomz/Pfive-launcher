// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/persistence.hpp"

#include <array>
#include <cerrno>
#include <charconv>
#include <cstdio>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace lx {

namespace {

std::array<uint32_t, 256> makeTable() {
    std::array<uint32_t, 256> t{};
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t c = i;
        for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        t[i] = c;
    }
    return t;
}

// Owns a file descriptor so every early return closes it.
class Fd {
public:
    explicit Fd(int fd) : fd_(fd) {}
    ~Fd() {
        if (fd_ >= 0) ::close(fd_);
    }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    int get() const { return fd_; }
    bool close() {
        int fd = fd_;
        fd_ = -1;
        return fd < 0 || ::close(fd) == 0;
    }

private:
    int fd_;
};

bool writeAll(int fd, std::string_view data) {
    size_t off = 0;
    while (off < data.size()) {
        ssize_t n = ::write(fd, data.data() + off, data.size() - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        off += static_cast<size_t>(n);
    }
    return true;
}

std::string dirOf(const std::string& path) {
    size_t slash = path.rfind('/');
    if (slash == std::string::npos) return ".";
    if (slash == 0) return "/";
    return path.substr(0, slash);
}

void syncDir(const std::string& path) {
    Fd dir(::open(dirOf(path).c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (dir.get() >= 0) ::fsync(dir.get());
}

bool exists(const std::string& path) {
    struct stat st{};
    return ::stat(path.c_str(), &st) == 0;
}

}  // namespace

uint32_t crc32(std::string_view data) {
    static const auto table = makeTable();
    uint32_t c = 0xFFFFFFFFu;
    for (unsigned char b : data) c = table[(c ^ b) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

std::string wrapEnvelope(std::string_view payload) {
    char hex[16];
    std::snprintf(hex, sizeof hex, "%08x", crc32(payload));
    std::string out = "LXL1 ";
    out += hex;
    out += ' ';
    out += std::to_string(payload.size());
    out += '\n';
    out.append(payload);
    return out;
}

std::optional<std::string> unwrapEnvelope(std::string_view file) {
    size_t nl = file.find('\n');
    if (nl == std::string_view::npos || nl > 64) return std::nullopt;
    std::string_view header = file.substr(0, nl);
    if (header.substr(0, 5) != "LXL1 ") return std::nullopt;
    std::string_view rest = header.substr(5);
    size_t sp = rest.find(' ');
    if (sp != 8) return std::nullopt;
    uint32_t crc = 0;
    auto r1 = std::from_chars(rest.data(), rest.data() + 8, crc, 16);
    if (r1.ec != std::errc() || r1.ptr != rest.data() + 8) return std::nullopt;
    size_t len = 0;
    std::string_view lenStr = rest.substr(9);
    auto r2 = std::from_chars(lenStr.data(), lenStr.data() + lenStr.size(), len);
    if (r2.ec != std::errc() || r2.ptr != lenStr.data() + lenStr.size()) return std::nullopt;
    std::string_view payload = file.substr(nl + 1);
    if (payload.size() != len || crc32(payload) != crc) return std::nullopt;
    return std::string(payload);
}

std::optional<std::string> readFile(const std::string& path, size_t maxBytes) {
    Fd fd(::open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (fd.get() < 0) return std::nullopt;
    struct stat st{};
    if (::fstat(fd.get(), &st) != 0 || !S_ISREG(st.st_mode)) return std::nullopt;
    if (static_cast<uint64_t>(st.st_size) > maxBytes) return std::nullopt;
    std::string data;
    data.resize(static_cast<size_t>(st.st_size));
    size_t off = 0;
    while (off < data.size()) {
        ssize_t n = ::read(fd.get(), data.data() + off, data.size() - off);
        if (n < 0) {
            if (errno == EINTR) continue;
            return std::nullopt;
        }
        if (n == 0) break;
        off += static_cast<size_t>(n);
    }
    data.resize(off);
    return data;
}

bool writeFileAtomic(const std::string& path, std::string_view data) {
    std::string tmp = path + ".tmp";
    {
        Fd fd(::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600));
        if (fd.get() < 0) return false;
        if (!writeAll(fd.get(), data) || ::fsync(fd.get()) != 0 || !fd.close()) {
            ::unlink(tmp.c_str());
            return false;
        }
    }
    if (::rename(tmp.c_str(), path.c_str()) != 0) {
        ::unlink(tmp.c_str());
        return false;
    }
    syncDir(path);
    return true;
}

LoadResult Store::load() const {
    LoadResult r;
    if (auto main = readFile(path_, kMaxFileBytes)) {
        if (auto payload = unwrapEnvelope(*main)) {
            r.source = LoadSource::Main;
            r.payload = std::move(*payload);
            return r;
        }
        r.mainCorrupt = true;
    } else if (exists(path_)) {
        r.mainCorrupt = true;  // unreadable or oversized
    }
    if (auto bak = readFile(path_ + ".bak", kMaxFileBytes)) {
        if (auto payload = unwrapEnvelope(*bak)) {
            r.source = LoadSource::Backup;
            r.payload = std::move(*payload);
            return r;
        }
        r.backupCorrupt = true;
    }
    return r;
}

bool Store::save(std::string_view payload) const {
    if (payload.size() > kMaxFileBytes - 64) return false;
    std::string file = wrapEnvelope(payload);
    std::string tmp = path_ + ".tmp";
    {
        Fd fd(::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600));
        if (fd.get() < 0) return false;
        if (!writeAll(fd.get(), file) || ::fsync(fd.get()) != 0 || !fd.close()) {
            ::unlink(tmp.c_str());
            return false;
        }
    }
    // Keep the last good file as backup. A crash between the two renames leaves
    // only the backup, which load() picks up.
    if (exists(path_)) {
        auto current = readFile(path_, kMaxFileBytes);
        if (current && unwrapEnvelope(*current)) {
            if (::rename(path_.c_str(), (path_ + ".bak").c_str()) != 0) {
                ::unlink(tmp.c_str());
                return false;
            }
        }
    }
    if (::rename(tmp.c_str(), path_.c_str()) != 0) {
        ::unlink(tmp.c_str());
        return false;
    }
    syncDir(path_);
    return true;
}

void Store::quarantineMain() const {
    if (exists(path_)) ::rename(path_.c_str(), (path_ + ".corrupt").c_str());
}

LoadResult Store::loadBackup() const {
    LoadResult r;
    if (auto bak = readFile(path_ + ".bak", kMaxFileBytes)) {
        if (auto payload = unwrapEnvelope(*bak)) {
            r.source = LoadSource::Backup;
            r.payload = std::move(*payload);
            return r;
        }
        r.backupCorrupt = true;
    }
    return r;
}

void Store::setAside(LoadSource which, const std::string& suffix) const {
    if (which == LoadSource::None) return;
    std::string from = which == LoadSource::Main ? path_ : path_ + ".bak";
    if (exists(from)) ::rename(from.c_str(), (from + suffix).c_str());
}

}  // namespace lx
