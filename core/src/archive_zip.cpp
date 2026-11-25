// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// ZIP reading through the central directory with pread, and streaming ZIP
// writing with data descriptors. APPNOTE.TXT 6.3 is the reference.
#define ZLIB_CONST
#include <zlib.h>

#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <iterator>
#include <map>
#include <vector>

#include "archive_internal.hpp"

namespace lx::archive::detail {

namespace {

constexpr uint32_t kLocalSig = 0x04034b50;
constexpr uint32_t kCentralSig = 0x02014b50;
constexpr uint32_t kEndSig = 0x06054b50;
constexpr uint32_t kEnd64Sig = 0x06064b50;
constexpr uint32_t kLocator64Sig = 0x07064b50;
constexpr uint32_t kDescriptorSig = 0x08074b50;
constexpr size_t kLocalSize = 30;
constexpr size_t kCentralSize = 46;
constexpr size_t kEndSize = 22;
constexpr size_t kEnd64Size = 56;
constexpr size_t kLocatorSize = 20;
constexpr uint64_t kMaxEndSearch = kEndSize + 0xFFFF;  // EOCD plus the longest comment
constexpr uint16_t kFlagEncrypted = 1;
constexpr uint16_t kFlagDescriptor = 1 << 3;
constexpr uint16_t kFlagUtf8 = 1 << 11;
constexpr uint16_t kExtraZip64 = 0x0001;
constexpr uint16_t kExtraTimestamp = 0x5455;
constexpr uint16_t kHostUnix = 3;
constexpr uint32_t kUnixTypeMask = 0170000;
constexpr uint32_t kUnixDir = 0040000;
constexpr uint32_t kUnixFile = 0100000;
constexpr uint32_t kUnixLink = 0120000;

class ZipReader final : public Reader {
public:
    ZipReader(int fd, const Limits& limits) : Reader(fd, limits), in_(kInBufferSize), out_(kOutBufferSize) {}

    Status init();
    Status next(Entry& entry) override;
    Status extract(int outFd, const Progress& progress) override;

private:
    struct Current {
        bool valid = false;
        EntryType type = EntryType::File;
        bool encrypted = false;
        uint16_t method = 0;
        uint32_t crc = 0;
        uint64_t csize = 0;
        uint64_t usize = 0;
        uint64_t dataOffset = 0;
    };

    Status readAt(uint64_t offset, uint8_t* buf, size_t n);
    Status claimRange(uint64_t start, uint64_t end);
    // Streams the current entry's data, at most maxOut bytes, into sink.
    template <typename Sink>
    Status streamData(uint64_t maxOut, Sink&& sink, uint32_t& crc, uint64_t& produced);

