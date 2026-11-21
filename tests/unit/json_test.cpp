// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <chrono>
#include <string>

#include "lx/json.hpp"

using namespace lx::json;

TEST(Json, ParsesScalars) {
    EXPECT_TRUE(parse("null")->isNull());
    EXPECT_TRUE(parse("true")->asBool());
    EXPECT_FALSE(parse("false")->asBool(true));
    EXPECT_DOUBLE_EQ(parse("-12.5e1")->asNumber(), -125.0);
    EXPECT_DOUBLE_EQ(parse("0.25")->asNumber(), 0.25);
    EXPECT_EQ(parse("\"a\\nb\"")->asString(), "a\nb");
}

TEST(Json, ParsesNestedDocument) {
    auto v = parse(R"({"a":[1,2,{"b":"c"}],"d":{"e":false}})");
    ASSERT_TRUE(v);
    ASSERT_TRUE(v->find("a"));
    EXPECT_EQ(v->find("a")->asArray().size(), 3u);
    EXPECT_EQ(v->find("a")->asArray()[2].find("b")->asString(), "c");
    EXPECT_FALSE(v->find("d")->find("e")->asBool(true));
}

TEST(Json, DecodesUnicodeEscapesAndSurrogates) {
    EXPECT_EQ(parse(R"("\u00e9")")->asString(), "\xC3\xA9");
    EXPECT_EQ(parse(R"("\ud83d\ude00")")->asString(), "\xF0\x9F\x98\x80");
    EXPECT_FALSE(parse(R"("\ud83d")"));
    EXPECT_FALSE(parse(R"("\ude00")"));
}

TEST(Json, RejectsMalformedInput) {
    const char* bad[] = {"", "{", "[1,", "{\"a\" 1}", "tru", "01", "1.", "-", "1e", "\"abc",
                         "[1 2]", "{\"a\":1,}", "nul", "\"\x01\"", "{} x", "[\"\\x\"]"};
    for (const char* s : bad) EXPECT_FALSE(parse(s)) << s;
}

TEST(Json, RejectsInvalidUtf8) {
    EXPECT_FALSE(parse("\"\xC3\""));
    EXPECT_FALSE(parse("\"\xC0\xAF\""));       // overlong
    EXPECT_FALSE(parse("\"\xED\xA0\x80\""));   // encoded surrogate
    EXPECT_TRUE(parse("\"\xE2\x82\xAC\""));    // euro sign
}

TEST(Json, EnforcesDepthLimit) {
    std::string deep(40, '[');
    deep += std::string(40, ']');
    EXPECT_FALSE(parse(deep));
    Limits l;
    l.maxDepth = 64;
    EXPECT_TRUE(parse(deep, l));
}

TEST(Json, EnforcesSizeAndStringLimits) {
    Limits l;
    l.maxBytes = 10;
    EXPECT_FALSE(parse("[1,2,3,4,5,6]", l));
    Limits s;
    s.maxStringBytes = 4;
    EXPECT_FALSE(parse("\"abcdefgh\"", s));
    Limits e;
    e.maxElements = 3;
    EXPECT_FALSE(parse("[1,2,3,4]", e));
}

TEST(Json, ReportsErrorOffset) {
    ParseError err;
    EXPECT_FALSE(parse("[1, x]", Limits{}, &err));
    EXPECT_EQ(err.offset, 4u);
    EXPECT_FALSE(err.message.empty());
}

TEST(Json, RoundTripsThroughSerialize) {
    Value v{Object{}};
    v.set("name", "LX \"quoted\"\n");
    v.set("n", 3);
    v.set("f", 0.125);
    v.set("list", Array{Value(true), Value(nullptr)});
    std::string text = serialize(v);
    auto back = parse(text);
    ASSERT_TRUE(back);
    EXPECT_EQ(serialize(*back), text);
    EXPECT_EQ(back->find("name")->asString(), "LX \"quoted\"\n");
}

TEST(Json, CopiesDoNotShareMutations) {
    Value a{Array{Value(1)}};
    Value b = a;
    b.push(Value(2));
    EXPECT_EQ(a.asArray().size(), 1u);
    EXPECT_EQ(b.asArray().size(), 2u);
}

TEST(Json, DuplicateKeysKeepLastValue) {
    auto v = parse(R"({"a":1,"a":2})");
    ASSERT_TRUE(v);
    EXPECT_EQ(v->asObject().size(), 1u);
    EXPECT_DOUBLE_EQ(v->find("a")->asNumber(), 2);
}

TEST(Json, SerializesNonFiniteAsNull) {
    Value v(1.0 / 0.0 * 0.0);  // NaN
    EXPECT_EQ(serialize(v), "null");
}

TEST(Json, FloatsWidenedToDoubleWriteShort) {
    // Layout coordinates are floats; written as doubles they took 17 digits each.
    lx::json::Value o{lx::json::Object{}};
    o.set("x", static_cast<double>(0.94f));
    o.set("y", static_cast<double>(0.1f));
    o.set("d", 0.1);  // a true double keeps every digit it needs
    const std::string text = lx::json::serialize(o);
    EXPECT_NE(text.find("\"x\":0.94"), std::string::npos) << text;
    EXPECT_EQ(text.find("0.9399999976158142"), std::string::npos) << text;
    EXPECT_NE(text.find("\"d\":0.1"), std::string::npos) << text;
    auto back = lx::json::parse(text);
    ASSERT_TRUE(back.has_value());
    EXPECT_EQ(static_cast<float>(back->find("x")->asNumber()), 0.94f);
    EXPECT_EQ(static_cast<float>(back->find("y")->asNumber()), 0.1f);
    EXPECT_EQ(back->find("d")->asNumber(), 0.1);
}

// A repeated key keeps its first place and takes the last value, in small objects and in ones large enough to be
// indexed. An object with 100,000 keys once took the better part of a minute to parse; it now takes a moment.
TEST(Json, RepeatedKeysAndLargeObjects) {
    auto small = parse(R"({"a":1,"b":2,"a":3})");
    ASSERT_TRUE(small);
    EXPECT_EQ(small->asObject().size(), 2u);
    EXPECT_EQ(small->find("a")->asNumber(), 3);

    std::string big = "{";
    for (int i = 0; i < 100000; ++i) big += "\"k" + std::to_string(i) + "\":" + std::to_string(i) + ",";
    big += "\"k5\":-1,\"k70000\":-2}";
    auto started = std::chrono::steady_clock::now();
    auto doc = parse(big);
    auto took = std::chrono::steady_clock::now() - started;
    ASSERT_TRUE(doc);
    EXPECT_EQ(doc->asObject().size(), 100000u);
    EXPECT_EQ(doc->asObject()[5].first, "k5");
    EXPECT_EQ(doc->asObject()[5].second.asNumber(), -1);
    EXPECT_EQ(doc->asObject()[70000].second.asNumber(), -2);
    EXPECT_LT(std::chrono::duration_cast<std::chrono::milliseconds>(took).count(), 2000);
}
