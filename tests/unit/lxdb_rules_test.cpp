// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <string>

#include "parcel.hpp"
#include "rules.hpp"

using namespace lxdb;

namespace {

constexpr int kDown = 0;
constexpr int kUp = 1;
constexpr int kLx = 10123;
constexpr int kMetaLeft = 0x20000;
constexpr int kAlt = 0x2;
constexpr int kKeyA = 29;
constexpr int kTab = 61;

ParcelReader readerOf(const Parcel& p) { return {p.data(), p.size(), p.offsets(), p.objectCount()}; }

Parcel motionParcel(int pointers, int action) {
    Parcel p;
    p.writeInt32(1);         // InputEvent.PARCEL_TOKEN_MOTION_EVENT
    p.writeInt32(pointers);
    p.writeInt32(1);         // samples
    p.writeInt32(55);        // id
    p.writeInt32(-1);        // device
    p.writeInt32(0x1002);    // source
    p.writeInt32(0);         // display
    p.writeInt32(32);        // HMAC
    const std::vector<uint8_t> hmac(32, 0);
    p.writeBytes(hmac.data(), hmac.size());
    p.writeInt32(action);
    return p;
}

Parcel keyParcel(int keyCode, int action, int meta, int hmacBytes) {
    Parcel p;
    p.writeInt32(2);  // InputEvent.PARCEL_TOKEN_KEY_EVENT
    p.writeInt32(9);  // id
    p.writeInt32(-1);  // device
    p.writeInt32(0x101);  // source
    p.writeInt32(0);  // display
    if (hmacBytes < 0) {
        p.writeInt32(-1);
    } else {
        p.writeByteArray(std::vector<uint8_t>(static_cast<size_t>(hmacBytes), 0));
    }
    p.writeInt32(action);
    p.writeInt32(keyCode);
    p.writeInt32(0);  // repeat
    p.writeInt32(meta);
    return p;
}

}  // namespace

TEST(LxdbRules, OnlyLxCallsItsService) {
    EXPECT_TRUE(callerAllowed(kLx, kLx));
    EXPECT_TRUE(callerAllowed(1000000 + kLx, kLx));  // LX in user 10
    EXPECT_FALSE(callerAllowed(kShellUid, kLx));
    EXPECT_FALSE(callerAllowed(kRootUid, kLx));
    EXPECT_FALSE(callerAllowed(kLx + 1, kLx));
    EXPECT_FALSE(callerAllowed(kLx, -1));
}

TEST(LxdbRules, ControlSocketTakesTheShellAndRootOnly) {
    EXPECT_TRUE(controlAllowed(kShellUid));
    EXPECT_TRUE(controlAllowed(kRootUid));
    EXPECT_FALSE(controlAllowed(kLx));
    EXPECT_FALSE(controlAllowed(1000));
}

TEST(LxdbRules, SizesStayInLimits) {
    EXPECT_TRUE(sizeAllowed(720, 1600, 320));
    EXPECT_TRUE(sizeAllowed(1, 1, kMinDpi));
    EXPECT_TRUE(sizeAllowed(kMaxSize, kMaxSize, kMaxDpi));
    EXPECT_FALSE(sizeAllowed(0, 100, 320));
    EXPECT_FALSE(sizeAllowed(100, kMaxSize + 1, 320));
    EXPECT_FALSE(sizeAllowed(100, 100, kMinDpi - 1));
    EXPECT_FALSE(sizeAllowed(100, 100, kMaxDpi + 1));
}

TEST(LxdbRules, TypingKeysPassAndPhoneWideKeysDoNot) {
    for (const int key : {4, 7, 16, 19, 23, kKeyA, 54, kTab, 62, 66, 67, 76, 92, 96, 111, 112, 131, 188, 278}) {
        EXPECT_TRUE(keyAllowed(key, 0, kDown)) << key;
        EXPECT_TRUE(keyAllowed(key, 0, kUp)) << key;
    }
    // Power, Volume up and down, Home, Call, End call, Camera, Menu, Notification, Assist, Sleep, Wakeup, Recents, Media
    // play, screenshot.
    for (const int key : {26, 24, 25, 3, 5, 6, 27, 82, 83, 219, 223, 224, 187, 85, 120}) {
        EXPECT_FALSE(keyAllowed(key, 0, kDown)) << key;
    }
}

TEST(LxdbRules, ShortcutsAreRefusedOnThePressOnly) {
    EXPECT_FALSE(keyAllowed(kKeyA, kMetaLeft, kDown));
    EXPECT_TRUE(keyAllowed(kKeyA, kMetaLeft, kUp));
    EXPECT_FALSE(keyAllowed(kTab, kAlt, kDown));
    EXPECT_TRUE(keyAllowed(kKeyA, kAlt, kDown));
    EXPECT_FALSE(keyAllowed(kKeyA, 0, 2));  // ACTION_MULTIPLE
}

TEST(LxdbRules, OrientationReadsTheWordInTheLine) {
    EXPECT_EQ(orientationIn("  requestedOrientation=SCREEN_ORIENTATION_SENSOR_PORTRAIT"), kOrientationPortrait);
    EXPECT_EQ(orientationIn("  requestedOrientation=SCREEN_ORIENTATION_USER_LANDSCAPE"), kOrientationLandscape);
    EXPECT_EQ(orientationIn("  requestedOrientation=SCREEN_ORIENTATION_UNSPECIFIED"), kOrientationUnspecified);
}

