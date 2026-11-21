// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Mutated ZIP, TAR and TAR.GZ archives must never crash the reader, never
// produce more than the limits allow and never pass a path that escapes.
// Fixed seeds; LX_FUZZ_ITERATIONS raises the iteration count.
#include <gtest/gtest.h>

#include <cctype>
#include <cstdlib>
#include <random>
#include <string>

#include "../unit/archive_test_util.hpp"

using namespace lxtest;

namespace {

int iterations(int fallback) {
    const char* env = std::getenv("LX_FUZZ_ITERATIONS");
    return env ? std::max(1, std::atoi(env)) : fallback;
}

std::string mutate(std::string s, std::mt19937& rng) {
    const int edits = 1 + static_cast<int>(rng() % 6);
    for (int i = 0; i < edits && !s.empty(); ++i) {
        const size_t pos = rng() % s.size();
        switch (rng() % 6) {
            case 0: s[pos] = static_cast<char>(rng()); break;
            case 1: s[pos] = static_cast<char>(s[pos] ^ (1 << (rng() % 8))); break;
            case 2: s.erase(pos, 1 + rng() % 16); break;
            case 3: s.insert(pos, s.substr(rng() % s.size(), rng() % 64)); break;
            case 4: s.resize(pos); break;
            default: {
                // Large values in length fields are where parsers break.
                const char big[] = {'\xff', '\xff', '\xff', '\x7f'};
                s.replace(pos, std::min<size_t>(4, s.size() - pos), big, 4);
                break;
            }
        }
    }
    return s;
}

Limits fuzzLimits() {
    Limits l;
    l.maxEntries = 64;
    l.maxTotalBytes = 4 * 1024 * 1024;
    l.maxEntryBytes = 2 * 1024 * 1024;
    l.maxRatioX100 = 200 * 100;
    l.ratioGraceBytes = 64 * 1024;
    return l;
}

void checkResult(const ReadResult& r, const Limits& l) {
    ASSERT_LE(r.entries.size(), l.maxEntries);
    uint64_t total = 0;
    for (const ReadEntry& e : r.entries) {
        ASSERT_LE(e.data.size(), l.maxEntryBytes);
        if (e.extracted == Status::Ok && e.entry.type == EntryType::File) ASSERT_EQ(e.data.size(), e.entry.size);
        total += e.data.size();
        if (e.entry.verdict == PathVerdict::Ok) {
            const std::string& p = e.entry.path;
            ASSERT_FALSE(p.empty());
            ASSERT_NE(p.front(), '/');
            ASSERT_EQ(("/" + p + "/").find("/../"), std::string::npos) << p;
            ASSERT_EQ(p.find('\\'), std::string::npos);
        } else {
            ASSERT_TRUE(e.entry.path.empty());
        }
    }
    ASSERT_LE(total, l.maxTotalBytes);
}

std::string seedArchive(Format f, int level) {
    ScratchDir dir;
    const std::string file = dir.file("seed");
    WriterOptions opts;
    opts.level = level;
    const std::vector<Item> items = {{"dir", true, ""},
                                     {"dir/a.txt", false, "hello archive fuzzing\n"},
                                     {"zeros.bin", false, std::string(60000, '\0')},
                                     {"mixed.bin", false, sampleData(9000, 17)},
                                     {"\xC3\xA9/" + std::string(140, 'l') + ".txt", false, "long"},
                                     {"empty", false, ""}};
    EXPECT_EQ(writeArchive(file, f, items, opts), Status::Ok);
    return readFile(file);
}

void fuzz(const std::string& seed, Format f, unsigned rngSeed, int count) {
    std::mt19937 rng(rngSeed);
    const Limits l = fuzzLimits();
    for (int i = 0; i < count; ++i) {
        const std::string input = mutate(seed, rng);
        const ReadResult r = readBytes(input, f, l);
        checkResult(r, l);
        if (::testing::Test::HasFatalFailure()) {
            ADD_FAILURE() << "iteration " << i;
            return;
        }
    }
}

}  // namespace

TEST(ArchiveFuzz, MutatedZip) {
    fuzz(seedArchive(Format::Zip, 6), Format::Zip, 101, iterations(300));
    fuzz(seedArchive(Format::Zip, 0), Format::Zip, 102, iterations(300));
}

TEST(ArchiveFuzz, MutatedTar) {
    fuzz(seedArchive(Format::Tar, 0), Format::Tar, 201, iterations(300));
    fuzz(readFile(fixture("gnu.tar")), Format::Tar, 202, iterations(200));
    fuzz(readFile(fixture("pax.tar")), Format::Tar, 203, iterations(200));
}

TEST(ArchiveFuzz, MutatedTarGz) {
    fuzz(seedArchive(Format::TarGz, 6), Format::TarGz, 301, iterations(300));
    fuzz(readFile(fixture("concat.tar.gz")), Format::TarGz, 302, iterations(200));
}

TEST(ArchiveFuzz, RandomBytesAsAnyFormat) {
    std::mt19937 rng(401);
    const Limits l = fuzzLimits();
    for (int i = 0; i < iterations(300); ++i) {
        std::string s(rng() % 4096, '\0');
        for (auto& c : s) c = static_cast<char>(rng());
        if (i % 3 == 0 && s.size() > 4) s.replace(0, 4, std::string("PK\x03\x04", 4));
        for (Format f : {Format::Zip, Format::Tar, Format::TarGz, Format::Unknown}) {
            checkResult(readBytes(s, f, l), l);
            if (HasFatalFailure()) return;
        }
    }
}

TEST(ArchiveFuzz, CheckPathNeverPassesAnEscape) {
    std::mt19937 rng(501);
    const char alphabet[] = {'a', '.', '/', '\\', ':', 'C', '\0', '\n', ' ', '\xc3', '\xa9', '\xff', '\xed', '\xa0', '*'};
    for (int i = 0; i < iterations(20000); ++i) {
        std::string raw(rng() % 24, 'a');
        for (auto& c : raw) c = alphabet[rng() % sizeof alphabet];
        for (bool fat : {false, true}) {
            std::string out;
            if (lx::archive::checkPath(raw, fat, &out) != PathVerdict::Ok) {
                ASSERT_TRUE(out.empty());
                continue;
            }
            ASSERT_FALSE(out.empty());
            ASSERT_NE(out.front(), '/');
            ASSERT_NE(out.back(), '/');
            ASSERT_EQ(out.find('\\'), std::string::npos);
            ASSERT_EQ(out.find("//"), std::string::npos);
            ASSERT_EQ(("/" + out + "/").find("/../"), std::string::npos) << raw;
            ASSERT_EQ(("/" + out + "/").find("/./"), std::string::npos) << raw;
            ASSERT_TRUE(lx::archive::isValidUtf8(out));
            const bool drive = out.size() >= 2 && out[1] == ':' && std::isalpha(static_cast<unsigned char>(out[0]));
            ASSERT_FALSE(drive) << out;
            for (char c : out) ASSERT_GE(static_cast<uint8_t>(c), 0x20);
            if (fat) ASSERT_EQ(out.find_first_of("\"*:<>?|"), std::string::npos);
        }
    }
}
