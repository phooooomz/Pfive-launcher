// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>
#include <zlib.h>

#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "archive_test_util.hpp"

using namespace lxtest;
using lx::archive::Reader;
using lx::archive::Writer;

namespace {

constexpr int64_t kTime = 1700000000000;

uint32_t le32At(const std::string& s, size_t pos) {
    return static_cast<uint32_t>(static_cast<uint8_t>(s[pos])) | (static_cast<uint32_t>(static_cast<uint8_t>(s[pos + 1])) << 8) |
           (static_cast<uint32_t>(static_cast<uint8_t>(s[pos + 2])) << 16) |
           (static_cast<uint32_t>(static_cast<uint8_t>(s[pos + 3])) << 24);
}

void put32At(std::string& s, size_t pos, uint32_t v) {
    for (int i = 0; i < 4; ++i) s[pos + static_cast<size_t>(i)] = static_cast<char>((v >> (8 * i)) & 0xFF);
}

std::vector<size_t> findAll(const std::string& s, const std::string& needle) {
    std::vector<size_t> out;
    for (size_t p = s.find(needle); p != std::string::npos; p = s.find(needle, p + 1)) out.push_back(p);
    return out;
}

const std::string kCentralSig("PK\x01\x02", 4);
const std::string kLocalSig("PK\x03\x04", 4);

std::string deepPath(size_t components, size_t width) {
    std::string p;
    for (size_t i = 0; i < components; ++i) p += std::string(width, static_cast<char>('a' + i % 26)) + "/";
    return p;
}

std::vector<Item> sampleItems() {
    std::vector<Item> items;
    items.push_back({"top", true, "", kTime, 0750});
    items.push_back({"top/nested", true, "", kTime, 0});
    items.push_back({"top/nested/deeper", true, "", kTime, 0});
    items.push_back({"top/nested/deeper/file.txt", false, "deep file\n", kTime, 0600});
    items.push_back({"empty-folder", true, "", kTime, 0});
    items.push_back({"zero.bin", false, "", kTime, 0});
    items.push_back({"unicode/\xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E/\xC3\xA9t\xC3\xA9 \xE2\x9C\x93.txt", false, "unicode", kTime, 0644});
    items.push_back({"long/" + std::string(60, 'm') + "/" + std::string(60, 'n') + ".txt", false, "over 100", kTime, 0});
    items.push_back({deepPath(6, 50) + "beyond-255.txt", false, "over 255", kTime, 0});
    items.push_back({"single/" + std::string(200, 's'), false, "long component", kTime, 0});
    items.push_back({"old.txt", false, "before 1970", -86400000, 0});
    items.push_back({"big.bin", false, sampleData(3 * 1024 * 1024 + 123, 11), kTime, 0755});
    return items;
}

void expectSame(const std::vector<Item>& items, const ReadResult& r, bool zip) {
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), items.size());
    for (size_t i = 0; i < items.size(); ++i) {
        const Item& it = items[i];
        const ReadEntry& e = r.entries[i];
        SCOPED_TRACE(it.path.substr(0, 80));
        EXPECT_EQ(e.entry.verdict, PathVerdict::Ok);
        EXPECT_EQ(e.entry.path, it.path);
        EXPECT_EQ(e.entry.type, it.dir ? EntryType::Dir : EntryType::File);
        EXPECT_EQ(e.entry.mtimeMs, it.mtimeMs);
        const uint32_t mode = it.mode ? it.mode : (it.dir ? 0755u : 0644u);
        EXPECT_EQ(e.entry.mode, mode);
        if (!it.dir) {
            EXPECT_EQ(e.extracted, Status::Ok);
            EXPECT_EQ(e.entry.size, it.data.size());
            EXPECT_TRUE(e.data == it.data) << "data differs, " << e.data.size() << " bytes";
        }
        if (zip) EXPECT_GE(e.entry.compressed, 0);
    }
}

struct Case {
    Format format;
    int level;
};

class ArchiveRoundTrip : public ::testing::TestWithParam<Case> {};

std::string endBlocks() { return std::string(1024, '\0'); }

int64_t localDosMs(int y, int mo, int d, int h, int mi, int s) {
    std::tm tm{};
    tm.tm_year = y - 1900;
    tm.tm_mon = mo - 1;
    tm.tm_mday = d;
    tm.tm_hour = h;
    tm.tm_min = mi;
    tm.tm_sec = s;
    tm.tm_isdst = -1;
    return static_cast<int64_t>(std::mktime(&tm)) * 1000;
}

uint32_t crcOf(const std::string& s) {
    return static_cast<uint32_t>(crc32(0, reinterpret_cast<const Bytef*>(s.data()), static_cast<uInt>(s.size())));
}

std::string gzipOf(const std::string& data) {
    z_stream z{};
    deflateInit2(&z, 6, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY);
    std::string out(deflateBound(&z, data.size()) + 64, '\0');
    z.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(data.data()));
    z.avail_in = static_cast<uInt>(data.size());
    z.next_out = reinterpret_cast<Bytef*>(out.data());
    z.avail_out = static_cast<uInt>(out.size());
    deflate(&z, Z_FINISH);
    out.resize(z.total_out);
    deflateEnd(&z);
    return out;
}

std::string caseName(const ::testing::TestParamInfo<Case>& info) {
    const char* names[] = {"Zip", "Tar", "TarGz"};
    return std::string(names[static_cast<int>(info.param.format)]) + "Level" + std::to_string(info.param.level);
}

Format detectFile(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    const Format f = lx::archive::detect(fd);
    ::close(fd);
    return f;
}

}  // namespace