TEST(LxdbRules, OrientationComesFromTheAskedDisplayOnly) {
    const std::string dump =
        "Display #0 (activities from top to bottom):\n"
        "  requestedOrientation=SCREEN_ORIENTATION_PORTRAIT\n"
        "Display #21 (activities from top to bottom):\n"
        "  requestedOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE\n"
        "Display #2 (activities from top to bottom):\n"
        "  requestedOrientation=SCREEN_ORIENTATION_UNSPECIFIED\n";
    EXPECT_EQ(requestedOrientation(dump, 21), kOrientationLandscape);
    EXPECT_EQ(requestedOrientation(dump, 2), kOrientationUnspecified);
    EXPECT_EQ(requestedOrientation(dump, 0), kOrientationPortrait);
    EXPECT_FALSE(requestedOrientation(dump, 1).has_value());
    EXPECT_FALSE(requestedOrientation("", 2).has_value());
}

TEST(LxdbRules, QuotingKeepsAWordOneWord) {
    char out[64];
    shellQuote("/data/local/tmp/a b", out, sizeof(out));
    EXPECT_STREQ(out, "'/data/local/tmp/a b'");
    shellQuote("it's", out, sizeof(out));
    EXPECT_STREQ(out, "'it'\\''s'");
    shellQuote("x; rm -rf /", out, sizeof(out));
    EXPECT_STREQ(out, "'x; rm -rf /'");
}

TEST(LxdbRules, QuotingNeverRunsPastItsBuffer) {
    char out[8];
    out[7] = 'z';
    shellQuote("a long text that does not fit", out, 7);
    EXPECT_EQ(out[7], 'z');
    EXPECT_EQ(out[6], '\0');
}

TEST(LxdbRules, MotionFieldsAreFoundInTheParcel) {
    const Parcel p = motionParcel(2, 5 | (1 << 8));
    ParcelReader r = readerOf(p);
    InputFields fields;
    ASSERT_TRUE(readMotionFields(r, &fields));
    EXPECT_EQ(fields.displayIdAt, 24u);
    EXPECT_EQ(fields.action & kMaskAction, 5);
}

TEST(LxdbRules, AParcelOfAnotherShapeIsRefused) {
    const Parcel noPointers = motionParcel(0, kMotionDown);
    ParcelReader a = readerOf(noPointers);
    InputFields fields;
    EXPECT_FALSE(readMotionFields(a, &fields));
    const Parcel key = keyParcel(kKeyA, kDown, 0, 32);
    ParcelReader b = readerOf(key);
    EXPECT_FALSE(readMotionFields(b, &fields));
    Parcel cut;
    cut.writeInt32(1);
    ParcelReader c = readerOf(cut);
    EXPECT_FALSE(readMotionFields(c, &fields));
}

TEST(LxdbRules, KeyFieldsAreReadWithAndWithoutAnHmac) {
    for (const int hmac : {32, -1}) {
        const Parcel p = keyParcel(kKeyA, kUp, kMetaLeft, hmac);
        ParcelReader r = readerOf(p);
        InputFields fields;
        ASSERT_TRUE(readKeyFields(r, &fields)) << hmac;
        EXPECT_EQ(fields.displayIdAt, 16u);
        EXPECT_EQ(fields.keyCode, kKeyA);
        EXPECT_EQ(fields.action, kUp);
        EXPECT_EQ(fields.metaState, kMetaLeft);
    }
}

TEST(LxdbRules, TaskEventOrientationMatchesTheDumpWords) {
    EXPECT_EQ(orientationOf(1), kOrientationPortrait);    // PORTRAIT
    EXPECT_EQ(orientationOf(7), kOrientationPortrait);    // SENSOR_PORTRAIT
    EXPECT_EQ(orientationOf(12), kOrientationPortrait);   // USER_PORTRAIT
    EXPECT_EQ(orientationOf(0), kOrientationLandscape);   // LANDSCAPE
    EXPECT_EQ(orientationOf(6), kOrientationLandscape);   // SENSOR_LANDSCAPE
    EXPECT_EQ(orientationOf(11), kOrientationLandscape);  // USER_LANDSCAPE
    EXPECT_EQ(orientationOf(-1), kOrientationUnspecified);
    EXPECT_EQ(orientationOf(4), kOrientationUnspecified);  // SENSOR
    EXPECT_EQ(orientationOf(10), kOrientationUnspecified);  // FULL_SENSOR
}

TEST(LxdbRules, RunningAppsArePolledSlowly) {
    // The watcher used to ask every 400 ms for as long as an app ran: 16 windows made 40 binder calls a second.
    EXPECT_EQ(pollDelay(false, 0), kArrivalPollMs);  // arriving
    EXPECT_EQ(pollDelay(true, 0), kSteadyPollMs);    // running
    EXPECT_EQ(pollDelay(true, 1), kArrivalPollMs);   // looked empty once, confirm quickly
    EXPECT_GE(kSteadyPollMs, 2000);
    EXPECT_LE(kSteadyPollMs, 5000);
}

TEST(LxdbRules, OrientationReadsBackOffEvenWhileHeld) {
    // A portrait-only app used to bring the dump back to every 1.5 s forever.
    int64_t gap = kFirstLookMs;
    for (int i = 0; i < 10; ++i) gap = lookDelay(gap, false);
    EXPECT_EQ(gap, kLongestLookMs);
    EXPECT_EQ(lookDelay(kLongestLookMs, true), kFirstLookMs);
    EXPECT_EQ(lookDelay(0, false), kFirstLookMs);
}
