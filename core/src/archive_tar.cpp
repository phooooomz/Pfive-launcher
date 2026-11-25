// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// TAR reading (v7, ustar, GNU and PAX headers) and ustar/PAX writing, plain
// or inside gzip. Both directions stream; nothing seeks except an optional
// fast skip over data on a plain TAR file.
#define ZLIB_CONST
#include <zlib.h>

#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <optional>
#include <vector>

#include "archive_internal.hpp"

namespace lx::archive::detail {

namespace {

constexpr size_t kBlock = 512;
constexpr size_t kRecord = 20 * kBlock;
constexpr uint64_t kMaxPaxBytes = 256 * 1024;  // PAX records are parsed in memory
// After the end-of-archive blocks of a TAR.GZ, at most this much is inflated
// to reach the gzip trailer. Record padding is 10 KiB (1 MiB with tar -b 2048).
constexpr uint64_t kMaxTrailingBytes = 4 * 1024 * 1024;
constexpr int kMaxMetaHeaders = 64;
constexpr uint64_t kMaxSparseBlocks = 4096;
constexpr uint64_t kOctal11Limit = 1ull << 33;  // 8^11, the first value 11 octal digits cannot hold

// A byte stream the tar parser reads from.
class Source {
public:
    virtual ~Source() = default;
    // Bytes read, 0 at the end of the stream, -1 on failure (see status()).
    virtual ssize_t read(uint8_t* buf, size_t n) = 0;
    // Skips up to n bytes without reading them when the stream allows it.
    // Returns how many were skipped; 0 means "read them instead".
    virtual uint64_t fastSkip(uint64_t) { return 0; }
    virtual uint64_t compressedIn() const { return 0; }
    virtual uint64_t producedOut() const { return 0; }
    // From now on, end the stream where the current gzip member ends.
    virtual void stopAtMemberEnd() {}
    Status status() const { return status_; }
    const std::string& message() const { return message_; }

protected:
    ssize_t failed(Status s, std::string m) {
        status_ = s;
        message_ = std::move(m);
        return -1;
    }
    Status status_ = Status::Ok;
    std::string message_;
};

// Reads the archive bytes. A regular file is read with pread from the offset
// it had at open, so the fd's offset (shared with the caller's fd through
// dup) never moves and the caller can read the file again. Pipes use read().
class FdSource final : public Source {
public:
    explicit FdSource(int fd) : fd_(fd), buf_(kInBufferSize) {
        struct stat st {};
        if (::fstat(fd, &st) == 0 && S_ISREG(st.st_mode)) {
            const off_t pos = ::lseek(fd, 0, SEEK_CUR);
            if (pos >= 0 && pos <= st.st_size) {
                positional_ = true;
                offset_ = static_cast<uint64_t>(pos);
                size_ = static_cast<uint64_t>(st.st_size);
            }
        }
    }

    ssize_t read(uint8_t* out, size_t n) override {
        if (pos_ == end_) {
            const ssize_t r = fill();
            if (r <= 0) return r;
        }
        const size_t take = std::min(n, end_ - pos_);
        std::memcpy(out, buf_.data() + pos_, take);
        pos_ += take;
        return static_cast<ssize_t>(take);
    }

    uint64_t fastSkip(uint64_t n) override {
        if (!positional_) return 0;
        const auto fromBuffer = static_cast<size_t>(std::min<uint64_t>(n, end_ - pos_));
        pos_ += fromBuffer;
        n -= fromBuffer;
        // Only skip within the size the file had at open, so truncation is still noticed.
        const uint64_t step = std::min(n, size_ > offset_ ? size_ - offset_ : 0);
        offset_ += step;
        return fromBuffer + step;
    }

    size_t buffered() const { return end_ - pos_; }
    // Lets GzipSource take raw bytes straight from the buffer.
    const uint8_t* peek() const { return buf_.data() + pos_; }
    void consume(size_t n) { pos_ += n; }
    ssize_t fill() {
        if (pos_ != end_) return static_cast<ssize_t>(end_ - pos_);
        const ssize_t r = positional_ ? preadFull(fd_, buf_.data(), buf_.size(), offset_)
                                      : readFull(fd_, buf_.data(), buf_.size());
        if (r < 0) return failed(Status::IoError, std::string("read failed: ") + std::strerror(errno));
        pos_ = 0;
        end_ = static_cast<size_t>(r);
        offset_ += end_;
        return r;
    }

private:
    int fd_;
    std::vector<uint8_t> buf_;
    size_t pos_ = 0;
    size_t end_ = 0;
    bool positional_ = false;
    // Next file offset to read (positional mode).
    uint64_t offset_ = 0;
    uint64_t size_ = 0;
};

// Inflates gzip members one after another (concatenated .gz files are one
// stream). Bytes after the last member that do not start a new member are ignored.
class GzipSource final : public Source {
public:
    explicit GzipSource(int fd) : raw_(fd) {}
    ~GzipSource() override {
        if (init_) inflateEnd(&z_);
    }

