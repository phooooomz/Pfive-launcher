// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "dex.hpp"

using namespace lxdb;

namespace {

constexpr const char* kStub = "Llx/fixture/IThing$Stub;";
constexpr const char* kThing = "Llx/fixture/IThing;";

std::string fixture(const char* name) { return std::string(LX_TEST_DATA_DIR) + "/lxdb/" + name; }

std::vector<uint8_t> bytesOf(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

void expectFixture(const Jar& jar) {
    EXPECT_EQ(jar.staticInt(kStub, "TRANSACTION_call"), 7);
    EXPECT_EQ(jar.staticInt(kStub, "TRANSACTION_over"), 300);
    EXPECT_EQ(jar.staticInt(kStub, "TRANSACTION_negative"), -2);
    EXPECT_EQ(jar.staticInt(kStub, "TRANSACTION_large"), 0x12345678);
    EXPECT_FALSE(jar.staticInt(kStub, "TRANSACTION_absent").has_value());
    EXPECT_FALSE(jar.staticInt(kStub, "LABEL").has_value());
    EXPECT_FALSE(jar.staticInt("Llx/fixture/IAbsent$Stub;", "TRANSACTION_call").has_value());
    const auto call = jar.methodShapes(kThing, "call");
    ASSERT_EQ(call.size(), 1u);
    EXPECT_EQ(call[0], "(Ljava/lang/String;I)I");
    auto over = jar.methodShapes(kThing, "over");
    ASSERT_EQ(over.size(), 2u);
    std::sort(over.begin(), over.end());
    EXPECT_EQ(over[0], "(I)V");
    EXPECT_EQ(over[1], "(J[B)V");
    EXPECT_TRUE(jar.methodShapes(kThing, "absent").empty());
    EXPECT_TRUE(jar.hasField(kStub, "ignored"));
    EXPECT_TRUE(jar.hasField(kStub, "TRANSACTION_call"));
    EXPECT_FALSE(jar.hasField(kStub, "absent"));
}

}  // namespace

TEST(LxdbDex, ReadsAStoredJar) {
    Jar jar;
    std::string why;
    ASSERT_TRUE(jar.open(fixture("fixture-stored.jar"), &why)) << why;
    expectFixture(jar);
}

TEST(LxdbDex, ReadsADeflatedJar) {
    Jar jar;
    std::string why;
    ASSERT_TRUE(jar.open(fixture("fixture-deflated.jar"), &why)) << why;
    expectFixture(jar);
}

TEST(LxdbDex, NamesAMissingFile) {
    Jar jar;
    std::string why;
    EXPECT_FALSE(jar.open(fixture("absent.jar"), &why));
    EXPECT_NE(why.find("absent.jar"), std::string::npos);
}

TEST(LxdbDex, RefusesWhatIsNotAJar) {
    const std::vector<uint8_t> junk(4096, 0x41);
    Jar jar;
    std::string why;
    EXPECT_FALSE(jar.openMemory(junk.data(), junk.size(), &why));
    EXPECT_FALSE(why.empty());
}

TEST(LxdbDex, DescriptorsFollowTheDexForm) {
    EXPECT_EQ(dexDescriptor("android.os.IPowerManager"), "Landroid/os/IPowerManager;");
    EXPECT_EQ(dexDescriptor("android.os.IPowerManager", "$Stub"), "Landroid/os/IPowerManager$Stub;");
}

// Every byte of a damaged file is read with a bounds check, so no damage may end in a crash.
TEST(LxdbDex, DamagedFilesAreSafeToRead) {
    const std::vector<uint8_t> good = bytesOf(fixture("fixture-stored.jar"));
    ASSERT_FALSE(good.empty());
    uint32_t state = 0x1234567;
    const auto next = [&state] {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    };
    for (int round = 0; round < 3000; round++) {
        std::vector<uint8_t> bad = good;
        const uint32_t changes = 1 + next() % 8;
        for (uint32_t i = 0; i < changes; i++) bad[next() % bad.size()] = static_cast<uint8_t>(next());
        if (round % 3 == 0) bad.resize(bad.size() - next() % (bad.size() / 2));
        Jar jar;
        std::string why;
        if (!jar.openMemory(bad.data(), bad.size(), &why)) continue;
        jar.staticInt(kStub, "TRANSACTION_call");
        jar.methodShapes(kThing, "over");
        jar.hasField(kStub, "ignored");
    }
}
