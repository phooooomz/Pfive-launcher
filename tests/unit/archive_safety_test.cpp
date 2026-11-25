// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Archive behaviour that keeps damaged or hostile input from doing harm, and
// writer output that strict streaming readers accept: gzip trailers, shared fd
// offsets, central directory order, ratio bombs split over many entries, link
// entries, tar parser differences and ZIP64 near 4 GiB.
#include <gtest/gtest.h>
#include <zlib.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <iterator>
#include <numeric>
#include <random>
#include <string>
#include <vector>

#include "archive_test_util.hpp"

using namespace lxtest;
using lx::archive::Reader;
using lx::archive::Writer;

namespace {

constexpr int64_t kTime = 1700000000000;
constexpr size_t kMiB = 1024 * 1024;

void put16(std::string& o, uint32_t v) {
    o.push_back(static_cast<char>(v & 0xFF));
    o.push_back(static_cast<char>((v >> 8) & 0xFF));
}

void put32(std::string& o, uint32_t v) {
    put16(o, v & 0xFFFF);
    put16(o, v >> 16);
}

uint32_t byteAt(const std::string& s, size_t pos) { return static_cast<uint8_t>(s[pos]); }
uint32_t le16At(const std::string& s, size_t pos) { return byteAt(s, pos) | (byteAt(s, pos + 1) << 8); }
uint32_t le32At(const std::string& s, size_t pos) { return le16At(s, pos) | (le16At(s, pos + 2) << 16); }

void putAt(std::string& s, size_t pos, uint64_t v, int bytes) {
    for (int i = 0; i < bytes; ++i) s[pos + static_cast<size_t>(i)] = static_cast<char>((v >> (8 * i)) & 0xFF);
}

uint32_t crcOf(const std::string& s) {
    return static_cast<uint32_t>(crc32(0, reinterpret_cast<const Bytef*>(s.data()), static_cast<uInt>(s.size())));
}

std::vector<size_t> findAll(const std::string& s, const std::string& needle) {
    std::vector<size_t> out;
    for (size_t p = s.find(needle); p != std::string::npos; p = s.find(needle, p + 1)) out.push_back(p);
    return out;
}

const std::string kCentralSig("PK\x01\x02", 4);
const std::string kLocalSig("PK\x03\x04", 4);
const std::string kDescriptorSig("PK\x07\x08", 4);

// A stored ZIP of empty files built by hand; the central directory lists them in the given order.
std::string emptyEntriesZip(const std::vector<size_t>& order) {
    std::string out;
    std::vector<uint32_t> offsets;
    for (size_t i = 0; i < order.size(); ++i) {
        const std::string name = "f" + std::to_string(i);
        offsets.push_back(static_cast<uint32_t>(out.size()));
        put32(out, 0x04034b50);
        put16(out, 20);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0x21);
        put32(out, 0);
        put32(out, 0);
        put32(out, 0);
        put16(out, static_cast<uint32_t>(name.size()));
        put16(out, 0);
        out += name;
    }
    const auto cdStart = static_cast<uint32_t>(out.size());
    for (size_t i : order) {
        const std::string name = "f" + std::to_string(i);
        put32(out, 0x02014b50);
        put16(out, 20);
        put16(out, 20);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0x21);
        put32(out, 0);
        put32(out, 0);
        put32(out, 0);
        put16(out, static_cast<uint32_t>(name.size()));
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put16(out, 0);
        put32(out, 0);
        put32(out, offsets[i]);
        out += name;
    }
    const uint32_t cdSize = static_cast<uint32_t>(out.size()) - cdStart;
    put32(out, 0x06054b50);
    put16(out, 0);
    put16(out, 0);
    put16(out, static_cast<uint32_t>(order.size()));
    put16(out, static_cast<uint32_t>(order.size()));
    put32(out, cdSize);
    put32(out, cdStart);
    put16(out, 0);
    return out;
}