    ssize_t read(uint8_t* out, size_t n) override {
        if (done_ || n == 0) return 0;
        if (!init_) {
            if (inflateInit2(&z_, 16 + MAX_WBITS) != Z_OK) return failed(Status::IoError, "inflate init failed");
            init_ = true;
        }
        const auto want = static_cast<uInt>(std::min<size_t>(n, kInBufferSize));
        z_.next_out = out;
        z_.avail_out = want;
        while (z_.avail_out == want) {
            if (memberDone_ && stopAtMemberEnd_) {
                done_ = true;
                break;
            }
            if (raw_.buffered() == 0) {
                const ssize_t r = raw_.fill();
                if (r < 0) return failed(raw_.status(), raw_.message());
                if (r == 0) {
                    if (!memberDone_) return failed(Status::Corrupt, "gzip data ends early");
                    done_ = true;
                    return 0;
                }
            }
            if (memberDone_) {
                // Another member follows only if it starts with the gzip magic byte.
                if (raw_.peek()[0] != 0x1F) {
                    done_ = true;
                    return 0;
                }
                inflateReset(&z_);
                memberDone_ = false;
            }
            z_.next_in = raw_.peek();
            z_.avail_in = static_cast<uInt>(raw_.buffered());
            const uInt before = z_.avail_in;
            const int rc = ::inflate(&z_, Z_NO_FLUSH);
            raw_.consume(before - z_.avail_in);
            consumed_ += before - z_.avail_in;
            if (rc == Z_STREAM_END) {
                memberDone_ = true;
            } else if (rc == Z_DATA_ERROR) {
                // The gzip trailer holds a CRC-32 and the length of each member.
                const std::string msg = z_.msg ? z_.msg : "";
                const bool trailer = msg == "incorrect data check" || msg == "incorrect length check";
                return failed(trailer ? Status::CrcError : Status::Corrupt, "gzip data is damaged: " + msg);
            } else if (rc == Z_MEM_ERROR) {
                return failed(Status::IoError, "out of memory while inflating");
            } else if (rc != Z_OK && rc != Z_BUF_ERROR) {
                return failed(Status::Corrupt, "gzip data is damaged");
            }
        }
        const size_t got = want - z_.avail_out;
        produced_ += got;
        return static_cast<ssize_t>(got);
    }