TEST_P(ArchiveRoundTrip, WritesAndReadsBack) {
    ScratchDir dir;
    const auto items = sampleItems();
    const std::string file = dir.file("out.bin");
    WriterOptions opts;
    opts.level = GetParam().level;
    std::string error;
    ASSERT_EQ(writeArchive(file, GetParam().format, items, opts, &error), Status::Ok) << error;
    EXPECT_EQ(detectFile(file), GetParam().format);
    expectSame(items, readAll(file, GetParam().format), GetParam().format == Format::Zip);
    // Format::Unknown detects the format first.
    expectSame(items, readAll(file, Format::Unknown), GetParam().format == Format::Zip);
}

INSTANTIATE_TEST_SUITE_P(Formats, ArchiveRoundTrip,
                         ::testing::Values(Case{Format::Zip, 0}, Case{Format::Zip, 6}, Case{Format::Tar, 0},
                                           Case{Format::TarGz, 6}, Case{Format::TarGz, 0}),
                         caseName);

TEST(ArchiveZip, Zip64RecordsThroughTestHook) {
    ScratchDir dir;
    const std::string file = dir.file("z64.zip");
    std::vector<Item> items = {{"a", true, "", kTime, 0}, {"a/one.txt", false, "first", kTime, 0},
                               {"two.bin", false, sampleData(300000, 3), kTime, 0}, {"empty", false, "", kTime, 0}};
    WriterOptions opts;
    opts.zip64EntrySize = 0;
    opts.zip64Offset = 0;
    opts.zip64Count = 0;
    ASSERT_EQ(writeArchive(file, Format::Zip, items, opts), Status::Ok);
    const std::string bytes = readFile(file);
    EXPECT_EQ(findAll(bytes, std::string("PK\x06\x06", 4)).size(), 1u);  // ZIP64 end record
    EXPECT_EQ(findAll(bytes, std::string("PK\x06\x07", 4)).size(), 1u);  // its locator
    // The plain end record points to the ZIP64 one with all-ones fields.
    const size_t eocd = bytes.size() - 22;
    EXPECT_EQ(le32At(bytes, eocd + 12), 0xFFFFFFFFu);
    EXPECT_EQ(le32At(bytes, eocd + 16), 0xFFFFFFFFu);
    const auto r = readAll(file, Format::Zip);
    expectSame(items, r, true);
}

TEST(ArchiveZip, ReadsPythonArchives) {
    for (const char* name : {"py_deflate.zip", "py_stored.zip"}) {
        SCOPED_TRACE(name);
        const auto r = readAll(fixture(name), Format::Zip);
        ASSERT_EQ(r.last, Status::End) << r.error;
        ASSERT_EQ(r.entries.size(), 6u);
        EXPECT_EQ(r.entries[0].entry.type, EntryType::Dir);
        EXPECT_EQ(r.entries[0].entry.path, "docs");
        EXPECT_EQ(r.entries[1].entry.path, "docs/empty");
        EXPECT_EQ(r.entries[2].data, [] {
            std::string s;
            for (int i = 0; i < 50; ++i) s += "hello from python\n";
            return s;
        }());
        EXPECT_EQ(r.entries[3].entry.size, 0u);
        EXPECT_EQ(r.entries[3].extracted, Status::Ok);
        EXPECT_EQ(r.entries[4].entry.raw, "unicode/h\xC3\xA9llo w\xC3\xB6rld \xE2\x9C\x93.txt");
        EXPECT_EQ(r.entries[4].data, "\xC3\xBC" "ber");
        EXPECT_EQ(r.entries[5].entry.size, 12000u);
        EXPECT_EQ(r.entries[5].extracted, Status::Ok);
        // No extended timestamp: the DOS time is read as local time.
        EXPECT_EQ(r.entries[2].entry.mtimeMs, localDosMs(2024, 5, 17, 10, 30, 42));
    }
    const auto r = readAll(fixture("py_forced64.zip"), Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].extracted, Status::Ok);
    EXPECT_EQ(r.entries[0].data.size(), 19u * 20u);
}

TEST(ArchiveZip, Cp437Names) {
    const auto r = readAll(fixture("cp437.zip"), Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].entry.raw, "caf\xC3\xA9.txt");
    EXPECT_EQ(r.entries[0].entry.path, "caf\xC3\xA9.txt");
    EXPECT_EQ(r.entries[1].entry.raw, "\xC3\x9C\xC3\xBC" "ber.txt");
    EXPECT_EQ(r.entries[1].data, "cp437 two");
}

TEST(ArchiveZip, UnixSymlinkAndSpecialFile) {
    const auto r = readAll(fixture("unix.zip"), Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 3u);
    EXPECT_EQ(r.entries[0].entry.type, EntryType::File);
    EXPECT_EQ(r.entries[0].entry.mode, 0640u);
    EXPECT_EQ(r.entries[1].entry.type, EntryType::Symlink);
    EXPECT_EQ(r.entries[1].entry.link, "target.txt");
    EXPECT_EQ(r.entries[1].entry.mode, 0777u);
    EXPECT_EQ(r.entries[2].entry.type, EntryType::Other);
}

TEST(ArchiveZip, CrcMismatchIsReportedAndReadingGoesOn) {
    ScratchDir dir;
    const std::string file = dir.file("crc.zip");
    WriterOptions stored;
    stored.level = 0;
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a.txt", false, "AAAAAAAAAA"}, {"b.txt", false, "BBBBBBBBBB"}}, stored),
              Status::Ok);
    std::string bytes = readFile(file);
    const size_t data = bytes.find("AAAAAAAAAA");
    ASSERT_NE(data, std::string::npos);
    bytes[data + 3] = 'X';
    const auto r = readBytes(bytes, Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].extracted, Status::CrcError);
    EXPECT_EQ(r.entries[1].extracted, Status::Ok);
    EXPECT_EQ(r.entries[1].data, "BBBBBBBBBB");
}