// Lists an archive without extracting; returns the seconds it took.
double listOnly(const std::string& bytes, Format format, size_t* count, Status* last, const Limits& limits = {}) {
    const auto t0 = std::chrono::steady_clock::now();
    Status status;
    std::string error;
    auto reader = Reader::open(memFd(bytes), format, limits, &status, &error);
    *count = 0;
    *last = status;
    if (reader) {
        Entry e;
        while ((*last = reader->next(e)) == Status::Ok) ++*count;
    }
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}

// The ustar name field of the first header that is not a PAX 'x' header.
std::string ustarNameAfterPax(const std::string& tar) {
    size_t pos = 0;
    while (pos + 512 <= tar.size() && tar[pos + 156] == 'x') {
        const uint64_t size = std::stoull(std::string(tar.data() + pos + 124, 11), nullptr, 8);
        pos += 512 + (size + 511) / 512 * 512;
    }
    return std::string(tar.data() + pos, strnlen(tar.data() + pos, 100));
}

}  // namespace

TEST(ArchiveGzip, TrailerIsCheckedAtTheEnd) {
    ScratchDir dir;
    const std::string payload(5000, 'A');
    // Level 0 leaves the data readable inside the gzip stream, so one byte can be changed.
    WriterOptions stored;
    stored.level = 0;
    ASSERT_EQ(writeArchive(dir.file("a.tgz"), Format::TarGz, {{"a.txt", false, payload}, {"b.txt", false, "second"}},
                           stored),
              Status::Ok);
    const std::string clean = readFile(dir.file("a.tgz"));
    auto r = readBytes(clean, Format::TarGz);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 2u);

    std::string flipped = clean;
    const size_t at = flipped.find(std::string(64, 'A'));
    ASSERT_NE(at, std::string::npos);
    flipped[at + 10] = 'B';
    r = readBytes(flipped, Format::TarGz);
    ASSERT_EQ(r.entries.size(), 2u);
    // Only the trailer can tell; the archive is not intact until next() says End.
    EXPECT_EQ(r.entries[0].extracted, Status::Ok);
    EXPECT_NE(r.entries[0].data, payload);
    EXPECT_EQ(r.last, Status::CrcError) << r.error;

    // The trailer's CRC-32 and length fields.
    for (size_t back : {8u, 4u}) {
        std::string bad = clean;
        bad[bad.size() - back] = static_cast<char>(bad[bad.size() - back] ^ 1);
        EXPECT_EQ(readBytes(bad, Format::TarGz).last, Status::CrcError) << back;
    }
    // A stream cut inside the trailer ends early.
    EXPECT_EQ(readBytes(clean.substr(0, clean.size() - 3), Format::TarGz).last, Status::Corrupt);
    // What follows the member with the end blocks is not inflated, even when it looks like gzip.
    r = readBytes(clean + std::string("\x1F\x8B" "not a member", 14), Format::TarGz);
    EXPECT_EQ(r.last, Status::End) << r.error;
    EXPECT_EQ(r.entries.size(), 2u);

    // The default level, with the writer's end blocks and record padding in the stream.
    ASSERT_EQ(writeArchive(dir.file("b.tgz"), Format::TarGz, {{"a.txt", false, payload}}), Status::Ok);
    std::string six = readFile(dir.file("b.tgz"));
    six[six.size() - 8] = static_cast<char>(six[six.size() - 8] ^ 1);
    EXPECT_EQ(readBytes(six, Format::TarGz).last, Status::CrcError);
}