    uint64_t compressedIn() const override { return consumed_; }
    uint64_t producedOut() const override { return produced_; }
    void stopAtMemberEnd() override { stopAtMemberEnd_ = true; }

private:
    FdSource raw_;
    z_stream z_{};
    bool init_ = false;
    bool memberDone_ = false;
    bool stopAtMemberEnd_ = false;
    bool done_ = false;
    uint64_t consumed_ = 0;
    uint64_t produced_ = 0;
};

std::string_view field(const uint8_t* h, size_t off, size_t len) {
    const auto* p = reinterpret_cast<const char*>(h + off);
    const auto* nul = static_cast<const char*>(std::memchr(p, 0, len));
    return std::string_view(p, nul ? static_cast<size_t>(nul - p) : len);
}

// Octal (space or NUL terminated, spaces around) or GNU base-256.
bool parseNumber(const uint8_t* f, size_t len, int64_t& out) {
    if (f[0] == 0x80 || f[0] == 0xFF) {
        // Big-endian two's complement in the bytes after the marker; it has to fit in 64 bits.
        const uint8_t fill = f[0] == 0xFF ? 0xFF : 0x00;
        const size_t value = len - 1 > 8 ? len - 8 : 1;
        for (size_t i = 1; i < value; ++i)
            if (f[i] != fill) return false;
        uint64_t u = fill ? UINT64_MAX : 0;
        for (size_t i = value; i < len; ++i) u = (u << 8) | f[i];
        out = static_cast<int64_t>(u);
        if ((fill == 0) != (out >= 0)) return false;
        return true;
    }
    std::string_view s = field(f, 0, len);
    while (!s.empty() && s.front() == ' ') s.remove_prefix(1);
    while (!s.empty() && s.back() == ' ') s.remove_suffix(1);
    uint64_t v = 0;
    for (char c : s) {
        if (c < '0' || c > '7') return false;
        if (v > (static_cast<uint64_t>(INT64_MAX) >> 3)) return false;
        v = v * 8 + static_cast<uint64_t>(c - '0');
    }
    out = static_cast<int64_t>(v);
    return true;
}

bool parseDecimal(std::string_view s, uint64_t& out) {
    if (s.empty() || s.size() > 19) return false;
    uint64_t v = 0;
    for (char c : s) {
        if (c < '0' || c > '9') return false;
        v = v * 10 + static_cast<uint64_t>(c - '0');
    }
    out = v;
    return true;
}

// PAX time: [-]seconds[.fraction], kept to milliseconds.
bool parsePaxTime(std::string_view s, int64_t& ms) {
    const bool negative = !s.empty() && s.front() == '-';
    if (negative) s.remove_prefix(1);
    const size_t dot = s.find('.');
    uint64_t seconds = 0;
    if (!parseDecimal(s.substr(0, dot), seconds) || seconds > static_cast<uint64_t>(INT64_MAX / 1000 - 1)) return false;
    uint64_t frac = 0;
    if (dot != std::string_view::npos) {
        std::string_view f = s.substr(dot + 1);
        for (char c : f)
            if (c < '0' || c > '9') return false;
        std::string digits(f.substr(0, 3));
        digits.resize(3, '0');
        frac = static_cast<uint64_t>((digits[0] - '0') * 100 + (digits[1] - '0') * 10 + (digits[2] - '0'));
    }
    const auto total = static_cast<int64_t>(seconds * 1000 + frac);
    ms = negative ? -total : total;
    return true;
}

struct PaxValues {
    std::optional<std::string> path;
    std::optional<std::string> linkpath;
    std::optional<uint64_t> size;
    std::optional<int64_t> mtimeMs;
    bool sparse = false;
};

class TarReader final : public Reader {
public:
    TarReader(int fd, bool gzip, const Limits& limits) : Reader(fd, limits), gzip_(gzip), buf_(kInBufferSize) {
        // The source reads through fd_; Reader closes it.
        if (gzip)
            src_ = std::make_unique<GzipSource>(fd);
        else
            src_ = std::make_unique<FdSource>(fd);
    }

    Status next(Entry& entry) override;
    Status extract(int outFd, const Progress& progress) override;

private:
    Status sourceFailed() { return fail(src_->status(), src_->message(), true); }
    // Reads exactly n bytes; running out is Corrupt.
    Status readExact(uint8_t* out, size_t n);
    // Reads a header block. eof is set when the stream ends exactly on a block boundary.
    Status readBlock(uint8_t* block, bool& eof);
    // Skips n bytes; inflated TAR.GZ data counts against the ratio limit.
    Status skip(uint64_t n);
    // At the end of the archive: for TAR.GZ, inflates what is left so the
    // trailer of every gzip member is checked. Returns End or the error.
    Status endOfArchive();
    Status readMeta(uint64_t size, uint64_t cap, std::string& out);
    Status parsePax(const std::string& data, PaxValues& into);