TEST(ArchiveZip, DamagedDeflateData) {
    ScratchDir dir;
    const std::string file = dir.file("bad.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a.bin", false, sampleData(200000, 5)}}), Status::Ok);
    std::string bytes = readFile(file);
    for (size_t i = 100; i < 400; ++i) bytes[i] = static_cast<char>(bytes[i] ^ 0x5A);
    const auto r = readBytes(bytes, Format::Zip);
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_TRUE(r.entries[0].extracted == Status::Corrupt || r.entries[0].extracted == Status::CrcError);
}

TEST(ArchiveZip, OverlappingEntriesAreRejected) {
    ScratchDir dir;
    const std::string file = dir.file("bomb.zip");
    WriterOptions stored;
    stored.level = 0;
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"aaa", false, std::string(1000, 'x')}, {"bbb", false, std::string(1000, 'y')}},
                           stored),
              Status::Ok);
    const std::string clean = readFile(file);
    const auto central = findAll(clean, kCentralSig);
    ASSERT_EQ(central.size(), 2u);

    // The second record points at the first entry's local header.
    std::string shared = clean;
    put32At(shared, central[1] + 42, 0);
    auto r = readBytes(shared, Format::Zip);
    EXPECT_EQ(r.last, Status::Corrupt);
    EXPECT_EQ(r.entries.size(), 1u);

    // The second local header sits inside the first entry's data.
    std::string inside = clean;
    put32At(inside, central[1] + 42, 100);
    r = readBytes(inside, Format::Zip);
    EXPECT_EQ(r.last, Status::Corrupt);

    // A local header offset past the central directory.
    std::string outside = clean;
    put32At(outside, central[1] + 42, static_cast<uint32_t>(clean.size()));
    r = readBytes(outside, Format::Zip);
    EXPECT_EQ(r.last, Status::Corrupt);

    // Once the structure is known to be hostile, the reader stays closed.
    Status status;
    std::string error;
    auto reader = Reader::open(memFd(shared), Format::Zip, {}, &status, &error);
    ASSERT_TRUE(reader);
    Entry e;
    EXPECT_EQ(reader->next(e), Status::Ok);
    EXPECT_EQ(reader->next(e), Status::Corrupt);
    EXPECT_EQ(reader->next(e), Status::Corrupt);
    EXPECT_EQ(reader->extract(-1, {}), Status::Corrupt);
}

TEST(ArchiveZip, EncryptedFlag) {
    ScratchDir dir;
    const std::string file = dir.file("enc.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"secret.txt", false, "not really secret"}}), Status::Ok);
    std::string bytes = readFile(file);
    bytes[6] = static_cast<char>(bytes[6] | 1);
    const size_t central = bytes.find(kCentralSig);
    bytes[central + 8] = static_cast<char>(bytes[central + 8] | 1);
    Status status;
    std::string error;
    auto reader = Reader::open(memFd(bytes), Format::Zip, {}, &status, &error);
    ASSERT_TRUE(reader) << error;
    Entry e;
    ASSERT_EQ(reader->next(e), Status::Ok);
    EXPECT_TRUE(e.encrypted);
    EXPECT_EQ(reader->extract(-1, {}), Status::Unsupported);
    EXPECT_EQ(reader->next(e), Status::End);
}

TEST(ArchiveZip, UnknownMethodIsUnsupported) {
    ScratchDir dir;
    const std::string file = dir.file("m.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a.txt", false, "data"}}), Status::Ok);
    std::string bytes = readFile(file);
    const size_t central = bytes.find(kCentralSig);
    bytes[central + 10] = 12;  // bzip2
    auto r = readBytes(bytes, Format::Zip);
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].extracted, Status::Unsupported);
}

TEST(ArchiveZip, NeedsASeekableFile) {
    int p[2];
    ASSERT_EQ(pipe(p), 0);
    ScratchDir dir;
    ASSERT_EQ(writeArchive(dir.file("x.zip"), Format::Zip, {{"a", false, "b"}}), Status::Ok);
    const std::string bytes = readFile(dir.file("x.zip"));
    ASSERT_EQ(::write(p[1], bytes.data(), bytes.size()), static_cast<ssize_t>(bytes.size()));
    ::close(p[1]);
    Status status = Status::Ok;
    std::string error;
    EXPECT_FALSE(Reader::open(p[0], Format::Zip, {}, &status, &error));
    EXPECT_EQ(status, Status::Unsupported);
}

