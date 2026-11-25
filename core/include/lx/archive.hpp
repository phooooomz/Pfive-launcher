// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Streaming ZIP, TAR and TAR.GZ reading and writing on file descriptors, and
// the path rules that extraction applies to every entry name. Nothing here
// holds a whole archive or entry in memory; each buffer is 256 KiB or less.
// The numeric values match the constants in NativeArchive.kt.
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <string_view>

namespace lx::archive {

enum class Format : int32_t { Unknown = -1, Zip = 0, Tar = 1, TarGz = 2 };

enum class EntryType : int32_t { File = 0, Dir = 1, Symlink = 2, Hardlink = 3, Other = 4 };

enum class PathVerdict : int32_t {
    Ok = 0,
    Empty = 1,
    Absolute = 2,
    DotDot = 3,
    Drive = 4,
    Control = 5,
    BadUtf8 = 6,
    TooLong = 7,
    ComponentTooLong = 8,
    Reserved = 9,
};

enum class Status : int32_t {
    Ok = 0,
    End = 1,
    IoError = -1,
    FormatError = -2,
    Corrupt = -3,
    CrcError = -4,
    LimitExceeded = -5,
    Cancelled = -6,
    Unsupported = -7,
    NoSpace = -8,
};

constexpr size_t kMaxPathBytes = 4096;
constexpr size_t kMaxComponentBytes = 255;
constexpr size_t kMaxLinkBytes = 4096;
// GNU 'L'/'K' long names longer than this fail with LimitExceeded.
constexpr size_t kMaxLongNameBytes = 64 * 1024;
// A ZIP entry whose declared size reaches this gets ZIP64 treatment. The
// margin below 4 GiB leaves room for deflate output that is larger than its input.
constexpr uint64_t kZip64EntrySize = 0xFFFFFFFFull - 1024 * 1024;

// Each limit is off when 0.
struct Limits {
    uint64_t maxEntries = 0;
    uint64_t maxTotalBytes = 0;
    uint64_t maxEntryBytes = 0;
    // Highest uncompressed:compressed ratio, times 100. Once the archive has
    // produced more than ratioGraceBytes, everything produced so far is held
    // against everything compressed read so far, so splitting a bomb into many
    // small entries does not get past it. A ZIP entry that produced more than
    // the grace is also checked on its own.
    uint64_t maxRatioX100 = 0;
    uint64_t ratioGraceBytes = 0;
};

struct Entry {
    EntryType type = EntryType::File;
    // Bytes extract() writes: the data of a file, the target of a ZIP symlink, 0 for the rest.
    uint64_t size = 0;
    // Stored bytes, or -1 when the stream is compressed as a whole (TAR.GZ).
    int64_t compressed = 0;
    int64_t mtimeMs = 0;
    // Permission bits, 0 when the archive did not record any.
    uint32_t mode = 0;
    // The path rules of checkPath(), plus the link rules: DotDot for an entry
    // at or below a symlink seen earlier in the archive, and for links whose
    // target fails checkLinkTarget() (which can also give Absolute and others).
    PathVerdict verdict = PathVerdict::Ok;
    bool encrypted = false;
    // The stored name. ZIP names that are not UTF-8 are decoded from CP437;
    // TAR names are passed on as stored and may be invalid UTF-8.
    std::string raw;
    // Normalised path when verdict is Ok, otherwise empty.
    std::string path;
    // Symlink target or hard link target as stored; the verdict covers it.
    std::string link;
};

// Called with the running byte total of the current call, about once per MiB
// and once at the end. Returning false cancels the call.
using Progress = std::function<bool(uint64_t bytes)>;

namespace detail {
class LinkTracker;
}

class Reader {
public:
    virtual ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;

    // Takes ownership of fd and closes it on destruction, also on failure.
    // Format::Unknown runs detect() first. ZIP needs a file that supports
    // pread; on a pipe it fails with Unsupported. ZIP reads the whole file;
    // TAR starts at the fd's offset. Neither moves the offset of a regular
    // file, so a dup of the caller's fd leaves the caller's position alone and
    // the same file can be read again. On failure returns null and fills
    // status and error.
    static std::unique_ptr<Reader> open(int fd, Format format, const Limits& limits, Status* status,
                                        std::string* error);

    // Moves to the next entry: Ok, End, or an error. Data of the previous entry
    // that was not extracted is skipped (for TAR.GZ that means inflating it,
    // with no progress calls; extract(-1, progress) skips it with them). After
    // LimitExceeded or a broken archive structure every later call returns the
    // same error. For TAR.GZ the gzip CRC and length of the last member are
    // checked when the end is reached, so data extracted earlier is only known
    // to be intact once next() has returned End.
    virtual Status next(Entry& entry) = 0;

    // Writes the current entry's data to outFd (-1 only verifies it). Checks
    // the CRC for ZIP, the declared size and the limits. Directories and hard
    // links write nothing; TYPE_OTHER entries and encrypted or unknown ZIP
    // methods return Unsupported.
    virtual Status extract(int outFd, const Progress& progress) = 0;

    const std::string& lastError() const { return error_; }

protected:
    Reader(int fd, const Limits& limits);

    // Counts an entry and its declared size against the limits.
    Status admitEntry(uint64_t declaredSize);
    // Counts produced bytes during extract. entryIn is the compressed size
    // behind the current entry (0 skips the per-entry ratio check); archiveOut
    // and archiveIn are the totals for the whole archive.
    Status accountOutput(uint64_t chunk, uint64_t entryProduced, uint64_t entryIn, uint64_t archiveOut,
                         uint64_t archiveIn);
    // The archive-wide ratio check on its own, for data that is inflated but not extracted.
    Status checkRatio(uint64_t archiveOut, uint64_t archiveIn);
    // Applies the link rules to an entry whose path verdict is set, and
    // remembers symlinks. Fails only when too many symlinks were seen.
    Status applyLinkRules(Entry& entry);
    // Records the message; sticky errors also close the reader for later calls.
    Status fail(Status status, std::string message, bool sticky);