TEST(ArchiveFd, ReadersLeaveTheCallersOffsetAlone) {
    ScratchDir dir;
    const std::vector<Item> items = {{"a.txt", false, "alpha"}, {"b.txt", false, sampleData(200000, 6)}};
    for (Format f : {Format::Zip, Format::Tar, Format::TarGz}) {
        SCOPED_TRACE(static_cast<int>(f));
        const std::string file = dir.file("same");
        ASSERT_EQ(writeArchive(file, f, items), Status::Ok);
        const int caller = ::open(file.c_str(), O_RDONLY | O_CLOEXEC);
        // What the JNI layer does: each reader gets a dup, which shares the offset.
        for (Format asked : {f, f, Format::Unknown}) {
            const auto r = readAll(fcntl(caller, F_DUPFD_CLOEXEC, 0), asked);
            EXPECT_EQ(r.last, Status::End) << r.error;
            ASSERT_EQ(r.entries.size(), 2u);
            EXPECT_EQ(r.entries[1].data, items[1].data);
            EXPECT_EQ(lseek(caller, 0, SEEK_CUR), 0);
        }
        EXPECT_EQ(lx::archive::detect(caller), f);
        EXPECT_EQ(lseek(caller, 0, SEEK_CUR), 0);
        ::close(caller);
    }

    // A TAR starts at the fd's offset, here partway into the file, read after read.
    ASSERT_EQ(writeArchive(dir.file("t.tar"), Format::Tar, items), Status::Ok);
    writeFile(dir.file("prefixed"), std::string(1536, 'x') + readFile(dir.file("t.tar")));
    const int caller = ::open(dir.file("prefixed").c_str(), O_RDONLY | O_CLOEXEC);
    ASSERT_EQ(lseek(caller, 1536, SEEK_SET), 1536);
    for (int pass = 0; pass < 2; ++pass) {
        const auto r = readAll(fcntl(caller, F_DUPFD_CLOEXEC, 0), Format::Tar);
        EXPECT_EQ(r.last, Status::End) << r.error;
        EXPECT_EQ(r.entries.size(), 2u);
        EXPECT_EQ(lseek(caller, 0, SEEK_CUR), 1536);
    }
    ::close(caller);
}

TEST(ArchiveZip, CentralDirectoryOrderDoesNotChangeTheCost) {
    constexpr size_t kCount = 65000;  // below 0xFFFF: the builder writes no ZIP64 end record
    std::vector<size_t> order(kCount);
    std::iota(order.begin(), order.end(), size_t{0});
    size_t count = 0;
    Status last;
    const double forward = listOnly(emptyEntriesZip(order), Format::Zip, &count, &last);
    EXPECT_EQ(last, Status::End);
    EXPECT_EQ(count, kCount);
    std::reverse(order.begin(), order.end());
    const double reversed = listOnly(emptyEntriesZip(order), Format::Zip, &count, &last);
    EXPECT_EQ(last, Status::End);
    EXPECT_EQ(count, kCount);
    // A sorted vector with inserts at the front took several times the forward
    // time at this size, and grew with the square of the count.
    EXPECT_LT(reversed, forward * 3 + 0.5) << "forward " << forward << " s, reversed " << reversed << " s";

    // Any order is accepted, and overlaps are still found out of order.
    std::vector<size_t> shuffled(5000);
    std::iota(shuffled.begin(), shuffled.end(), size_t{0});
    std::shuffle(shuffled.begin(), shuffled.end(), std::mt19937(8));
    listOnly(emptyEntriesZip(shuffled), Format::Zip, &count, &last);
    EXPECT_EQ(last, Status::End);
    EXPECT_EQ(count, shuffled.size());
    // The third record points at the local header of the first.
    std::string shared = emptyEntriesZip({3, 2, 1, 0});
    const auto central = findAll(shared, kCentralSig);
    ASSERT_EQ(central.size(), 4u);
    putAt(shared, central[2] + 42, le32At(shared, central[0] + 42), 4);
    listOnly(shared, Format::Zip, &count, &last);
    EXPECT_EQ(last, Status::Corrupt);
    EXPECT_EQ(count, 2u);
}

