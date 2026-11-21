// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>

#include <unistd.h>

#include "lx/persistence.hpp"

using namespace lx;

namespace {

class TempDir {
public:
    TempDir() {
        char tmpl[] = "/tmp/lxstoreXXXXXX";
        const char* base = std::getenv("LX_TEST_TMP");
        std::string t = base ? std::string(base) + "/lxstoreXXXXXX" : std::string(tmpl);
        path_ = mkdtemp(t.data());
    }
    ~TempDir() {
        std::string cmd = "rm -rf '" + path_ + "'";
        if (std::system(cmd.c_str()) != 0) std::perror("cleanup");
    }
    std::string file(const std::string& name) const { return path_ + "/" + name; }

private:
    std::string path_;
};

void writeRaw(const std::string& path, const std::string& data) {
    std::ofstream(path, std::ios::binary) << data;
}

}  // namespace

TEST(Crc32, KnownVector) {
    EXPECT_EQ(crc32("123456789"), 0xCBF43926u);
    EXPECT_EQ(crc32(""), 0u);
}

TEST(Envelope, RoundTripAndTamperDetection) {
    std::string wrapped = wrapEnvelope("{\"a\":1}");
    auto back = unwrapEnvelope(wrapped);
    ASSERT_TRUE(back);
    EXPECT_EQ(*back, "{\"a\":1}");
    std::string tampered = wrapped;
    tampered.back() = '2';
    EXPECT_FALSE(unwrapEnvelope(tampered));
    EXPECT_FALSE(unwrapEnvelope(wrapped.substr(0, wrapped.size() - 1)));
    EXPECT_FALSE(unwrapEnvelope("garbage"));
    EXPECT_FALSE(unwrapEnvelope("LXL1 zzzzzzzz 3\nabc"));
}

TEST(Store, FreshDirectoryLoadsNothing) {
    TempDir dir;
    Store store(dir.file("layout.lxl"));
    auto r = store.load();
    EXPECT_EQ(r.source, LoadSource::None);
    EXPECT_FALSE(r.mainCorrupt);
}

TEST(Store, SaveThenLoad) {
    TempDir dir;
    Store store(dir.file("layout.lxl"));
    ASSERT_TRUE(store.save("first"));
    ASSERT_TRUE(store.save("second"));
    auto r = store.load();
    EXPECT_EQ(r.source, LoadSource::Main);
    EXPECT_EQ(r.payload, "second");
    // The previous version is kept as backup.
    auto bak = readFile(dir.file("layout.lxl.bak"), 1024);
    ASSERT_TRUE(bak);
    EXPECT_EQ(*unwrapEnvelope(*bak), "first");
}

TEST(Store, CorruptMainFallsBackToBackup) {
    TempDir dir;
    Store store(dir.file("layout.lxl"));
    ASSERT_TRUE(store.save("good"));
    ASSERT_TRUE(store.save("newer"));
    writeRaw(dir.file("layout.lxl"), "LXL1 00000000 5\nnewer");
    auto r = store.load();
    EXPECT_TRUE(r.mainCorrupt);
    EXPECT_EQ(r.source, LoadSource::Backup);
    EXPECT_EQ(r.payload, "good");
    store.quarantineMain();
    EXPECT_TRUE(readFile(dir.file("layout.lxl.corrupt"), 1024).has_value());
}

TEST(Store, CrashBetweenRenamesLeavesBackup) {
    TempDir dir;
    Store store(dir.file("layout.lxl"));
    ASSERT_TRUE(store.save("only"));
    // Simulate the window after main -> bak and before tmp -> main.
    ASSERT_EQ(std::rename(dir.file("layout.lxl").c_str(), dir.file("layout.lxl.bak").c_str()), 0);
    auto r = store.load();
    EXPECT_EQ(r.source, LoadSource::Backup);
    EXPECT_EQ(r.payload, "only");
}

TEST(Store, CorruptFileIsNotRotatedIntoBackup) {
    TempDir dir;
    Store store(dir.file("layout.lxl"));
    ASSERT_TRUE(store.save("good"));
    ASSERT_TRUE(store.save("good2"));
    writeRaw(dir.file("layout.lxl"), "broken");
    ASSERT_TRUE(store.save("good3"));
    auto bak = readFile(dir.file("layout.lxl.bak"), 1024);
    ASSERT_TRUE(bak);
    EXPECT_EQ(*unwrapEnvelope(*bak), "good");
}

TEST(Store, RejectsOversizedFiles) {
    TempDir dir;
    std::string path = dir.file("big");
    writeRaw(path, std::string(2048, 'x'));
    EXPECT_FALSE(readFile(path, 1024).has_value());
    EXPECT_TRUE(readFile(path, 4096).has_value());
}

TEST(Store, SaveFailsCleanlyOnMissingDirectory) {
    Store store("/nonexistent-lx-dir/layout.lxl");
    EXPECT_FALSE(store.save("x"));
}