    int fd_;
    Limits limits_;
    std::string error_;
    Status closed_ = Status::Ok;

private:
    uint64_t entries_ = 0;
    uint64_t declaredTotal_ = 0;
    uint64_t producedTotal_ = 0;
    std::unique_ptr<detail::LinkTracker> links_;
};

struct WriterOptions {
    // 0 stores (deflate's stored blocks, see ZIP below), 1 to 9 deflate.
    int level = 6;
    // ZIP entries whose size reaches this get ZIP64 treatment.
    uint64_t zip64EntrySize = kZip64EntrySize;
    // The ZIP 32-bit field limit: offsets and sizes at or above it use ZIP64
    // fields. Tests lower these three to cover the ZIP64 records with tiny files.
    uint64_t zip64Offset = 0xFFFFFFFFull;
    // Entry counts at or above this need the ZIP64 end record.
    uint64_t zip64Count = 0xFFFF;
};

class Writer {
public:
    virtual ~Writer();
    Writer(const Writer&) = delete;
    Writer& operator=(const Writer&) = delete;

    // Takes ownership of fd. Returns null with the reason in error when the
    // format or level is not valid.
    static std::unique_ptr<Writer> open(int fd, Format format, const WriterOptions& options, std::string* error);

    // path uses "/" and no leading slash; it must pass checkPath. A mode of 0
    // stores 0755 for folders and 0644 for files. mtimeMs is UTC.
    virtual Status addDirectory(std::string_view path, int64_t mtimeMs, uint32_t mode) = 0;
    // Reads exactly size bytes from inFd (which stays open). A source that is
    // shorter or longer fails with IoError and leaves a broken archive.
    //
    // ZIP entries carry a data descriptor and are deflated; level 0 uses
    // deflate's stored blocks, because readers that stream (java.util.zip.
    // ZipInputStream) cannot find the end of a method-0 entry that has a
    // descriptor. A ZIP64 local header is only written for sizes above 4 GiB,
    // since ZipInputStream sizes the descriptor from the real byte counts. An
    // entry just under 4 GiB, where deflate could pass 4 GiB, is stored as
    // method 0 with its CRC taken in a first pass over inFd, so it needs no
    // descriptor. That pass uses pread, calls progress with 0, and writes
    // nothing, so failing or cancelling there leaves the archive usable. When
    // inFd cannot pread, the entry falls back to a ZIP64 deflate entry.
    virtual Status addFile(std::string_view path, int inFd, uint64_t size, int64_t mtimeMs, uint32_t mode,
                           const Progress& progress) = 0;
    // Writes the central directory or end blocks and closes the fd.
    virtual Status finish() = 0;

    const std::string& lastError() const { return error_; }

protected:
    explicit Writer(int fd) : fd_(fd) {}
    Status fail(Status status, std::string message, bool sticky = true);
    // Closes the fd; a failing close() is reported as a write error.
    Status closeFd();

    int fd_;
    std::string error_;
    Status broken_ = Status::Ok;
};

// Reads the first bytes of fd. A file that can seek is read with pread, so its
// offset does not move; a pipe loses the bytes read.
Format detect(int fd);

// Checks an entry path the way extraction does. Backslashes become "/",
// repeated slashes and "." components are dropped, a trailing "/" is removed.
// Control covers bytes below 0x20 and 0x7F, the C1 controls U+0080..U+009F,
// U+2028/U+2029 and the bidi embeddings, overrides and isolates
// (U+202A..U+202E, U+2066..U+2069) that can make "evil", U+202E, "txt.apk"
// show as "evilkpa.txt". fat adds the FAT rules: no " * : < > ? | and no component
// ending in "." or a space. normalized receives the cleaned path when the
// verdict is Ok.
PathVerdict checkPath(std::string_view raw, bool fat, std::string* normalized);

// Checks one file or folder name typed by the user: the path rules for a
// single component, where "/", "\", "." and ".." are Reserved.
PathVerdict checkName(std::string_view name, bool fat);

// Checks the target of a link entry at entryPath (a normalised path).
// A hard link target names another entry, so it follows checkPath. A symlink
// target is resolved from the link's folder: it must be relative, may start
// with ".." components as long as they stay inside the archive root, and may
// not use ".." after a normal component ("a/../b"), since "a" could itself be
// a symlink. resolved receives the target as a path from the archive root
// ("" for the root) when the verdict is Ok.
PathVerdict checkLinkTarget(std::string_view entryPath, std::string_view target, bool hardLink,
                            std::string* resolved);

// ("report.pdf", 2) gives "report (2).pdf". Double extensions such as
// ".tar.gz" stay together, a dotfile like ".profile" gets the suffix at the
// end, and n below 2 returns the name unchanged. The result stays within
// kMaxComponentBytes.
std::string numberedName(std::string_view name, int n);

// Strict UTF-8: no overlong forms, surrogates or values above U+10FFFF.
bool isValidUtf8(std::string_view s);
std::string cp437ToUtf8(std::string_view s);
// For the JNI layer: invalid UTF-8 bytes and lone surrogates become U+FFFD.
std::u16string utf8ToUtf16(std::string_view s);
std::string utf16ToUtf8(std::u16string_view s);

}  // namespace lx::archive