    uint64_t fileSize_ = 0;
    uint64_t cdStart_ = 0;
    uint64_t cdPos_ = 0;
    uint64_t cdEnd_ = 0;
    uint64_t entryCount_ = 0;
    uint64_t entryIndex_ = 0;
    // [local header, data end) of every entry seen, keyed by start, never
    // overlapping; touching ranges are merged. A map keeps each claim O(log n)
    // whatever order the central directory lists the entries in.
    std::map<uint64_t, uint64_t> ranges_;
    // Output of, and compressed bytes behind, the extractions so far, for the archive-wide ratio.
    uint64_t extractedOut_ = 0;
    uint64_t extractedIn_ = 0;
    Current cur_;
    std::vector<uint8_t> in_;
    std::vector<uint8_t> out_;
    std::vector<uint8_t> record_;
};

Status ZipReader::readAt(uint64_t offset, uint8_t* buf, size_t n) {
    const ssize_t got = preadFull(fd_, buf, n, offset);
    if (got < 0) return fail(Status::IoError, std::string("read failed: ") + std::strerror(errno), true);
    if (static_cast<size_t>(got) < n) return fail(Status::Corrupt, "archive ends early", true);
    return Status::Ok;
}

Status ZipReader::init() {
    struct stat st {};
    if (::fstat(fd_, &st) != 0) return fail(Status::IoError, std::string("fstat failed: ") + std::strerror(errno), true);
    if (S_ISFIFO(st.st_mode) || S_ISSOCK(st.st_mode))
        return fail(Status::Unsupported, "ZIP needs a file that can seek", true);
    // fd_ may share its offset with the caller's fd, so the size comes from
    // fstat; other seekable kinds get their offset put back.
    off_t end = -1;
    if (S_ISREG(st.st_mode)) {
        end = st.st_size;
    } else if (const off_t here = ::lseek(fd_, 0, SEEK_CUR); here >= 0) {
        end = ::lseek(fd_, 0, SEEK_END);
        ::lseek(fd_, here, SEEK_SET);
    }
    if (end < 0) return fail(Status::Unsupported, "ZIP needs a file that can seek", true);
    fileSize_ = static_cast<uint64_t>(end);
    if (fileSize_ < kEndSize) return fail(Status::FormatError, "not a ZIP archive", true);

    const uint64_t tailLen = std::min<uint64_t>(fileSize_, kMaxEndSearch);
    const uint64_t tailStart = fileSize_ - tailLen;
    std::vector<uint8_t> tail(static_cast<size_t>(tailLen));
    if (Status s = readAt(tailStart, tail.data(), tail.size()); s != Status::Ok) return s;

    // Search backwards. A record whose comment reaches exactly to the end of the
    // file wins; otherwise the last record that fits.
    size_t found = SIZE_MAX;
    for (size_t i = tail.size() - kEndSize + 1; i-- > 0;) {
        if (le32(&tail[i]) != kEndSig) continue;
        const size_t commentLen = le16(&tail[i + 20]);
        if (i + kEndSize + commentLen > tail.size()) continue;
        if (found == SIZE_MAX) found = i;
        if (i + kEndSize + commentLen == tail.size()) {
            found = i;
            break;
        }
    }
    if (found == SIZE_MAX) return fail(Status::FormatError, "no ZIP end record", true);
    const uint8_t* e = &tail[found];
    const uint64_t endPos = tailStart + found;
    uint32_t disk = le16(e + 4);
    uint32_t cdDisk = le16(e + 6);
    uint64_t diskEntries = le16(e + 8);
    uint64_t entries = le16(e + 10);
    uint64_t cdSize = le32(e + 12);
    uint64_t cdOffset = le32(e + 16);
    uint64_t cdLimit = endPos;

    if (endPos >= kLocatorSize) {
        uint8_t loc[kLocatorSize];
        if (Status s = readAt(endPos - kLocatorSize, loc, sizeof loc); s != Status::Ok) return s;
        if (le32(loc) == kLocator64Sig) {
            const uint64_t end64 = le64(loc + 8);
            if (end64 > endPos - kLocatorSize || endPos - kLocatorSize - end64 < kEnd64Size)
                return fail(Status::Corrupt, "ZIP64 end record outside the archive", true);
            uint8_t r[kEnd64Size];
            if (Status s = readAt(end64, r, sizeof r); s != Status::Ok) return s;
            if (le32(r) != kEnd64Sig) return fail(Status::Corrupt, "bad ZIP64 end record", true);
            disk = le32(r + 16);
            cdDisk = le32(r + 20);
            diskEntries = le64(r + 24);
            entries = le64(r + 32);
            cdSize = le64(r + 40);
            cdOffset = le64(r + 48);
            cdLimit = end64;
        }
    }
    if (disk != 0 || cdDisk != 0 || diskEntries != entries)
        return fail(Status::Unsupported, "split ZIP archives are not supported", true);
    if (cdOffset > cdLimit || cdSize > cdLimit - cdOffset)
        return fail(Status::Corrupt, "central directory outside the archive", true);
    if (entries > cdSize / kCentralSize) return fail(Status::Corrupt, "central directory too small for its entries", true);
    cdStart_ = cdOffset;
    cdPos_ = cdOffset;
    cdEnd_ = cdOffset + cdSize;
    entryCount_ = entries;
    return Status::Ok;
}

Status ZipReader::claimRange(uint64_t start, uint64_t end) {
    auto next = ranges_.lower_bound(start);
    const bool hitsNext = next != ranges_.end() && next->first < end;
    const bool hitsPrev = next != ranges_.begin() && std::prev(next)->second > start;
    if (hitsNext || hitsPrev) return fail(Status::Corrupt, "entries overlap", true);
    // Merge with touching neighbours, so entries without gaps between them
    // stay one range in either order.
    if (next != ranges_.end() && next->first == end) {
        end = next->second;
        next = ranges_.erase(next);
    }
    if (next != ranges_.begin() && std::prev(next)->second == start) {
        std::prev(next)->second = end;
        return Status::Ok;
    }
    ranges_.emplace_hint(next, start, end);
    return Status::Ok;
}

Status ZipReader::next(Entry& entry) {
    if (closed_ != Status::Ok) return closed_;
    cur_.valid = false;
    if (entryIndex_ >= entryCount_) return Status::End;
    if (cdEnd_ - cdPos_ < kCentralSize) return fail(Status::Corrupt, "central directory ends early", true);
    uint8_t h[kCentralSize];
    if (Status s = readAt(cdPos_, h, sizeof h); s != Status::Ok) return s;
    if (le32(h) != kCentralSig) return fail(Status::Corrupt, "bad central directory record", true);
    const uint16_t madeBy = le16(h + 4);
    const uint16_t flags = le16(h + 8);
    const uint16_t method = le16(h + 10);
    const uint16_t dosTime = le16(h + 12);
    const uint16_t dosDate = le16(h + 14);
    const uint32_t crc = le32(h + 16);
    uint64_t csize = le32(h + 20);
    uint64_t usize = le32(h + 24);
    const size_t nameLen = le16(h + 28);
    const size_t extraLen = le16(h + 30);
    const size_t commentLen = le16(h + 32);
    const uint32_t external = le32(h + 38);
    uint64_t localOffset = le32(h + 42);
    if (cdEnd_ - cdPos_ - kCentralSize < nameLen + extraLen + commentLen)
        return fail(Status::Corrupt, "central directory record runs past its end", true);
    record_.resize(nameLen + extraLen);
    if (!record_.empty()) {
        if (Status s = readAt(cdPos_ + kCentralSize, record_.data(), record_.size()); s != Status::Ok) return s;
    }
    cdPos_ += kCentralSize + nameLen + extraLen + commentLen;
    ++entryIndex_;

    std::string name(reinterpret_cast<const char*>(record_.data()), nameLen);
    const uint8_t* extra = record_.data() + nameLen;
    bool haveUnixTime = false;
    int64_t unixTime = 0;
    for (size_t pos = 0; pos + 4 <= extraLen;) {
        const uint16_t id = le16(extra + pos);
        const size_t len = le16(extra + pos + 2);
        const uint8_t* d = extra + pos + 4;
        if (pos + 4 + len > extraLen) break;  // trailing padding some writers leave
        if (id == kExtraZip64) {
            size_t k = 0;
            auto take = [&](uint64_t& field) {
                if (field != 0xFFFFFFFFu) return true;
                if (k + 8 > len) return false;
                field = le64(d + k);
                k += 8;
                return true;
            };
            if (!take(usize) || !take(csize) || !take(localOffset))
                return fail(Status::Corrupt, "short ZIP64 extra field", true);
        } else if (id == kExtraTimestamp && len >= 5 && (d[0] & 1)) {
            haveUnixTime = true;
            unixTime = static_cast<int32_t>(le32(d + 1));
        }
        pos += 4 + len;
    }

    // A trailing slash always marks a folder. Otherwise the Unix file type
    // decides when the archive was made on Unix, and the MS-DOS folder bit when not.
    const bool slashDir = !name.empty() && (name.back() == '/' || name.back() == '\\');
    const bool dosDir = (external & 0x10) != 0;
    EntryType type = slashDir || dosDir ? EntryType::Dir : EntryType::File;
    uint32_t mode = 0;
    const uint32_t unixMode = external >> 16;
    if ((madeBy >> 8) == kHostUnix && unixMode != 0) {
        mode = unixMode & 07777;
        const uint32_t kind = unixMode & kUnixTypeMask;
        if (slashDir || kind == kUnixDir)
            type = EntryType::Dir;
        else if (kind == kUnixLink)
            type = EntryType::Symlink;
        else if (kind == kUnixFile)
            type = EntryType::File;
        else if (kind != 0)
            type = EntryType::Other;
    }
    const bool encrypted = (flags & kFlagEncrypted) != 0;
    // Sizes leave as signed 64-bit numbers (Kotlin Long).
    if (usize > static_cast<uint64_t>(INT64_MAX) || csize > static_cast<uint64_t>(INT64_MAX))
        return fail(Status::Corrupt, "entry size out of range", true);

    if (Status s = admitEntry(type == EntryType::Dir ? 0 : usize); s != Status::Ok) return s;

    // The local header gives the data offset; its own sizes are only checked.
    // Local headers and data must lie before the central directory.
    if (localOffset > cdStart_ || cdStart_ - localOffset < kLocalSize)
        return fail(Status::Corrupt, "entry outside the archive", true);
    uint8_t l[kLocalSize];
    if (Status s = readAt(localOffset, l, sizeof l); s != Status::Ok) return s;
    if (le32(l) != kLocalSig) return fail(Status::Corrupt, "bad local header", true);
    const uint16_t localFlags = le16(l + 6);
    if (!(localFlags & kFlagDescriptor)) {
        const uint32_t lc = le32(l + 18);
        const uint32_t lu = le32(l + 22);
        const bool csizeDiffers = lc != 0 && lc != 0xFFFFFFFFu && csize < 0xFFFFFFFFu && lc != csize;
        const bool usizeDiffers = lu != 0 && lu != 0xFFFFFFFFu && usize < 0xFFFFFFFFu && lu != usize;
        if (csizeDiffers || usizeDiffers) return fail(Status::Corrupt, "local and central sizes differ", true);
    }
    const uint64_t dataOffset = localOffset + kLocalSize + le16(l + 26) + le16(l + 28);
    if (dataOffset > cdStart_ || csize > cdStart_ - dataOffset)
        return fail(Status::Corrupt, "entry data outside the archive", true);
    if (Status s = claimRange(localOffset, dataOffset + csize); s != Status::Ok) return s;

    cur_ = Current{true, type, encrypted, method, crc, csize, usize, dataOffset};

    std::string link;
    if (type == EntryType::Symlink) {
        if (usize > kMaxLinkBytes || (method != 0 && method != 8)) {
            type = EntryType::Other;
            cur_.type = type;
        } else if (!encrypted) {
            uint32_t linkCrc = 0;
            uint64_t produced = 0;
            Status s = streamData(
                usize,
                [&](const uint8_t* p, size_t n) {
                    link.append(reinterpret_cast<const char*>(p), n);
                    return Status::Ok;
                },
                linkCrc, produced);
            if (s != Status::Ok) return s;
            if (linkCrc != crc) return fail(Status::CrcError, "CRC mismatch in link target", false);
        }
    }

    entry.type = type;
    entry.size = type == EntryType::Dir || type == EntryType::Other ? 0 : usize;
    entry.compressed = static_cast<int64_t>(csize);
    entry.mtimeMs = haveUnixTime ? unixTime * 1000 : dosToUnixMs(dosTime, dosDate);
    entry.mode = mode;
    entry.encrypted = encrypted;
    entry.raw = (flags & kFlagUtf8) || isValidUtf8(name) ? std::move(name) : cp437ToUtf8(name);
    entry.verdict = checkPath(entry.raw, false, &entry.path);
    entry.link = std::move(link);
    return applyLinkRules(entry);
}

template <typename Sink>
Status ZipReader::streamData(uint64_t maxOut, Sink&& sink, uint32_t& crc, uint64_t& produced) {
    crc = static_cast<uint32_t>(::crc32(0, nullptr, 0));
    produced = 0;
    uint64_t consumed = 0;
    if (cur_.method == 0) {
        if (cur_.csize != cur_.usize) return fail(Status::Corrupt, "stored entry sizes differ", false);
        while (consumed < cur_.csize) {
            const size_t n = static_cast<size_t>(std::min<uint64_t>(in_.size(), cur_.csize - consumed));
            if (Status s = readAt(cur_.dataOffset + consumed, in_.data(), n); s != Status::Ok) return s;
            consumed += n;
            produced += n;
            if (produced > maxOut) return fail(Status::Corrupt, "entry is larger than declared", false);
            crc = static_cast<uint32_t>(::crc32(crc, in_.data(), static_cast<uInt>(n)));
            if (Status s = sink(in_.data(), n); s != Status::Ok) return s;
        }
        return Status::Ok;
    }
    z_stream z{};
    if (inflateInit2(&z, -MAX_WBITS) != Z_OK) return fail(Status::IoError, "inflate init failed", false);
    struct Guard {
        z_stream* z;
        ~Guard() { inflateEnd(z); }
    } guard{&z};
    for (;;) {
        if (z.avail_in == 0 && consumed < cur_.csize) {
            const size_t n = static_cast<size_t>(std::min<uint64_t>(in_.size(), cur_.csize - consumed));
            if (Status s = readAt(cur_.dataOffset + consumed, in_.data(), n); s != Status::Ok) return s;
            consumed += n;
            z.next_in = in_.data();
            z.avail_in = static_cast<uInt>(n);
        }
        z.next_out = out_.data();
        z.avail_out = static_cast<uInt>(out_.size());
        const int rc = ::inflate(&z, Z_NO_FLUSH);
        const size_t got = out_.size() - z.avail_out;
        if (rc != Z_OK && rc != Z_STREAM_END && !(rc == Z_BUF_ERROR && got == 0 && consumed < cur_.csize))
            return fail(Status::Corrupt, rc == Z_BUF_ERROR ? "compressed data ends early" : "compressed data is damaged",
                        false);
        if (got > 0) {
            produced += got;
            if (produced > maxOut) return fail(Status::Corrupt, "entry is larger than declared", false);
            crc = static_cast<uint32_t>(::crc32(crc, out_.data(), static_cast<uInt>(got)));
            if (Status s = sink(out_.data(), got); s != Status::Ok) return s;
        }
        if (rc == Z_STREAM_END) return Status::Ok;
        if (got == 0 && z.avail_in == 0 && consumed >= cur_.csize)
            return fail(Status::Corrupt, "compressed data ends early", false);
    }
}

Status ZipReader::extract(int outFd, const Progress& progress) {
    if (closed_ != Status::Ok) return closed_;
    if (!cur_.valid) return fail(Status::IoError, "no current entry", false);
    ProgressTicker ticker(progress);
    if (cur_.type == EntryType::Dir) return ticker.finish() ? Status::Ok : fail(Status::Cancelled, "cancelled", false);
    if (cur_.type == EntryType::Other) return fail(Status::Unsupported, "special files cannot be extracted", false);
    if (cur_.encrypted) return fail(Status::Unsupported, "encrypted entries are not supported", false);
    if (cur_.method != 0 && cur_.method != 8)
        return fail(Status::Unsupported, "compression method " + std::to_string(cur_.method) + " is not supported", false);

    std::string writeMessage;
    uint32_t crc = 0;
    uint64_t produced = 0;
    uint64_t entryOut = 0;
    // Ratios compare against the whole compressed entry, so a file that starts
    // with a long run of zeros is not stopped early.
    const auto entryIn = [&] { return cur_.method == 0 ? entryOut : cur_.csize; };
    const uint64_t inBefore = extractedIn_;
    const uint64_t outBefore = extractedOut_;
    Status s = streamData(
        cur_.usize,
        [&](const uint8_t* p, size_t n) {
            entryOut += n;
            if (Status a = accountOutput(n, entryOut, entryIn(), outBefore + entryOut, inBefore + entryIn());
                a != Status::Ok)
                return a;
            if (outFd >= 0) {
                if (Status w = writeAll(outFd, p, n, writeMessage); w != Status::Ok) return fail(w, writeMessage, false);
            }
            if (!ticker.add(n)) return fail(Status::Cancelled, "cancelled", false);
            return Status::Ok;
        },
        crc, produced);
    extractedIn_ = inBefore + entryIn();
    extractedOut_ = outBefore + entryOut;
    if (s != Status::Ok) return s;
    if (produced != cur_.usize) return fail(Status::Corrupt, "entry is shorter than declared", false);
    if (crc != cur_.crc) return fail(Status::CrcError, "CRC mismatch", false);
    if (!ticker.finish()) return fail(Status::Cancelled, "cancelled", false);
    return Status::Ok;
}

class ZipWriter final : public Writer {
public:
    ZipWriter(int fd, const WriterOptions& options)
        : Writer(fd), options_(options), sink_(fd), in_(kInBufferSize), out_(kInBufferSize) {}