TEST(ArchiveZip, EmptyArchiveAndNotAZip) {
    ScratchDir dir;
    const std::string file = dir.file("empty.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {}), Status::Ok);
    EXPECT_EQ(readFile(file).size(), 22u);
    EXPECT_EQ(detectFile(file), Format::Zip);
    auto r = readAll(file, Format::Zip);
    EXPECT_EQ(r.last, Status::End);
    EXPECT_TRUE(r.entries.empty());
    r = readBytes("this is plain text, long enough to look for an end record", Format::Zip);
    EXPECT_EQ(r.openStatus, Status::FormatError);
}

TEST(ArchiveTar, GnuFixture) {
    const auto r = readAll(fixture("gnu.tar"), Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 9u);
    const std::string longName = "dir/" + std::string(120, 'n') + ".txt";
    EXPECT_EQ(r.entries[0].entry.type, EntryType::Dir);
    EXPECT_EQ(r.entries[1].entry.path, longName);
    EXPECT_EQ(r.entries[1].data, "gnu long name\n");
    EXPECT_EQ(r.entries[2].entry.type, EntryType::Symlink);
    EXPECT_EQ(r.entries[2].entry.link, "../" + std::string(150, 't'));
    EXPECT_EQ(r.entries[3].entry.type, EntryType::Hardlink);
    EXPECT_EQ(r.entries[3].entry.link, longName);
    EXPECT_EQ(r.entries[4].entry.mtimeMs, -86400000);  // base-256 mtime
    EXPECT_EQ(r.entries[4].data, "before 1970\n");
    EXPECT_EQ(r.entries[5].entry.type, EntryType::Other);  // character device
    EXPECT_EQ(r.entries[6].entry.type, EntryType::Other);  // block device
    EXPECT_EQ(r.entries[7].entry.type, EntryType::Other);  // FIFO
    EXPECT_EQ(r.entries[8].data, "after the devices\n");
    EXPECT_EQ(r.entries[8].entry.mtimeMs, 1715941842000);
    EXPECT_EQ(r.entries[8].entry.mode, 0644u);
}

TEST(ArchiveTar, PaxFixture) {
    const auto r = readAll(fixture("pax.tar"), Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 5u);
    std::string longDir;
    for (int i = 0; i < 6; ++i) {
        char seg[16];
        std::snprintf(seg, sizeof seg, "segment-%02d-", i);
        longDir += (i ? "/" : "") + std::string(seg) + std::string(40, 'x');
    }
    EXPECT_EQ(r.entries[0].entry.type, EntryType::Dir);
    EXPECT_EQ(r.entries[0].entry.path, longDir);
    EXPECT_EQ(r.entries[1].entry.path, longDir + "/deep.txt");
    EXPECT_EQ(r.entries[1].data, "pax long path\n");
    EXPECT_EQ(r.entries[2].entry.path, "unicode/\xE6\x97\xA5\xE6\x9C\xAC\xE8\xAA\x9E \xC3\xA9.txt");
    EXPECT_EQ(r.entries[3].entry.mtimeMs, 1700000000500);
    EXPECT_EQ(r.entries[4].entry.type, EntryType::Symlink);
    EXPECT_EQ(r.entries[4].entry.link, "../" + std::string(150, 't'));
}

TEST(ArchiveTar, GzipFixturesIncludingConcatenatedMembers) {
    for (const char* name : {"ustar.tar.gz", "concat.tar.gz"}) {
        SCOPED_TRACE(name);
        const auto r = readAll(fixture(name), Format::TarGz);
        ASSERT_EQ(r.last, Status::End) << r.error;
        ASSERT_EQ(r.entries.size(), 4u);
        EXPECT_EQ(r.entries[0].entry.path, "a.txt");
        EXPECT_EQ(r.entries[0].data.size(), 1300u);
        EXPECT_EQ(r.entries[1].data.size(), 3000u);
        EXPECT_EQ(r.entries[2].entry.type, EntryType::Dir);
        EXPECT_EQ(r.entries[3].data, "second member\n");
        EXPECT_EQ(r.entries[0].entry.compressed, -1);
    }
    const auto plain = readAll(fixture("ustar.tar.gz"), Format::TarGz);
    const auto joined = readAll(fixture("concat.tar.gz"), Format::TarGz);
    ASSERT_EQ(plain.entries.size(), joined.entries.size());
    EXPECT_EQ(crcOf(plain.entries[1].data), crcOf(joined.entries[1].data));
}

TEST(ArchiveTar, PaxSizeOverridesHeader) {
    std::string tar;
    const std::string pax = paxRecord("size", "5") + paxRecord("path", "from-pax.txt");
    tar += tarHeader("PaxHeader", 'x', octal(pax.size()));
    tar += padded(pax);
    tar += tarHeader("ignored", '0', octal(0));
    tar += padded("hello");
    tar += tarHeader("next.txt", '0', octal(3));
    tar += padded("abc");
    tar += endBlocks();
    const auto r = readBytes(tar, Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].entry.path, "from-pax.txt");
    EXPECT_EQ(r.entries[0].data, "hello");
    EXPECT_EQ(r.entries[1].entry.path, "next.txt");
    EXPECT_EQ(r.entries[1].data, "abc");
}

TEST(ArchiveTar, PaxGlobalHeaderApplies) {
    std::string tar;
    const std::string g = paxRecord("mtime", "1234.25");
    tar += tarHeader("global", 'g', octal(g.size()));
    tar += padded(g);
    tar += tarHeader("a.txt", '0', octal(1));
    tar += padded("a");
    tar += tarHeader("b.txt", '0', octal(1));
    tar += padded("b");
    tar += endBlocks();
    const auto r = readBytes(tar, Format::Tar);
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].entry.mtimeMs, 1234250);
    EXPECT_EQ(r.entries[1].entry.mtimeMs, 1234250);
}

TEST(ArchiveTar, Base256Size) {
    std::string size(12, '\0');
    size[0] = static_cast<char>(0x80);
    size[11] = 11;
    std::string tar = tarHeader("b256.txt", '0', size);
    tar += padded("eleven byte");
    tar += tarHeader("after", '0', octal(2));
    tar += padded("ok");
    tar += endBlocks();
    const auto r = readBytes(tar, Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].data, "eleven byte");
    EXPECT_EQ(r.entries[1].data, "ok");
}