    std::unique_ptr<Source> src_;
    bool gzip_;
    bool firstHeader_ = true;
    bool ended_ = false;
    bool haveEntry_ = false;
    bool extracted_ = false;
    EntryType curType_ = EntryType::File;
    uint64_t dataLeft_ = 0;
    uint64_t padLeft_ = 0;
    PaxValues globals_;
    std::vector<uint8_t> buf_;
};

Status TarReader::readExact(uint8_t* out, size_t n) {
    size_t got = 0;
    while (got < n) {
        const ssize_t r = src_->read(out + got, n - got);
        if (r < 0) return sourceFailed();
        if (r == 0) return fail(Status::Corrupt, "archive ends inside an entry", true);
        got += static_cast<size_t>(r);
    }
    return Status::Ok;
}

Status TarReader::readBlock(uint8_t* block, bool& eof) {
    eof = false;
    size_t got = 0;
    while (got < kBlock) {
        const ssize_t r = src_->read(block + got, kBlock - got);
        if (r < 0 && firstHeader_ && src_->status() == Status::Corrupt)
            return fail(Status::FormatError, "not a TAR.GZ archive: " + src_->message(), true);
        if (r < 0) return sourceFailed();
        if (r == 0) break;
        got += static_cast<size_t>(r);
    }
    if (got == 0) {
        eof = true;
        return Status::Ok;
    }
    if (got < kBlock) return fail(Status::Corrupt, "archive ends inside a header", true);
    return Status::Ok;
}

Status TarReader::skip(uint64_t n) {
    while (n > 0) {
        const uint64_t fast = src_->fastSkip(n);
        if (fast > 0) {
            n -= fast;
            continue;
        }
        const size_t step = static_cast<size_t>(std::min<uint64_t>(n, buf_.size()));
        if (Status s = readExact(buf_.data(), step); s != Status::Ok) return s;
        n -= step;
        if (gzip_) {
            if (Status s = checkRatio(src_->producedOut(), src_->compressedIn()); s != Status::Ok) return s;
        }
    }
    return Status::Ok;
}

Status TarReader::endOfArchive() {
    ended_ = true;
    if (!gzip_) return Status::End;
    // Only the member holding the end blocks matters; bytes after it may be
    // anything (further members, padding, junk that starts with 0x1F).
    src_->stopAtMemberEnd();
    uint64_t drained = 0;
    while (drained < kMaxTrailingBytes) {
        const ssize_t r = src_->read(buf_.data(), buf_.size());
        if (r < 0) return sourceFailed();
        if (r == 0) return Status::End;
        drained += static_cast<uint64_t>(r);
    }
    // More than any record padding inside the last member: its trailer is left unchecked.
    return Status::End;
}

Status TarReader::readMeta(uint64_t size, uint64_t cap, std::string& out) {
    if (size > cap) return fail(Status::LimitExceeded, "extended header is larger than the limit", true);
    out.resize(static_cast<size_t>(size));
    if (size > 0) {
        if (Status s = readExact(reinterpret_cast<uint8_t*>(out.data()), out.size()); s != Status::Ok) return s;
    }
    return skip((kBlock - size % kBlock) % kBlock);
}

Status TarReader::parsePax(const std::string& data, PaxValues& into) {
    size_t pos = 0;
    while (pos < data.size() && data[pos] != '\0') {
        const size_t space = data.find(' ', pos);
        uint64_t len = 0;
        if (space == std::string::npos || !parseDecimal(std::string_view(data).substr(pos, space - pos), len) ||
            len <= space - pos || len > data.size() - pos || data[pos + len - 1] != '\n')
            return fail(Status::Corrupt, "bad PAX record", true);
        const std::string_view record = std::string_view(data).substr(space + 1, pos + len - 1 - (space + 1));
        const size_t eq = record.find('=');
        if (eq == std::string_view::npos) return fail(Status::Corrupt, "bad PAX record", true);
        const std::string_view key = record.substr(0, eq);
        const std::string_view value = record.substr(eq + 1);
        if (key == "path") {
            into.path = std::string(value);
        } else if (key == "linkpath") {
            into.linkpath = std::string(value);
        } else if (key == "size") {
            uint64_t v = 0;
            if (!parseDecimal(value, v) || v > static_cast<uint64_t>(INT64_MAX))
                return fail(Status::Corrupt, "bad PAX size", true);
            into.size = v;
        } else if (key == "mtime") {
            int64_t ms = 0;
            if (!parsePaxTime(value, ms)) return fail(Status::Corrupt, "bad PAX mtime", true);
            into.mtimeMs = ms;
        } else if (key.substr(0, 11) == "GNU.sparse.") {
            into.sparse = true;
        }
        pos += static_cast<size_t>(len);
    }
    return Status::Ok;
}

Status TarReader::next(Entry& entry) {
    if (closed_ != Status::Ok) return closed_;
    if (ended_) return Status::End;
    if (dataLeft_ + padLeft_ > 0) {
        const uint64_t n = dataLeft_ + padLeft_;
        dataLeft_ = padLeft_ = 0;
        if (Status s = skip(n); s != Status::Ok) return s;
    }
    haveEntry_ = false;

    PaxValues pax = globals_;
    std::optional<std::string> longName;
    std::optional<std::string> longLink;
    bool metaPending = false;
    uint8_t h[kBlock];
    for (int meta = 0;; ++meta) {
        if (meta > kMaxMetaHeaders) return fail(Status::Corrupt, "too many extended headers", true);
        bool eof = false;
        if (Status s = readBlock(h, eof); s != Status::Ok) return s;
        const bool zero = !eof && std::all_of(h, h + kBlock, [](uint8_t b) { return b == 0; });
        if (eof || zero) {
            // Two zero blocks end an archive; one zero block or a clean end of file is accepted too.
            if (metaPending) return fail(Status::Corrupt, "extended header without an entry", true);
            return endOfArchive();
        }
        if (!tarChecksumOk(h))
            return fail(firstHeader_ ? Status::FormatError : Status::Corrupt,
                        firstHeader_ ? "not a TAR archive" : "bad TAR header checksum", true);
        firstHeader_ = false;
        const char type = static_cast<char>(h[156]);
        int64_t size = 0;
        if (!parseNumber(h + 124, 12, size) || size < 0) return fail(Status::Corrupt, "bad size field", true);
        if (type == 'x' || type == 'g') {
            std::string data;
            if (Status s = readMeta(static_cast<uint64_t>(size), kMaxPaxBytes, data); s != Status::Ok) return s;
            if (type == 'g') {
                if (Status s = parsePax(data, globals_); s != Status::Ok) return s;
                if (Status s = parsePax(data, pax); s != Status::Ok) return s;
            } else {
                if (Status s = parsePax(data, pax); s != Status::Ok) return s;
                metaPending = true;
            }
            continue;
        }
        if (type == 'L' || type == 'K') {
            std::string data;
            if (Status s = readMeta(static_cast<uint64_t>(size), kMaxLongNameBytes, data); s != Status::Ok) return s;
            data.resize(std::strlen(data.c_str()));
            (type == 'L' ? longName : longLink) = std::move(data);
            metaPending = true;
            continue;
        }
        break;
    }

    const char type = static_cast<char>(h[156]);
    std::string name;
    if (pax.path) {
        name = *pax.path;
    } else if (longName) {
        name = *longName;
    } else {
        const std::string_view base = field(h, 0, 100);
        const bool posix = std::memcmp(h + 257, "ustar\0", 6) == 0;
        const std::string_view prefix = posix ? field(h, 345, 155) : std::string_view();
        name = prefix.empty() ? std::string(base) : std::string(prefix) + "/" + std::string(base);
    }
    std::string link = pax.linkpath ? *pax.linkpath : longLink ? *longLink : std::string(field(h, 157, 100));

    int64_t headerSize = 0;
    parseNumber(h + 124, 12, headerSize);
    const uint64_t size = pax.size ? *pax.size : static_cast<uint64_t>(headerSize);
    int64_t mtime = 0;
    if (!pax.mtimeMs && !parseNumber(h + 136, 12, mtime)) return fail(Status::Corrupt, "bad mtime field", true);
    int64_t mode = 0;
    if (!parseNumber(h + 100, 8, mode)) mode = 0;

    EntryType kind = EntryType::Other;
    uint64_t data = size;
    switch (type) {
        case '0':
        case '\0':
        case '7':
            // Old tars mark folders with a trailing slash. Any data still gets
            // skipped, as GNU tar does: reading it as headers would show
            // entries that other tools do not see.
            kind = !name.empty() && name.back() == '/' ? EntryType::Dir : EntryType::File;
            break;
        case '5': kind = EntryType::Dir; data = 0; break;
        case '2': kind = EntryType::Symlink; data = 0; break;
        case '1': kind = EntryType::Hardlink; data = 0; break;
        case '3':
        case '4':
        case '6': data = 0; break;
        case 'S': {
            // Old GNU sparse: extension blocks with more of the map come before the data.
            bool extended = h[482] != 0;
            for (uint64_t n = 0; extended; ++n) {
                if (n >= kMaxSparseBlocks) return fail(Status::Corrupt, "sparse map too long", true);
                uint8_t ext[kBlock];
                if (Status s = readExact(ext, kBlock); s != Status::Ok) return s;
                extended = ext[504] != 0;
            }
            break;
        }
        default: break;  // GNU 'D', 'M', 'V' and unknown types: skip their data
    }
    if (pax.sparse) kind = EntryType::Other;
    if (kind != EntryType::Symlink && kind != EntryType::Hardlink) link.clear();

    if (Status s = admitEntry(data); s != Status::Ok) return s;

    entry.type = kind;
    entry.size = kind == EntryType::File ? data : 0;
    entry.compressed = gzip_ ? -1 : static_cast<int64_t>(data);
    entry.mtimeMs = pax.mtimeMs ? *pax.mtimeMs : (mtime > INT64_MAX / 1000 || mtime < INT64_MIN / 1000 ? 0 : mtime * 1000);
    entry.mode = static_cast<uint32_t>(mode & 07777);
    entry.encrypted = false;
    entry.verdict = checkPath(name, false, &entry.path);
    entry.raw = std::move(name);
    entry.link = std::move(link);
    if (Status s = applyLinkRules(entry); s != Status::Ok) return s;

    curType_ = kind;
    dataLeft_ = data;
    padLeft_ = (kBlock - data % kBlock) % kBlock;
    haveEntry_ = true;
    extracted_ = false;
    return Status::Ok;
}

Status TarReader::extract(int outFd, const Progress& progress) {
    if (closed_ != Status::Ok) return closed_;
    if (!haveEntry_) return fail(Status::IoError, "no current entry", false);
    if (extracted_) return fail(Status::IoError, "entry data was already read", false);
    if (curType_ == EntryType::Other) return fail(Status::Unsupported, "special files cannot be extracted", false);
    ProgressTicker ticker(progress);
    extracted_ = true;
    if (curType_ == EntryType::File) {
        std::string writeMessage;
        uint64_t entryOut = 0;
        while (dataLeft_ > 0) {
            const size_t n = static_cast<size_t>(std::min<uint64_t>(dataLeft_, buf_.size()));
            if (Status s = readExact(buf_.data(), n); s != Status::Ok) return s;
            dataLeft_ -= n;
            entryOut += n;
            // Plain TAR has nothing to compare; gzip compares the whole stream so far.
            const uint64_t ratioOut = gzip_ ? src_->producedOut() : entryOut;
            const uint64_t ratioIn = gzip_ ? src_->compressedIn() : entryOut;
            if (Status s = accountOutput(n, entryOut, 0, ratioOut, ratioIn); s != Status::Ok) return s;
            if (outFd >= 0) {
                if (Status w = writeAll(outFd, buf_.data(), n, writeMessage); w != Status::Ok)
                    return fail(w, writeMessage, false);
            }
            if (!ticker.add(n)) return fail(Status::Cancelled, "cancelled", false);
        }
        const uint64_t pad = padLeft_;
        padLeft_ = 0;
        if (Status s = skip(pad); s != Status::Ok) return s;
    }
    if (!ticker.finish()) return fail(Status::Cancelled, "cancelled", false);
    return Status::Ok;
}

void putOctal(uint8_t* f, size_t len, uint64_t v) {
    // len - 1 digits and a NUL.
    f[len - 1] = 0;
    for (size_t i = len - 1; i-- > 0;) {
        f[i] = static_cast<uint8_t>('0' + (v & 7));
        v >>= 3;
    }
}

void putBase256(uint8_t* f, size_t len, int64_t v) {
    auto u = static_cast<uint64_t>(v);
    for (size_t i = len; i-- > 1;) {
        f[i] = static_cast<uint8_t>(u & 0xFF);
        u = v < 0 ? (u >> 8) | (0xFFull << 56) : u >> 8;
    }
    f[0] = v < 0 ? 0xFF : 0x80;
}

void putNumber(uint8_t* f, size_t len, int64_t v) {
    if (v >= 0 && static_cast<uint64_t>(v) < kOctal11Limit)
        putOctal(f, len, static_cast<uint64_t>(v));
    else
        putBase256(f, len, v);
}

void putText(uint8_t* f, size_t len, std::string_view s) {
    if (!s.empty()) std::memcpy(f, s.data(), std::min(len, s.size()));
}

// The ustar name for a path that only PAX can hold, for readers without PAX
// support: ASCII, at most 100 bytes, cut at a "/" so it is whole components
// of a checked path and can never start with "../". A last component over 100
// bytes keeps its first 100. A folder keeps its trailing "/".
std::string fallbackName(std::string_view name) {
    std::string ascii(name);
    for (char& c : ascii)
        if (static_cast<uint8_t>(c) >= 0x80) c = '_';
    const bool dir = !ascii.empty() && ascii.back() == '/';
    std::string_view body(ascii);
    if (dir) body.remove_suffix(1);
    const size_t room = dir ? 99 : 100;
    if (body.size() <= room) return ascii;
    size_t cut = body.find('/', body.size() - room - 1);
    std::string out;
    if (cut != std::string_view::npos) {
        out = std::string(body.substr(cut + 1));
    } else {
        const size_t last = body.rfind('/');
        out = std::string(body.substr(last == std::string_view::npos ? 0 : last + 1).substr(0, room));
    }
    if (dir) out.push_back('/');
    return out;
}

void addPaxRecord(std::string& out, std::string_view key, std::string_view value) {
    const size_t body = key.size() + value.size() + 3;  // space, '=', newline
    size_t len = body + 1;
    while (std::to_string(len).size() + body != len) len = std::to_string(len).size() + body;
    out += std::to_string(len);
    out += ' ';
    out += key;
    out += '=';
    out += value;
    out += '\n';
}

class TarWriter final : public Writer {
public:
    TarWriter(int fd, bool gzip, int level) : Writer(fd), gzip_(gzip), level_(level), sink_(fd), in_(kInBufferSize) {
        if (gzip) out_.resize(kInBufferSize);
    }
    ~TarWriter() override {
        if (zInit_) deflateEnd(&z_);
    }