    Status addDirectory(std::string_view path, int64_t mtimeMs, uint32_t mode) override;
    Status addFile(std::string_view path, int inFd, uint64_t size, int64_t mtimeMs, uint32_t mode,
                   const Progress& progress) override;
    Status finish() override;

private:
    struct Record {
        std::string name;
        uint16_t flags = 0;
        uint16_t method = 0;
        uint16_t dosTime = 0;
        uint16_t dosDate = 0;
        uint32_t crc = 0;
        uint64_t csize = 0;
        uint64_t usize = 0;
        uint64_t offset = 0;
        uint32_t external = 0;
        bool zip64 = false;
        bool haveTime = false;
        int64_t mtime = 0;
    };

    Status ready();
    // Checks the path and fills in the name, offset, times and attributes.
    Status prepare(std::string_view path, bool dir, int64_t mtimeMs, uint32_t mode, Record& r);
    // Without a data descriptor the local header carries the CRC and sizes.
    Status writeLocal(const Record& r);
    Status sinkFailed(Status s) { return fail(s, sink_.message()); }
    Status drain(z_stream& z, int flush, uint64_t& csize);
    Status addDeflated(Record& r, int inFd, uint64_t size, ProgressTicker& ticker);
    Status addStored(Record& r, int inFd, uint64_t size, uint64_t start, const Progress& progress,
                     ProgressTicker& ticker);
    // Fails when inFd has more than the size it was added with.
    Status checkSourceEnd(int inFd);