TEST(ArchiveTar, V7HeaderAndEndVariants) {
    // No magic, a directory as a name ending in "/", and only one zero block.
    std::string tar = tarHeader("v7.txt", '0', octal(4), false);
    tar += padded("v7v7");
    tar += tarHeader("v7dir/", '\0', octal(0), false);
    const std::string oneZero = tar + std::string(512, '\0');
    auto r = readBytes(oneZero, Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);
    EXPECT_EQ(r.entries[0].data, "v7v7");
    EXPECT_EQ(r.entries[1].entry.type, EntryType::Dir);
    // A clean end of file on a block boundary is accepted too.
    r = readBytes(tar, Format::Tar);
    EXPECT_EQ(r.last, Status::End);
    EXPECT_EQ(r.entries.size(), 2u);
}

TEST(ArchiveTar, GnuSparseAndUnknownTypesAreSkipped) {
    std::string sparse = tarHeader("sparse", 'S', octal(600));
    sparse[482] = 1;  // one extension block follows
    // Recompute the checksum after the change.
    std::memset(sparse.data() + 148, ' ', 8);
    unsigned sum = 0;
    for (char c : sparse) sum += static_cast<uint8_t>(c);
    char chk[8];
    std::snprintf(chk, sizeof chk, "%06o", sum);
    std::memcpy(sparse.data() + 148, chk, 7);
    std::string tar = sparse + std::string(512, '\0') + padded(std::string(600, 'S'));
    tar += tarHeader("dumpdir", 'D', octal(10)) + padded("Ydir\0Nfile");
    tar += tarHeader("vendor", 'Q', octal(700)) + padded(std::string(700, 'Q'));
    tar += tarHeader("last.txt", '0', octal(4)) + padded("last");
    tar += endBlocks();
    Status status;
    std::string error;
    auto reader = Reader::open(memFd(tar), Format::Tar, {}, &status, &error);
    ASSERT_TRUE(reader);
    Entry e;
    for (const char* name : {"sparse", "dumpdir", "vendor"}) {
        ASSERT_EQ(reader->next(e), Status::Ok) << reader->lastError();
        EXPECT_EQ(e.raw, name);
        EXPECT_EQ(e.type, EntryType::Other);
        EXPECT_EQ(reader->extract(-1, {}), Status::Unsupported);
    }
    ASSERT_EQ(reader->next(e), Status::Ok);
    EXPECT_EQ(e.raw, "last.txt");
    const int out = memFd();
    EXPECT_EQ(reader->extract(out, {}), Status::Ok);
    EXPECT_EQ(fdContents(out), "last");
    ::close(out);
    EXPECT_EQ(reader->extract(-1, {}), Status::IoError);  // data already read
    EXPECT_EQ(reader->next(e), Status::End);
}

TEST(ArchiveTar, BadChecksum) {
    ScratchDir dir;
    const std::string file = dir.file("c.tar");
    ASSERT_EQ(writeArchive(file, Format::Tar, {{"a.txt", false, "a"}, {"b.txt", false, "b"}}), Status::Ok);
    const std::string clean = readFile(file);
    std::string first = clean;
    first[0] = 'X';
    auto r = readBytes(first, Format::Tar);
    EXPECT_EQ(r.last, Status::FormatError);
    std::string second = clean;
    second[1024] = 'X';
    r = readBytes(second, Format::Tar);
    EXPECT_EQ(r.last, Status::Corrupt);
    EXPECT_EQ(r.entries.size(), 1u);
}

TEST(ArchiveTar, GnuLongNameCap) {
    const std::string longName(70 * 1024, 'L');
    std::string tar = tarHeader("././@LongLink", 'L', octal(longName.size() + 1));
    tar += padded(longName + '\0');
    tar += tarHeader("short", '0', octal(0));
    tar += endBlocks();
    const auto r = readBytes(tar, Format::Tar);
    EXPECT_EQ(r.last, Status::LimitExceeded);
}

TEST(ArchiveTar, WriterUsesPrefixPaxAndRecordPadding) {
    ScratchDir dir;
    const std::string file = dir.file("w.tar");
    const std::string splittable = std::string(80, 'p') + "/" + std::string(70, 'q') + ".txt";
    ASSERT_EQ(writeArchive(file, Format::Tar, {{splittable, false, "x"}}), Status::Ok);
    std::string bytes = readFile(file);
    EXPECT_EQ(bytes.size() % 10240, 0u);
    EXPECT_EQ(bytes.find("PaxHeader"), std::string::npos);  // fits name + prefix
    EXPECT_EQ(bytes.substr(345, 80), std::string(80, 'p'));

    ASSERT_EQ(writeArchive(file, Format::Tar, {{"\xC3\xA9.txt", false, "x"}}), Status::Ok);
    bytes = readFile(file);
    EXPECT_NE(bytes.find("path=\xC3\xA9.txt\n"), std::string::npos);
    // The plain header carries an ASCII stand-in for readers without PAX.
    EXPECT_EQ(bytes.substr(1024 + 0, 6), "__.txt");
}

TEST(ArchiveTar, TarOnAPipe) {
    ScratchDir dir;
    ASSERT_EQ(writeArchive(dir.file("p.tar"), Format::Tar, {{"a.txt", false, "pipe data"}}), Status::Ok);
    const std::string bytes = readFile(dir.file("p.tar"));
    int p[2];
    ASSERT_EQ(pipe(p), 0);
    ASSERT_EQ(::write(p[1], bytes.data(), bytes.size()), static_cast<ssize_t>(bytes.size()));
    ::close(p[1]);
    EXPECT_EQ(lx::archive::detect(p[0]), Format::Tar);  // consumes the first block of a pipe
    ::close(p[0]);
    ASSERT_EQ(pipe(p), 0);
    ASSERT_EQ(::write(p[1], bytes.data(), bytes.size()), static_cast<ssize_t>(bytes.size()));
    ::close(p[1]);
    const auto r = readAll(p[0], Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].data, "pipe data");
    // Detecting on a pipe would eat the first block, so the format has to be given.
    ASSERT_EQ(pipe(p), 0);
    ::close(p[1]);
    Status status = Status::Ok;
    std::string error;
    EXPECT_FALSE(Reader::open(p[0], Format::Unknown, {}, &status, &error));
    EXPECT_EQ(status, Status::Unsupported);
}