TEST(ArchiveTar, FileTypeWithTrailingSlashStillSkipsItsData) {
    // A header hidden in the data of a "folder" must not show up as an entry:
    // GNU tar and Python skip that data, so reading it would list files they do not.
    int status = 0;
    const bool gnuTar = runCommand("tar --version 2>/dev/null", &status).find("GNU tar") != std::string::npos;
    ScratchDir dir;
    for (char type : {'0', '\0', '7'}) {
        SCOPED_TRACE(static_cast<int>(type));
        const std::string hidden = tarHeader("hidden.txt", '0', octal(6)) + padded("secret");
        std::string tar = tarHeader("cover/", type, octal(hidden.size())) + hidden;
        tar += tarHeader("visible.txt", '0', octal(2)) + padded("ok") + std::string(1024, '\0');
        const auto r = readBytes(tar, Format::Tar);
        ASSERT_EQ(r.last, Status::End) << r.error;
        ASSERT_EQ(r.entries.size(), 2u);
        EXPECT_EQ(r.entries[0].entry.type, EntryType::Dir);
        EXPECT_EQ(r.entries[0].entry.path, "cover");
        EXPECT_EQ(r.entries[0].entry.size, 0u);
        EXPECT_EQ(r.entries[1].entry.path, "visible.txt");
        EXPECT_EQ(r.entries[1].data, "ok");
        if (gnuTar) {
            writeFile(dir.file("slash.tar"), tar);
            EXPECT_EQ(runCommand("tar -tf '" + dir.file("slash.tar") + "'", &status), "cover/\nvisible.txt\n");
        }
    }
}

TEST(ArchiveLimits, RatioCoversTheWholeArchive) {
    ScratchDir dir;
    // 32 entries of 1 MiB zeros: each one alone stays within the grace.
    std::vector<Item> bomb;
    for (int i = 0; i < 32; ++i) bomb.push_back({"z" + std::to_string(i), false, std::string(kMiB, '\0')});
    std::vector<Item> ordinary;
    for (unsigned i = 0; i < 6; ++i) ordinary.push_back({"m" + std::to_string(i), false, sampleData(kMiB / 2, i)});
    Limits l;
    l.maxRatioX100 = 100 * 100;
    l.ratioGraceBytes = kMiB;
    WriterOptions nine;
    nine.level = 9;
    for (Format f : {Format::Zip, Format::TarGz}) {
        SCOPED_TRACE(static_cast<int>(f));
        const std::string file = dir.file("bomb");
        ASSERT_EQ(writeArchive(file, f, bomb, nine), Status::Ok);
        auto r = readAll(file, f, l);
        EXPECT_EQ(r.last, Status::LimitExceeded);
        size_t total = 0;
        for (const auto& e : r.entries) total += e.data.size();
        EXPECT_LE(total, 3 * kMiB);
        if (f == Format::TarGz) {
            // Listing inflates the data it skips, so the limit holds there as well.
            size_t count = 0;
            Status last;
            listOnly(readFile(file), f, &count, &last, l);
            EXPECT_EQ(last, Status::LimitExceeded);
            EXPECT_LT(count, bomb.size());
        }
        // Ordinary data passes under the same limits.
        ASSERT_EQ(writeArchive(file, f, ordinary, nine), Status::Ok);
        r = readAll(file, f, l);
        EXPECT_EQ(r.last, Status::End) << r.error;
        EXPECT_EQ(r.entries.size(), ordinary.size());
    }
}

TEST(ArchiveTar, StandInNameForPaxPathsStaysInside) {
    ScratchDir dir;
    struct {
        std::string path;
        bool isDir;
        std::string standIn;
    } cases[] = {
        // Cutting the last 100 bytes of this path used to give "../ccc...".
        {std::string(200, 'p') + "x../" + std::string(97, 'c'), false, std::string(97, 'c')},
        {"a/" + std::string(200, 's'), false, std::string(100, 's')},
        {std::string(150, 'd') + "/" + std::string(120, 'e'), true, std::string(99, 'e') + "/"},
        {"\xC3\xA9t\xC3\xA9/" + std::string(60, 'u') + "/" + std::string(60, 'v'), false, std::string(60, 'v')},
    };
    for (const auto& c : cases) {
        SCOPED_TRACE(c.path.substr(0, 40));
        const std::string file = dir.file("n.tar");
        ASSERT_EQ(writeArchive(file, Format::Tar, {{c.path, c.isDir, ""}}), Status::Ok);
        const std::string name = ustarNameAfterPax(readFile(file));
        EXPECT_EQ(name, c.standIn);
        EXPECT_EQ(lx::archive::checkPath(name, false, nullptr), PathVerdict::Ok);
        const auto r = readAll(file, Format::Tar);
        ASSERT_EQ(r.entries.size(), 1u);
        EXPECT_EQ(r.entries[0].entry.path, c.path);
    }
}