    WriterOptions options_;
    FdSink sink_;
    std::vector<Record> records_;
    std::vector<uint8_t> in_;
    std::vector<uint8_t> out_;
    bool finished_ = false;
};

void putTimestampExtra(std::string& out, bool have, int64_t mtime) {
    if (!have) return;
    put16(out, kExtraTimestamp);
    put16(out, 5);
    out.push_back(1);  // only the modification time follows
    put32(out, static_cast<uint32_t>(static_cast<int32_t>(mtime)));
}

// Where to pread a source from, or -1 when it cannot be read twice.
off_t preadStart(int fd) {
    struct stat st {};
    if (::fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) return -1;
    return ::lseek(fd, 0, SEEK_CUR);
}

Status ZipWriter::ready() {
    if (broken_ != Status::Ok) return broken_;
    if (finished_) return fail(Status::IoError, "archive already finished", false);
    return Status::Ok;
}

Status ZipWriter::prepare(std::string_view path, bool dir, int64_t mtimeMs, uint32_t mode, Record& r) {
    std::string clean;
    if (checkPath(path, false, &clean) != PathVerdict::Ok)
        return fail(Status::IoError, "invalid entry path: " + std::string(path), false);
    r.name = dir ? clean + "/" : clean;
    r.offset = sink_.offset();
    r.flags = kFlagUtf8;
    unixMsToDos(mtimeMs, r.dosTime, r.dosDate);
    const int64_t seconds = floorDiv(mtimeMs, 1000);
    r.haveTime = seconds >= INT32_MIN && seconds <= INT32_MAX;
    r.mtime = seconds;
    const uint32_t perms = (mode & 07777) != 0 ? (mode & 07777) : (dir ? 0755 : 0644);
    r.external = ((dir ? kUnixDir : kUnixFile) | perms) << 16;
    if (dir) r.external |= 0x10;
    return Status::Ok;
}

Status ZipWriter::writeLocal(const Record& r) {
    const bool descriptor = (r.flags & kFlagDescriptor) != 0;
    std::string h;
    put32(h, kLocalSig);
    put16(h, r.zip64 ? 45 : 20);
    put16(h, r.flags);
    put16(h, r.method);
    put16(h, r.dosTime);
    put16(h, r.dosDate);
    put32(h, descriptor ? 0 : r.crc);
    put32(h, r.zip64 ? 0xFFFFFFFFu : descriptor ? 0 : r.csize);
    put32(h, r.zip64 ? 0xFFFFFFFFu : descriptor ? 0 : r.usize);
    std::string extra;
    if (r.zip64) {
        put16(extra, kExtraZip64);
        put16(extra, 16);
        put64(extra, descriptor ? 0 : r.usize);
        put64(extra, descriptor ? 0 : r.csize);
    }
    putTimestampExtra(extra, r.haveTime, r.mtime);
    put16(h, r.name.size());
    put16(h, extra.size());
    h += r.name;
    h += extra;
    if (Status s = sink_.write(h); s != Status::Ok) return sinkFailed(s);
    return Status::Ok;
}

Status ZipWriter::addDirectory(std::string_view path, int64_t mtimeMs, uint32_t mode) {
    if (Status s = ready(); s != Status::Ok) return s;
    Record r;
    if (Status s = prepare(path, true, mtimeMs, mode, r); s != Status::Ok) return s;
    if (Status s = writeLocal(r); s != Status::Ok) return s;
    records_.push_back(std::move(r));
    return Status::Ok;
}

Status ZipWriter::drain(z_stream& z, int flush, uint64_t& csize) {
    do {
        z.next_out = out_.data();
        z.avail_out = static_cast<uInt>(out_.size());
        const int rc = ::deflate(&z, flush);
        if (rc == Z_STREAM_ERROR) return fail(Status::IoError, "deflate failed");
        const size_t got = out_.size() - z.avail_out;
        csize += got;
        if (Status s = sink_.write(out_.data(), got); s != Status::Ok) return sinkFailed(s);
        if (rc == Z_STREAM_END) break;
    } while (z.avail_out == 0 || (flush == Z_FINISH));
    return Status::Ok;
}

Status ZipWriter::checkSourceEnd(int inFd) {
    uint8_t probe = 0;
    const ssize_t extra = readFull(inFd, &probe, 1);
    if (extra != 0) return fail(Status::IoError, extra < 0 ? "reading the source failed" : "source is longer than its size");
    return Status::Ok;
}

Status ZipWriter::addFile(std::string_view path, int inFd, uint64_t size, int64_t mtimeMs, uint32_t mode,
                          const Progress& progress) {
    if (Status s = ready(); s != Status::Ok) return s;
    if (inFd < 0) return fail(Status::IoError, "invalid source descriptor", false);
    Record r;
    if (Status s = prepare(path, false, mtimeMs, mode, r); s != Status::Ok) return s;
    ProgressTicker ticker(progress);
    // Deflate can grow data that does not compress; zlib's deflateBound() formula.
    const uint64_t bound = size + (size >> 12) + (size >> 14) + (size >> 25) + 13 + 64;
    const bool big = size >= options_.zip64EntrySize || bound >= options_.zip64Offset;
    // java.util.zip.ZipInputStream reads an 8-byte descriptor only when a real
    // size passes the 32-bit limit, so a ZIP64 local header is kept for sizes
    // above it. A big entry below it is stored with its CRC known up front,
    // which needs no descriptor and no ZIP64 local header.
    if (big && size <= options_.zip64Offset) {
        if (const off_t start = preadStart(inFd); start >= 0) {
            r.method = 0;
            r.zip64 = size >= options_.zip64Offset;
            r.csize = size;
            r.usize = size;
            return addStored(r, inFd, size, static_cast<uint64_t>(start), progress, ticker);
        }
    }
    // Level 0 still goes through deflate (stored blocks): streaming readers
    // cannot find the end of a method-0 entry that has a data descriptor.
    r.method = 8;
    r.flags |= kFlagDescriptor;
    r.zip64 = big;
    return addDeflated(r, inFd, size, ticker);
}

Status ZipWriter::addDeflated(Record& r, int inFd, uint64_t size, ProgressTicker& ticker) {
    if (Status s = writeLocal(r); s != Status::Ok) return s;
    z_stream z{};
    if (deflateInit2(&z, options_.level, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK)
        return fail(Status::IoError, "deflate init failed");
    struct Guard {
        z_stream* z;
        ~Guard() { deflateEnd(z); }
    } guard{&z};
    uint32_t crc = static_cast<uint32_t>(::crc32(0, nullptr, 0));
    uint64_t done = 0;
    while (done < size) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(in_.size(), size - done));
        const ssize_t got = readFull(inFd, in_.data(), want);
        if (got < 0) return fail(Status::IoError, std::string("reading the source failed: ") + std::strerror(errno));
        if (static_cast<size_t>(got) < want) return fail(Status::IoError, "source is shorter than its size");
        crc = static_cast<uint32_t>(::crc32(crc, in_.data(), static_cast<uInt>(want)));
        done += want;
        z.next_in = in_.data();
        z.avail_in = static_cast<uInt>(want);
        if (Status s = drain(z, Z_NO_FLUSH, r.csize); s != Status::Ok) return s;
        if (!ticker.add(want)) return fail(Status::Cancelled, "cancelled");
    }
    if (Status s = checkSourceEnd(inFd); s != Status::Ok) return s;
    z.next_in = nullptr;
    z.avail_in = 0;
    if (Status s = drain(z, Z_FINISH, r.csize); s != Status::Ok) return s;
    r.crc = crc;
    r.usize = size;
    if (!r.zip64 && r.csize >= options_.zip64Offset) return fail(Status::IoError, "entry grew past 4 GiB without ZIP64");