TEST(ArchiveTar, NotGzip) {
    const auto r = readBytes(std::string(2048, 'x'), Format::TarGz);
    EXPECT_EQ(r.last, Status::FormatError);
}

TEST(ArchiveTar, GzipTrailerCrcIsChecked) {
    ScratchDir dir;
    ASSERT_EQ(writeArchive(dir.file("t.tar"), Format::Tar, {{"a.txt", false, "trailer check"}}), Status::Ok);
    // Header and data only, so reading on reaches the gzip trailer.
    std::string gz = gzipOf(readFile(dir.file("t.tar")).substr(0, 1024));
    gz[gz.size() - 8] = static_cast<char>(gz[gz.size() - 8] ^ 1);  // CRC32 of the member
    const auto r = readBytes(gz, Format::TarGz);
    EXPECT_EQ(r.last, Status::CrcError);
}

TEST(ArchiveLimits, EntryCount) {
    ScratchDir dir;
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        const std::string file = dir.file("count");
        std::vector<Item> items;
        for (int i = 0; i < 5; ++i) items.push_back({"f" + std::to_string(i), false, "x"});
        ASSERT_EQ(writeArchive(file, f, items), Status::Ok);
        Limits l;
        l.maxEntries = 3;
        const auto r = readAll(file, f, l);
        EXPECT_EQ(r.last, Status::LimitExceeded);
        EXPECT_EQ(r.entries.size(), 3u);
    }
}

TEST(ArchiveLimits, DeclaredSizes) {
    ScratchDir dir;
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        const std::string file = dir.file("sizes");
        ASSERT_EQ(writeArchive(file, f, {{"a", false, std::string(600, 'a')}, {"b", false, std::string(600, 'b')}}),
                  Status::Ok);
        Limits total;
        total.maxTotalBytes = 1000;
        auto r = readAll(file, f, total);
        EXPECT_EQ(r.last, Status::LimitExceeded);
        EXPECT_EQ(r.entries.size(), 1u);
        Limits each;
        each.maxEntryBytes = 500;
        r = readAll(file, f, each);
        EXPECT_EQ(r.last, Status::LimitExceeded);
        EXPECT_TRUE(r.entries.empty());
    }
}

TEST(ArchiveLimits, CompressionRatio) {
    ScratchDir dir;
    const std::string zeros(8 * 1024 * 1024, '\0');
    for (Format f : {Format::Zip, Format::TarGz}) {
        const std::string file = dir.file("ratio");
        WriterOptions opts;
        opts.level = 9;
        ASSERT_EQ(writeArchive(file, f, {{"zeros.bin", false, zeros}, {"after", false, "x"}}, opts), Status::Ok);
        Limits l;
        l.maxRatioX100 = 100 * 100;
        l.ratioGraceBytes = 1024 * 1024;
        Status status;
        std::string error;
        auto reader = Reader::open(::open(file.c_str(), O_RDONLY | O_CLOEXEC), f, l, &status, &error);
        ASSERT_TRUE(reader);
        Entry e;
        ASSERT_EQ(reader->next(e), Status::Ok);
        uint64_t written = 0;
        const int out = memFd();
        EXPECT_EQ(reader->extract(out, {}), Status::LimitExceeded);
        written = fdContents(out).size();
        ::close(out);
        EXPECT_LE(written, 2u * 1024 * 1024);
        EXPECT_EQ(reader->next(e), Status::LimitExceeded);  // stays closed
        // Without the ratio limit the same archive extracts.
        const auto r = readAll(file, f);
        ASSERT_EQ(r.last, Status::End);
        EXPECT_EQ(r.entries[0].data.size(), zeros.size());
    }
}

TEST(ArchiveLimits, ProducedBytesAreCountedDuringExtract) {
    // A ZIP whose central directory under-declares the size cannot produce more than it declared.
    ScratchDir dir;
    const std::string file = dir.file("liar.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a", false, std::string(5000, 'a')}}), Status::Ok);
    std::string bytes = readFile(file);
    const size_t central = bytes.find(kCentralSig);
    put32At(bytes, central + 24, 100);
    const auto r = readBytes(bytes, Format::Zip);
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].extracted, Status::Corrupt);
    EXPECT_LE(r.entries[0].data.size(), 100u + 128 * 1024);
}