TEST(ArchiveLinks, TarEntriesThroughOrOutOfLinksAreRefused) {
    std::string tar;
    tar += tarHeader("evil", '2', octal(0), true, "/data/data/com.lxlauncher");
    tar += tarHeader("evil/shared_prefs/settings.xml", '0', octal(4)) + padded("pwnd");
    tar += tarHeader("hl", '1', octal(0), true, "../../outside");
    tar += tarHeader("docs/v2/readme", '0', octal(2)) + padded("v2");
    tar += tarHeader("docs/latest", '2', octal(0), true, "v2");
    tar += tarHeader("docs/latest/readme", '0', octal(3)) + padded("new");
    tar += tarHeader("docs/up", '2', octal(0), true, "..");
    tar += tarHeader("loop", '2', octal(0), true, "docs/../..");
    tar += tarHeader("h2", '1', octal(0), true, "evil/x");
    tar += tarHeader("h3", '1', octal(0), true, "docs/v2/readme");
    tar += tarHeader("evil", '0', octal(1)) + padded("x");  // a file where a symlink was
    tar += tarHeader("ok.txt", '0', octal(2)) + padded("ok");
    tar += std::string(1024, '\0');
    const auto r = readBytes(tar, Format::Tar);
    ASSERT_EQ(r.last, Status::End) << r.error;
    const std::pair<const char*, PathVerdict> expected[] = {
        {"evil", PathVerdict::Absolute},          {"evil/shared_prefs/settings.xml", PathVerdict::DotDot},
        {"hl", PathVerdict::DotDot},              {"docs/v2/readme", PathVerdict::Ok},
        {"docs/latest", PathVerdict::Ok},         {"docs/latest/readme", PathVerdict::DotDot},
        {"docs/up", PathVerdict::Ok},             {"loop", PathVerdict::DotDot},
        {"h2", PathVerdict::DotDot},              {"h3", PathVerdict::Ok},
        {"evil", PathVerdict::DotDot},            {"ok.txt", PathVerdict::Ok},
    };
    ASSERT_EQ(r.entries.size(), std::size(expected));
    for (size_t i = 0; i < r.entries.size(); ++i) {
        const Entry& e = r.entries[i].entry;
        SCOPED_TRACE(e.raw);
        EXPECT_EQ(e.raw, expected[i].first);
        EXPECT_EQ(e.verdict, expected[i].second);
        EXPECT_EQ(e.path.empty(), e.verdict != PathVerdict::Ok);
    }
    EXPECT_EQ(r.entries[4].entry.link, "v2");
}

TEST(ArchiveLinks, ZipSymlinksFollowTheSameRules) {
    ScratchDir dir;
    const std::string file = dir.file("links.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip,
                           {{"link", false, "/etc/passwd"}, {"link/x", false, "y"}, {"rel", false, "sub/target"}}),
              Status::Ok);
    std::string bytes = readFile(file);
    const auto central = findAll(bytes, kCentralSig);
    ASSERT_EQ(central.size(), 3u);
    for (size_t i : {size_t{0}, size_t{2}}) putAt(bytes, central[i] + 38, uint64_t{0120777} << 16, 4);
    const auto r = readBytes(bytes, Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 3u);
    EXPECT_EQ(r.entries[0].entry.type, EntryType::Symlink);
    EXPECT_EQ(r.entries[0].entry.link, "/etc/passwd");
    EXPECT_EQ(r.entries[0].entry.verdict, PathVerdict::Absolute);
    EXPECT_EQ(r.entries[1].entry.verdict, PathVerdict::DotDot);
    EXPECT_EQ(r.entries[2].entry.type, EntryType::Symlink);
    EXPECT_EQ(r.entries[2].entry.verdict, PathVerdict::Ok);
    EXPECT_EQ(r.entries[2].entry.link, "sub/target");
}