    std::string d;
    put32(d, kDescriptorSig);
    put32(d, r.crc);
    if (r.zip64) {
        put64(d, r.csize);
        put64(d, r.usize);
    } else {
        put32(d, r.csize);
        put32(d, r.usize);
    }
    if (Status s = sink_.write(d); s != Status::Ok) return sinkFailed(s);
    records_.push_back(std::move(r));
    if (!ticker.finish()) return fail(Status::Cancelled, "cancelled");
    return Status::Ok;
}

Status ZipWriter::addStored(Record& r, int inFd, uint64_t size, uint64_t start, const Progress& progress,
                            ProgressTicker& ticker) {
    // First pass: the CRC, read with pread so inFd's offset stays where it is.
    // Nothing is written yet, so a failure here leaves the archive usable.
    // Progress is called with 0 so the caller can cancel without the total
    // running past size.
    uint32_t crc = static_cast<uint32_t>(::crc32(0, nullptr, 0));
    uint64_t done = 0;
    uint64_t nextPoll = kProgressStep;
    while (done < size) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(in_.size(), size - done));
        const ssize_t got = preadFull(inFd, in_.data(), want, start + done);
        if (got < 0)
            return fail(Status::IoError, std::string("reading the source failed: ") + std::strerror(errno), false);
        if (static_cast<size_t>(got) < want) return fail(Status::IoError, "source is shorter than its size", false);
        crc = static_cast<uint32_t>(::crc32(crc, in_.data(), static_cast<uInt>(want)));
        done += want;
        if (progress && done >= nextPoll) {
            nextPoll = done + kProgressStep;
            if (!progress(0)) return fail(Status::Cancelled, "cancelled", false);
        }
    }
    r.crc = crc;
    if (Status s = writeLocal(r); s != Status::Ok) return s;

    // Second pass: the copy, checking that the data did not change in between.
    uint32_t again = static_cast<uint32_t>(::crc32(0, nullptr, 0));
    done = 0;
    while (done < size) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(in_.size(), size - done));
        const ssize_t got = readFull(inFd, in_.data(), want);
        if (got < 0) return fail(Status::IoError, std::string("reading the source failed: ") + std::strerror(errno));
        if (static_cast<size_t>(got) < want) return fail(Status::IoError, "source is shorter than its size");
        again = static_cast<uint32_t>(::crc32(again, in_.data(), static_cast<uInt>(want)));
        done += want;
        if (Status s = sink_.write(in_.data(), want); s != Status::Ok) return sinkFailed(s);
        if (!ticker.add(want)) return fail(Status::Cancelled, "cancelled");
    }
    if (Status s = checkSourceEnd(inFd); s != Status::Ok) return s;
    if (again != crc) return fail(Status::IoError, "the source changed while it was being added");
    records_.push_back(std::move(r));
    if (!ticker.finish()) return fail(Status::Cancelled, "cancelled");
    return Status::Ok;
}