TEST(ArchiveProgress, CancelStopsExtractAndAddFile) {
    ScratchDir dir;
    const std::string data = sampleData(3 * 1024 * 1024, 9);
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        const std::string file = dir.file("p");
        ASSERT_EQ(writeArchive(file, f, {{"big", false, data}}), Status::Ok);
        Status status;
        std::string error;
        auto reader = Reader::open(::open(file.c_str(), O_RDONLY | O_CLOEXEC), f, {}, &status, &error);
        Entry e;
        ASSERT_EQ(reader->next(e), Status::Ok);
        std::vector<uint64_t> seen;
        EXPECT_EQ(reader->extract(-1,
                                  [&](uint64_t n) {
                                      seen.push_back(n);
                                      return n < 1024 * 1024;
                                  }),
                  Status::Cancelled);
        ASSERT_FALSE(seen.empty());
        EXPECT_GE(seen.back(), 1024u * 1024u);
        EXPECT_LT(seen.back(), 2u * 1024 * 1024);
        EXPECT_EQ(reader->next(e), Status::End);  // the rest of the entry is skipped

        // A full run reports about once per MiB and ends with the total.
        auto again = Reader::open(::open(file.c_str(), O_RDONLY | O_CLOEXEC), f, {}, &status, &error);
        ASSERT_EQ(again->next(e), Status::Ok);
        seen.clear();
        EXPECT_EQ(again->extract(-1, [&](uint64_t n) { seen.push_back(n); return true; }), Status::Ok);
        ASSERT_GE(seen.size(), 3u);
        EXPECT_LE(seen.size(), 5u);
        EXPECT_EQ(seen.back(), data.size());

        // The writer stops too.
        auto w = Writer::open(::open(dir.file("w").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644), f, {}, &error);
        const int in = memFd(data);
        EXPECT_EQ(w->addFile("big", in, data.size(), kTime, 0, [](uint64_t) { return false; }), Status::Cancelled);
        ::close(in);
        EXPECT_EQ(w->finish(), Status::Cancelled);  // a broken writer cannot finish
    }
}

TEST(ArchiveWriter, SourceSizeMustMatch) {
    ScratchDir dir;
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        std::string error;
        auto w = Writer::open(::open(dir.file("s").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644), f, {}, &error);
        int in = memFd("12345");
        EXPECT_EQ(w->addFile("short", in, 10, kTime, 0, {}), Status::IoError);
        ::close(in);
        w = Writer::open(::open(dir.file("s").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644), f, {}, &error);
        in = memFd("1234567890");
        EXPECT_EQ(w->addFile("long", in, 5, kTime, 0, {}), Status::IoError);
        EXPECT_NE(w->lastError().find("longer"), std::string::npos);
        ::close(in);
    }
}

TEST(ArchiveWriter, RejectsBadPathsAndLevels) {
    ScratchDir dir;
    std::string error;
    EXPECT_FALSE(Writer::open(::open(dir.file("l").c_str(), O_CREAT | O_WRONLY | O_CLOEXEC, 0644), Format::Zip,
                              WriterOptions{10}, &error));
    EXPECT_FALSE(Writer::open(::open(dir.file("l").c_str(), O_CREAT | O_WRONLY | O_CLOEXEC, 0644), Format::Unknown, {}, &error));
    for (Format f : {Format::Zip, Format::Tar}) {
        auto w = Writer::open(::open(dir.file("b").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644), f, {}, &error);
        EXPECT_EQ(w->addDirectory("../escape", kTime, 0), Status::IoError);
        EXPECT_EQ(w->addDirectory("/abs", kTime, 0), Status::IoError);
        // A rejected path does not break the archive.
        EXPECT_EQ(w->addDirectory("fine", kTime, 0), Status::Ok);
        EXPECT_EQ(w->finish(), Status::Ok);
        const auto r = readAll(dir.file("b"), f);
        ASSERT_EQ(r.entries.size(), 1u);
        EXPECT_EQ(r.entries[0].entry.path, "fine");
    }
}

TEST(ArchiveWriter, FullAndReadOnlyOutputs) {
    std::string error;
    const std::string data = sampleData(1024 * 1024, 4);
    if (::access("/dev/full", W_OK) == 0) {
        for (Format f : {Format::Zip, Format::Tar}) {
            auto w = Writer::open(::open("/dev/full", O_WRONLY | O_CLOEXEC), f, WriterOptions{0}, &error);
            ASSERT_TRUE(w) << error;
            const int in = memFd(data);
            EXPECT_EQ(w->addFile("x", in, data.size(), kTime, 0, {}), Status::NoSpace);
            ::close(in);
        }
    }
    ScratchDir dir;
    writeFile(dir.file("ro"), "");
    auto w = Writer::open(::open(dir.file("ro").c_str(), O_RDONLY | O_CLOEXEC), Format::Tar, {}, &error);
    ASSERT_TRUE(w);
    const int in = memFd("small");
    EXPECT_EQ(w->addFile("x", in, 5, kTime, 0, {}), Status::Ok);  // still buffered
    ::close(in);
    EXPECT_EQ(w->finish(), Status::IoError);
    // Extracting into a read-only fd fails the same way.
    ASSERT_EQ(writeArchive(dir.file("a.zip"), Format::Zip, {{"a", false, "abc"}}), Status::Ok);
    Status status;
    auto reader = Reader::open(::open(dir.file("a.zip").c_str(), O_RDONLY | O_CLOEXEC), Format::Zip, {}, &status, &error);
    Entry e;
    ASSERT_EQ(reader->next(e), Status::Ok);
    const int ro = ::open(dir.file("ro").c_str(), O_RDONLY | O_CLOEXEC);
    EXPECT_EQ(reader->extract(ro, {}), Status::IoError);
    ::close(ro);
    EXPECT_EQ(reader->extract(-1, {}), Status::Ok);  // a write error does not close the reader
}