TEST(ArchiveZip, SizesThatDoNotFitInt64AreCorrupt) {
    ScratchDir dir;
    const std::string file = dir.file("big.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a.txt", false, "data"}}, WriterOptions{6, 0, 0, 0}), Status::Ok);
    std::string bytes = readFile(file);
    const size_t central = bytes.find(kCentralSig);
    ASSERT_NE(central, std::string::npos);
    const size_t extra = central + 46 + le16At(bytes, central + 28);
    ASSERT_EQ(le16At(bytes, extra), 1u);  // ZIP64 extra, uncompressed size first
    putAt(bytes, extra + 4, uint64_t{1} << 63, 8);
    const auto r = readBytes(bytes, Format::Zip);
    EXPECT_EQ(r.last, Status::Corrupt);
    EXPECT_TRUE(r.entries.empty());
}

TEST(ArchiveZip, LevelZeroStreamsAsDeflateStoredBlocks) {
    ScratchDir dir;
    const std::string file = dir.file("l0.zip");
    ASSERT_EQ(writeArchive(file, Format::Zip, {{"a.txt", false, "plain stored text"}}, WriterOptions{0}), Status::Ok);
    const std::string bytes = readFile(file);
    EXPECT_EQ(le16At(bytes, 8), 8u);         // method: deflate
    EXPECT_EQ(le16At(bytes, 6) & 8, 8u);     // data descriptor
    EXPECT_NE(bytes.find("plain stored text"), std::string::npos);  // not compressed
    const auto r = readAll(file, Format::Zip);
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].data, "plain stored text");
}

TEST(ArchiveZip, BigEntryBelowTheLimitIsStoredWithItsCrcUpFront) {
    ScratchDir dir;
    // Every file counts as big; the 32-bit limit stays where it is.
    WriterOptions zone;
    zone.zip64EntrySize = 0;
    const std::string data = sampleData(300000, 8);
    const std::string file = dir.file("zone.zip");
    const std::vector<Item> items = {{"d", true, ""}, {"a.bin", false, data}, {"empty", false, ""}};
    ASSERT_EQ(writeArchive(file, Format::Zip, items, zone), Status::Ok);
    const std::string bytes = readFile(file);
    EXPECT_TRUE(findAll(bytes, kDescriptorSig).empty());
    const auto locals = findAll(bytes, kLocalSig);
    ASSERT_GE(locals.size(), 3u);
    const size_t a = locals[1];
    EXPECT_EQ(le16At(bytes, a + 6) & 8, 0u);  // no data descriptor
    EXPECT_EQ(le16At(bytes, a + 8), 0u);      // stored
    EXPECT_EQ(le32At(bytes, a + 14), crcOf(data));
    EXPECT_EQ(le32At(bytes, a + 18), data.size());
    EXPECT_EQ(le32At(bytes, a + 22), data.size());
    const auto r = readAll(file, Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 3u);
    EXPECT_EQ(r.entries[1].data, data);

    // A pipe cannot be read twice, so the entry falls back to deflate with ZIP64 records.
    const std::string small = sampleData(30000, 9);  // fits in the pipe buffer
    int p[2];
    ASSERT_EQ(pipe(p), 0);
    ASSERT_EQ(::write(p[1], small.data(), small.size()), static_cast<ssize_t>(small.size()));
    ::close(p[1]);
    std::string error;
    auto w = Writer::open(::open(dir.file("pipe.zip").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644),
                          Format::Zip, zone, &error);
    ASSERT_TRUE(w) << error;
    EXPECT_EQ(w->addFile("p.bin", p[0], small.size(), kTime, 0, {}), Status::Ok) << w->lastError();
    ::close(p[0]);
    EXPECT_EQ(w->finish(), Status::Ok);
    const std::string piped = readFile(dir.file("pipe.zip"));
    EXPECT_EQ(le16At(piped, 4), 45u);       // ZIP64 version needed
    EXPECT_EQ(le16At(piped, 6) & 8, 8u);    // data descriptor
    EXPECT_EQ(le16At(piped, 8), 8u);        // deflate
    const auto back = readAll(dir.file("pipe.zip"), Format::Zip);
    ASSERT_EQ(back.entries.size(), 1u);
    EXPECT_EQ(back.entries[0].data, small);
}

