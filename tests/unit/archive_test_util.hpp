// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Helpers shared by the archive tests: scratch files, writing a list of items
// with the C++ writer, reading an archive back into memory, hand-built tar
// headers and running a command.
#pragma once

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <random>
#include <string>
#include <vector>

#include "lx/archive.hpp"

namespace lxtest {

using lx::archive::Entry;
using lx::archive::EntryType;
using lx::archive::Format;
using lx::archive::Limits;
using lx::archive::PathVerdict;
using lx::archive::Status;
using lx::archive::WriterOptions;

class ScratchDir {
public:
    ScratchDir() {
        const char* base = std::getenv("LX_TEST_TMP");
        std::string t = std::string(base ? base : "/tmp") + "/lxarchiveXXXXXX";
        path_ = mkdtemp(t.data()) ? t : std::string();
    }
    ~ScratchDir() {
        if (path_.empty()) return;
        const std::string cmd = "rm -rf '" + path_ + "'";
        if (std::system(cmd.c_str()) != 0) std::perror("cleanup");
    }
    std::string file(const std::string& name) const { return path_ + "/" + name; }
    const std::string& path() const { return path_; }

private:
    std::string path_;
};

inline std::string fixture(const std::string& name) { return std::string(LX_TEST_DATA_DIR) + "/archives/" + name; }

inline std::string readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

inline void writeFile(const std::string& path, const std::string& data) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(data.data(), static_cast<std::streamsize>(data.size()));
}

// A memory-backed fd for extract() output and addFile() input.
inline int memFd(const std::string& data = {}) {
    const int fd = memfd_create("lxtest", MFD_CLOEXEC);
    size_t done = 0;
    while (done < data.size()) {
        const ssize_t w = ::write(fd, data.data() + done, data.size() - done);
        if (w <= 0) break;
        done += static_cast<size_t>(w);
    }
    lseek(fd, 0, SEEK_SET);
    return fd;
}

inline std::string fdContents(int fd) {
    std::string out;
    lseek(fd, 0, SEEK_SET);
    char buf[65536];
    for (;;) {
        const ssize_t r = ::read(fd, buf, sizeof buf);
        if (r <= 0) break;
        out.append(buf, static_cast<size_t>(r));
    }
    return out;
}

// Mixed content: a random half and a compressible half.
inline std::string sampleData(size_t size, unsigned seed) {
    std::mt19937 rng(seed);
    std::string s(size, '\0');
    for (size_t i = 0; i < size; ++i) s[i] = i % 4096 < 2048 ? static_cast<char>(rng()) : static_cast<char>('a' + i % 7);
    return s;
}

struct Item {
    std::string path;
    bool dir = false;
    std::string data;
    int64_t mtimeMs = 1700000000000;
    uint32_t mode = 0;
};

inline Status writeArchive(const std::string& file, Format format, const std::vector<Item>& items,
                           WriterOptions options = {}, std::string* error = nullptr) {
    const int fd = ::open(file.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644);
    std::string openError;
    auto w = lx::archive::Writer::open(fd, format, options, &openError);
    if (!w) {
        if (error) *error = openError;
        return Status::IoError;
    }
    for (const Item& it : items) {
        Status s;
        if (it.dir) {
            s = w->addDirectory(it.path, it.mtimeMs, it.mode);
        } else {
            const int in = memFd(it.data);
            s = w->addFile(it.path, in, it.data.size(), it.mtimeMs, it.mode, {});
            ::close(in);
        }
        if (s != Status::Ok) {
            if (error) *error = w->lastError();
            return s;
        }
    }
    const Status s = w->finish();
    if (s != Status::Ok && error) *error = w->lastError();
    return s;
}

struct ReadEntry {
    Entry entry;
    Status extracted = Status::Ok;
    std::string data;
};

struct ReadResult {
    Status openStatus = Status::Ok;
    Status last = Status::End;  // End when the archive was read to its end
    std::vector<ReadEntry> entries;
    std::string error;
};

// Reads every entry and extracts files (and ZIP symlinks) into memory.
inline ReadResult readAll(int fd, Format format, const Limits& limits = {}) {
    ReadResult r;
    auto reader = lx::archive::Reader::open(fd, format, limits, &r.openStatus, &r.error);
    if (!reader) {
        r.last = r.openStatus;
        return r;
    }
    for (;;) {
        ReadEntry e;
        const Status s = reader->next(e.entry);
        if (s != Status::Ok) {
            r.last = s;
            r.error = reader->lastError();
            break;
        }
        if (e.entry.type == EntryType::File || e.entry.type == EntryType::Symlink) {
            const int out = memFd();
            e.extracted = reader->extract(out, {});
            e.data = fdContents(out);
            ::close(out);
        }
        r.entries.push_back(std::move(e));
    }
    return r;
}

inline ReadResult readAll(const std::string& path, Format format, const Limits& limits = {}) {
    return readAll(::open(path.c_str(), O_RDONLY | O_CLOEXEC), format, limits);
}

inline ReadResult readBytes(const std::string& bytes, Format format, const Limits& limits = {}) {
    return readAll(memFd(bytes), format, limits);
}

// Hand-built tar header for the parser tests; link goes in the link name field.
inline std::string tarHeader(const std::string& name, char type, const std::string& sizeField, bool ustar = true,
                             const std::string& link = {}) {
    std::string h(512, '\0');
    std::memcpy(h.data(), name.data(), std::min<size_t>(name.size(), 100));
    std::memcpy(h.data() + 100, "0000644", 7);
    std::memcpy(h.data() + 108, "0000000", 7);
    std::memcpy(h.data() + 116, "0000000", 7);
    std::memcpy(h.data() + 124, sizeField.data(), std::min<size_t>(sizeField.size(), 12));
    std::memcpy(h.data() + 136, "14000000000", 11);
    h[156] = type;
    std::memcpy(h.data() + 157, link.data(), std::min<size_t>(link.size(), 100));
    if (ustar) std::memcpy(h.data() + 257, "ustar\0" "00", 8);
    std::memset(h.data() + 148, ' ', 8);
    unsigned sum = 0;
    for (char c : h) sum += static_cast<uint8_t>(c);
    char chk[8];
    std::snprintf(chk, sizeof chk, "%06o", sum);
    std::memcpy(h.data() + 148, chk, 7);
    return h;
}

inline std::string octal(uint64_t v) {
    char b[13];
    std::snprintf(b, sizeof b, "%011llo", static_cast<unsigned long long>(v));
    return b;
}

inline std::string padded(std::string data) {
    data.resize((data.size() + 511) / 512 * 512, '\0');
    return data;
}

inline std::string paxRecord(const std::string& key, const std::string& value) {
    const size_t body = key.size() + value.size() + 3;
    size_t len = body + 1;
    while (std::to_string(len).size() + body != len) len = std::to_string(len).size() + body;
    return std::to_string(len) + " " + key + "=" + value + "\n";
}

// Runs a shell command and returns its output; status receives pclose's result.
inline std::string runCommand(const std::string& cmd, int* status) {
    std::string out;
    FILE* p = popen(cmd.c_str(), "r");
    if (!p) {
        *status = -1;
        return out;
    }
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, p)) > 0) out.append(buf, n);
    *status = pclose(p);
    return out;
}

}  // namespace lxtest