TEST(ArchiveDetect, MagicAndSeekBack) {
    ScratchDir dir;
    ASSERT_EQ(writeArchive(dir.file("d.zip"), Format::Zip, {{"a", false, "b"}}), Status::Ok);
    ASSERT_EQ(writeArchive(dir.file("d.tar"), Format::Tar, {{"a", false, "b"}}), Status::Ok);
    ASSERT_EQ(writeArchive(dir.file("d.tgz"), Format::TarGz, {{"a", false, "b"}}), Status::Ok);
    writeFile(dir.file("d.txt"), "just some text that is not an archive");
    struct {
        const char* name;
        Format format;
    } cases[] = {{"d.zip", Format::Zip}, {"d.tar", Format::Tar}, {"d.tgz", Format::TarGz}, {"d.txt", Format::Unknown}};
    for (const auto& c : cases) {
        const int fd = ::open(dir.file(c.name).c_str(), O_RDONLY | O_CLOEXEC);
        EXPECT_EQ(lx::archive::detect(fd), c.format) << c.name;
        EXPECT_EQ(lseek(fd, 0, SEEK_CUR), 0) << c.name;
        ::close(fd);
    }
    for (const auto& [bytes, format] : {std::pair{std::string("PK\x07\x08PK\x03\x04", 8), Format::Zip},
                                        std::pair{std::string(), Format::Unknown}}) {
        const int fd = memFd(bytes);
        EXPECT_EQ(lx::archive::detect(fd), format);
        ::close(fd);
    }
    EXPECT_EQ(lx::archive::detect(-1), Format::Unknown);
}

TEST(ArchiveTruncation, NeverReturnsWrongData) {
    ScratchDir dir;
    const std::vector<Item> items = {{"d", true, ""},
                                     {"d/a.txt", false, sampleData(3000, 1)},
                                     {"b.bin", false, sampleData(70000, 2)},
                                     {"c.txt", false, "tail"}};
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        SCOPED_TRACE(static_cast<int>(f));
        const std::string file = dir.file("t");
        ASSERT_EQ(writeArchive(file, f, items), Status::Ok);
        const std::string full = readFile(file);
        for (size_t cut = 0; cut < full.size(); cut += (cut < 2048 ? 7 : 211)) {
            const auto r = readBytes(full.substr(0, cut), f);
            bool cleanFull = r.last == Status::End && r.entries.size() == items.size();
            for (size_t i = 0; i < r.entries.size(); ++i) {
                const ReadEntry& e = r.entries[i];
                if (e.entry.type == EntryType::File && e.extracted == Status::Ok) {
                    EXPECT_EQ(e.data, items[i].data) << "cut at " << cut;
                } else if (e.entry.type == EntryType::File) {
                    cleanFull = false;
                }
            }
            // Every ZIP truncation loses the end record.
            if (f == Format::Zip) EXPECT_FALSE(cleanFull) << "cut at " << cut;
        }
    }
}

TEST(ArchiveInterop, PythonAndSystemToolsReadOurArchives) {
    if (std::system("python3 -c 'import zipfile, tarfile' >/dev/null 2>&1") != 0) GTEST_SKIP() << "python3 not installed";
    ScratchDir dir;
    auto items = sampleItems();
    items.pop_back();  // 3 MiB is enough in the round trip test
    items.push_back({"big.bin", false, sampleData(2 * 1024 * 1024 + 7, 21), kTime, 0});
    const std::string script = std::string(LX_TEST_DATA_DIR) + "/archives/verify_interop.py";
    std::string expected;
    for (const Item& it : items) {
        char line[64];
        if (it.dir)
            std::snprintf(line, sizeof line, "d 0 00000000 ");
        else
            std::snprintf(line, sizeof line, "f %zu %08x ", it.data.size(), crcOf(it.data));
        expected += line + it.path + "\n";
    }
    // java.util.zip.ZipInputStream is the strictest streaming reader around.
    const bool haveJava = std::system("java -version >/dev/null 2>&1") == 0;
    const std::string javaCheck = std::string(LX_TEST_DATA_DIR) + "/archives/ZipStreamCheck.java";
    struct {
        Format format;
        const char* kind;
        WriterOptions options;
        const char* tool;
        bool zipInputStream;
    } cases[] = {
        {Format::Zip, "zip", WriterOptions{6}, "unzip -tq", true},
        {Format::Zip, "zip", WriterOptions{0}, "unzip -tq", true},
        // Every file in the ZIP64 zone below the 32-bit limit: stored with its CRC up front.
        {Format::Zip, "zip", WriterOptions{6, 0}, "unzip -tq", true},
        // ZIP64 everywhere, 8-byte descriptors on tiny entries: a test-only layout
        // that ZipInputStream rejects by design, so only the other readers check it.
        {Format::Zip, "zip", WriterOptions{6, 0, 0, 0}, "unzip -tq", false},
        {Format::Tar, "tar", WriterOptions{}, "tar -tf", false},
        {Format::TarGz, "tgz", WriterOptions{}, "tar -tzf", false},
    };
    for (const auto& c : cases) {
        SCOPED_TRACE(std::string(c.kind) + " level " + std::to_string(c.options.level) + " zip64 entry size " +
                     std::to_string(c.options.zip64EntrySize));
        const std::string file = dir.file(std::string("interop.") + c.kind);
        ASSERT_EQ(writeArchive(file, c.format, items, c.options), Status::Ok);
        int status = 0;
        std::string listing = runCommand("python3 -I '" + script + "' " + c.kind + " '" + file + "'", &status);
        EXPECT_EQ(status, 0);
        EXPECT_EQ(listing, expected);
        if (c.zipInputStream && haveJava) {
            listing = runCommand("java '" + javaCheck + "' '" + file + "' 2>&1", &status);
            EXPECT_EQ(status, 0) << listing;
            EXPECT_EQ(listing, expected) << "ZipInputStream";
        }
        const std::string tool = std::string(c.tool).substr(0, std::string(c.tool).find(' '));
        if (std::system(("command -v " + tool + " >/dev/null 2>&1").c_str()) == 0) {
            EXPECT_EQ(std::system((std::string(c.tool) + " '" + file + "' >/dev/null").c_str()), 0) << c.tool;
        }
    }
}