TEST(ArchiveZip, StoredPassNoticesChangesAndFailsCleanly) {
    ScratchDir dir;
    WriterOptions zone;
    zone.zip64EntrySize = 0;
    const std::string data = sampleData(2 * kMiB + 5, 3);
    std::string error;
    {
        // The first pass reports 0, which is when the source gets changed behind its back.
        auto w = Writer::open(::open(dir.file("c.zip").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644),
                              Format::Zip, zone, &error);
        const int src = memFd(data);
        bool changed = false;
        const Status s = w->addFile("a.bin", src, data.size(), kTime, 0, [&](uint64_t n) {
            if (n == 0 && !changed) {
                changed = true;
                const char x = static_cast<char>(data[0] ^ 1);
                EXPECT_EQ(pwrite(src, &x, 1, 0), 1);
            }
            return true;
        });
        ::close(src);
        EXPECT_TRUE(changed);
        EXPECT_EQ(s, Status::IoError);
        EXPECT_NE(w->lastError().find("changed"), std::string::npos) << w->lastError();
    }
    // A short source or a cancel in the first pass has written nothing, so the archive goes on.
    auto w = Writer::open(::open(dir.file("ok.zip").c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0644),
                          Format::Zip, zone, &error);
    int src = memFd("12345");
    EXPECT_EQ(w->addFile("short", src, 10, kTime, 0, {}), Status::IoError);
    ::close(src);
    src = memFd(data);
    EXPECT_EQ(w->addFile("cancelled", src, data.size(), kTime, 0, [](uint64_t) { return false; }), Status::Cancelled);
    ::close(src);
    src = memFd("abc");
    EXPECT_EQ(w->addFile("good", src, 3, kTime, 0, {}), Status::Ok);
    ::close(src);
    EXPECT_EQ(w->finish(), Status::Ok);
    const auto r = readAll(dir.file("ok.zip"), Format::Zip);
    ASSERT_EQ(r.last, Status::End) << r.error;
    ASSERT_EQ(r.entries.size(), 1u);
    EXPECT_EQ(r.entries[0].entry.path, "good");
    EXPECT_EQ(r.entries[0].data, "abc");
}

TEST(ArchiveTar, SkippingWithProgressThroughExtract) {
    // next() skips TAR.GZ data without progress calls; extract(-1, progress)
    // is the way to skip with them, and to stop.
    ScratchDir dir;
    const std::string data = sampleData(3 * kMiB, 12);
    ASSERT_EQ(writeArchive(dir.file("s.tgz"), Format::TarGz, {{"big", false, data}, {"after", false, "x"}}),
              Status::Ok);
    Status status;
    std::string error;
    auto reader = Reader::open(::open(dir.file("s.tgz").c_str(), O_RDONLY | O_CLOEXEC), Format::TarGz, {}, &status,
                               &error);
    ASSERT_TRUE(reader);
    Entry e;
    ASSERT_EQ(reader->next(e), Status::Ok);
    int calls = 0;
    EXPECT_EQ(reader->extract(-1, [&](uint64_t) { return ++calls < 2; }), Status::Cancelled);
    EXPECT_EQ(calls, 2);
    ASSERT_EQ(reader->next(e), Status::Ok);
    EXPECT_EQ(e.raw, "after");
    EXPECT_EQ(reader->next(e), Status::End);
}