    Status addDirectory(std::string_view path, int64_t mtimeMs, uint32_t mode) override;
    Status addFile(std::string_view path, int inFd, uint64_t size, int64_t mtimeMs, uint32_t mode,
                   const Progress& progress) override;
    Status finish() override;

private:
    Status ready();
    Status emit(const uint8_t* data, size_t n);
    Status pad();
    Status header(std::string_view path, bool dir, char type, uint32_t mode, uint64_t size, int64_t mtimeMs);
    Status deflateOut(int flush);

    bool gzip_;
    int level_;
    FdSink sink_;
    z_stream z_{};
    bool zInit_ = false;
    uint64_t tarBytes_ = 0;
    bool finished_ = false;
    std::vector<uint8_t> in_;
    std::vector<uint8_t> out_;
};

Status TarWriter::ready() {
    if (broken_ != Status::Ok) return broken_;
    if (finished_) return fail(Status::IoError, "archive already finished", false);
    if (gzip_ && !zInit_) {
        if (deflateInit2(&z_, level_, Z_DEFLATED, 16 + MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK)
            return fail(Status::IoError, "deflate init failed");
        zInit_ = true;
    }
    return Status::Ok;
}

Status TarWriter::deflateOut(int flush) {
    do {
        z_.next_out = out_.data();
        z_.avail_out = static_cast<uInt>(out_.size());
        const int rc = ::deflate(&z_, flush);
        if (rc == Z_STREAM_ERROR) return fail(Status::IoError, "deflate failed");
        if (Status s = sink_.write(out_.data(), out_.size() - z_.avail_out); s != Status::Ok) return fail(s, sink_.message());
        if (rc == Z_STREAM_END) break;
    } while (z_.avail_out == 0 || flush == Z_FINISH);
    return Status::Ok;
}

Status TarWriter::emit(const uint8_t* data, size_t n) {
    tarBytes_ += n;
    if (!gzip_) {
        if (Status s = sink_.write(data, n); s != Status::Ok) return fail(s, sink_.message());
        return Status::Ok;
    }
    z_.next_in = data;
    z_.avail_in = static_cast<uInt>(n);
    return deflateOut(Z_NO_FLUSH);
}

Status TarWriter::pad() {
    static const uint8_t zeros[kBlock] = {};
    const size_t n = static_cast<size_t>((kBlock - tarBytes_ % kBlock) % kBlock);
    return n ? emit(zeros, n) : Status::Ok;
}

Status TarWriter::header(std::string_view path, bool dir, char type, uint32_t mode, uint64_t size, int64_t mtimeMs) {
    std::string clean;
    if (checkPath(path, false, &clean) != PathVerdict::Ok)
        return fail(Status::IoError, "invalid entry path: " + std::string(path), false);
    const std::string name = dir ? clean + "/" : clean;
    const int64_t mtime = floorDiv(mtimeMs, 1000);
    const bool ascii = std::all_of(name.begin(), name.end(), [](char c) { return static_cast<uint8_t>(c) < 0x80; });

    // ustar splits long names at a slash into prefix (155) and name (100).
    std::string_view nameField = name;
    std::string_view prefix;
    bool fits = name.size() <= 100;
    for (size_t slash = name.find('/'); !fits && slash != std::string::npos && slash <= 155;
         slash = name.find('/', slash + 1)) {
        const size_t rest = name.size() - slash - 1;
        if (rest > 0 && rest <= 100) {
            prefix = std::string_view(name).substr(0, slash);
            nameField = std::string_view(name).substr(slash + 1);
            fits = true;
        }
    }
    std::string records;
    std::string safeName;
    if (!ascii || !fits) {
        addPaxRecord(records, "path", name);
        safeName = fallbackName(name);
        nameField = safeName;
        prefix = {};
    }
    if (size >= kOctal11Limit) addPaxRecord(records, "size", std::to_string(size));
    if (mtime < 0 || static_cast<uint64_t>(mtime) >= kOctal11Limit) addPaxRecord(records, "mtime", std::to_string(mtime));

    auto build = [&](uint8_t* h, std::string_view n, std::string_view pre, char t, uint32_t m, uint64_t sz, int64_t mt) {
        std::memset(h, 0, kBlock);
        putText(h, 100, n);
        putOctal(h + 100, 8, m);
        putOctal(h + 108, 8, 0);
        putOctal(h + 116, 8, 0);
        putNumber(h + 124, 12, static_cast<int64_t>(std::min<uint64_t>(sz, INT64_MAX)));
        putNumber(h + 136, 12, mt);
        h[156] = static_cast<uint8_t>(t);
        std::memcpy(h + 257, "ustar\0" "00", 8);
        putOctal(h + 329, 8, 0);
        putOctal(h + 337, 8, 0);
        putText(h + 345, 155, pre);
        std::memset(h + 148, ' ', 8);
        uint32_t sum = 0;
        for (size_t i = 0; i < kBlock; ++i) sum += h[i];
        putOctal(h + 148, 7, sum);
        h[155] = ' ';
    };

    uint8_t h[kBlock];
    if (!records.empty()) {
        const int64_t paxTime = mtime >= 0 && static_cast<uint64_t>(mtime) < kOctal11Limit ? mtime : 0;
        build(h, "././@PaxHeader", "", 'x', 0644, records.size(), paxTime);
        if (Status s = emit(h, kBlock); s != Status::Ok) return s;
        if (Status s = emit(reinterpret_cast<const uint8_t*>(records.data()), records.size()); s != Status::Ok) return s;
        if (Status s = pad(); s != Status::Ok) return s;
    }
    const uint32_t perms = (mode & 07777) != 0 ? (mode & 07777) : (dir ? 0755 : 0644);
    build(h, nameField, prefix, type, perms, size, mtime);
    return emit(h, kBlock);
}

Status TarWriter::addDirectory(std::string_view path, int64_t mtimeMs, uint32_t mode) {
    if (Status s = ready(); s != Status::Ok) return s;
    return header(path, true, '5', mode, 0, mtimeMs);
}

Status TarWriter::addFile(std::string_view path, int inFd, uint64_t size, int64_t mtimeMs, uint32_t mode,
                          const Progress& progress) {
    if (Status s = ready(); s != Status::Ok) return s;
    if (inFd < 0) return fail(Status::IoError, "invalid source descriptor", false);
    if (Status s = header(path, false, '0', mode, size, mtimeMs); s != Status::Ok) return s;
    ProgressTicker ticker(progress);
    uint64_t done = 0;
    while (done < size) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(in_.size(), size - done));
        const ssize_t got = readFull(inFd, in_.data(), want);
        if (got < 0) return fail(Status::IoError, std::string("reading the source failed: ") + std::strerror(errno));
        if (static_cast<size_t>(got) < want) return fail(Status::IoError, "source is shorter than its size");
        if (Status s = emit(in_.data(), want); s != Status::Ok) return s;
        done += want;
        if (!ticker.add(want)) return fail(Status::Cancelled, "cancelled");
    }
    uint8_t probe = 0;
    const ssize_t extra = readFull(inFd, &probe, 1);
    if (extra != 0) return fail(Status::IoError, extra < 0 ? "reading the source failed" : "source is longer than its size");
    if (Status s = pad(); s != Status::Ok) return s;
    if (!ticker.finish()) return fail(Status::Cancelled, "cancelled");
    return Status::Ok;
}

Status TarWriter::finish() {
    if (Status s = ready(); s != Status::Ok) return s;
    finished_ = true;
    static const uint8_t zeros[kBlock] = {};
    for (int i = 0; i < 2; ++i) {
        if (Status s = emit(zeros, kBlock); s != Status::Ok) return s;
    }
    while (tarBytes_ % kRecord != 0) {
        if (Status s = emit(zeros, kBlock); s != Status::Ok) return s;
    }
    if (gzip_) {
        z_.next_in = nullptr;
        z_.avail_in = 0;
        if (Status s = deflateOut(Z_FINISH); s != Status::Ok) return s;
    }
    if (Status s = sink_.flush(); s != Status::Ok) return fail(s, sink_.message());
    return closeFd();
}

}  // namespace

std::unique_ptr<Reader> openTarReader(int fd, bool gzip, const Limits& limits, Status* status, std::string*) {
    *status = Status::Ok;
    return std::make_unique<TarReader>(fd, gzip, limits);
}

std::unique_ptr<Writer> openTarWriter(int fd, bool gzip, int level) {
    return std::make_unique<TarWriter>(fd, gzip, level);
}

}  // namespace lx::archive::detail