Status ZipWriter::finish() {
    if (Status s = ready(); s != Status::Ok) return s;
    finished_ = true;
    const uint64_t cdStart = sink_.offset();
    for (const Record& r : records_) {
        const bool sizes64 = r.zip64 || r.usize >= options_.zip64Offset || r.csize >= options_.zip64Offset;
        const bool offset64 = r.offset >= options_.zip64Offset;
        std::string extra;
        if (sizes64 || offset64) {
            put16(extra, kExtraZip64);
            put16(extra, (sizes64 ? 16 : 0) + (offset64 ? 8 : 0));
            if (sizes64) {
                put64(extra, r.usize);
                put64(extra, r.csize);
            }
            if (offset64) put64(extra, r.offset);
        }
        putTimestampExtra(extra, r.haveTime, r.mtime);
        std::string h;
        put32(h, kCentralSig);
        put16(h, (kHostUnix << 8) | 63);
        put16(h, sizes64 || offset64 ? 45 : 20);
        put16(h, r.flags);
        put16(h, r.method);
        put16(h, r.dosTime);
        put16(h, r.dosDate);
        put32(h, r.crc);
        put32(h, sizes64 ? 0xFFFFFFFFull : r.csize);
        put32(h, sizes64 ? 0xFFFFFFFFull : r.usize);
        put16(h, r.name.size());
        put16(h, extra.size());
        put16(h, 0);  // comment
        put16(h, 0);  // disk
        put16(h, 0);  // internal attributes
        put32(h, r.external);
        put32(h, offset64 ? 0xFFFFFFFFull : r.offset);
        h += r.name;
        h += extra;
        if (Status s = sink_.write(h); s != Status::Ok) return sinkFailed(s);
    }
    const uint64_t cdSize = sink_.offset() - cdStart;
    const uint64_t count = records_.size();
    const bool count64 = count >= options_.zip64Count || count >= 0xFFFF;
    const bool size64 = cdSize >= options_.zip64Offset;
    const bool start64 = cdStart >= options_.zip64Offset;
    std::string e;
    if (count64 || size64 || start64) {
        const uint64_t end64 = sink_.offset();
        put32(e, kEnd64Sig);
        put64(e, kEnd64Size - 12);
        put16(e, (kHostUnix << 8) | 63);
        put16(e, 45);
        put32(e, 0);
        put32(e, 0);
        put64(e, count);
        put64(e, count);
        put64(e, cdSize);
        put64(e, cdStart);
        put32(e, kLocator64Sig);
        put32(e, 0);
        put64(e, end64);
        put32(e, 1);
    }
    put32(e, kEndSig);
    put16(e, 0);
    put16(e, 0);
    put16(e, count64 ? 0xFFFF : count);
    put16(e, count64 ? 0xFFFF : count);
    put32(e, size64 ? 0xFFFFFFFFull : cdSize);
    put32(e, start64 ? 0xFFFFFFFFull : cdStart);
    put16(e, 0);
    if (Status s = sink_.write(e); s != Status::Ok) return sinkFailed(s);
    if (Status s = sink_.flush(); s != Status::Ok) return sinkFailed(s);
    return closeFd();
}

}  // namespace

std::unique_ptr<Reader> openZipReader(int fd, const Limits& limits, Status* status, std::string* error) {
    auto reader = std::make_unique<ZipReader>(fd, limits);
    const Status s = reader->init();
    if (s != Status::Ok) {
        *status = s;
        *error = reader->lastError();
        return nullptr;
    }
    *status = Status::Ok;
    return reader;
}

std::unique_ptr<Writer> openZipWriter(int fd, const WriterOptions& options) {
    return std::make_unique<ZipWriter>(fd, options);
}

}  // namespace lx::archive::detail
