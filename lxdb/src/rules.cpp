// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "rules.hpp"

#include <algorithm>
#include <cstring>

#include "parcel.hpp"

namespace lxdb {

namespace {

constexpr int kKeyDown = 0;
constexpr int kKeyUp = 1;
constexpr int kMetaMask = 0x70000;  // KeyEvent.META_META_MASK
constexpr int kAltMask = 0x32;      // KeyEvent.META_ALT_MASK
constexpr int kTab = 61;
constexpr int kParcelTokenMotion = 1;  // InputEvent.PARCEL_TOKEN_MOTION_EVENT
constexpr int kParcelTokenKey = 2;     // InputEvent.PARCEL_TOKEN_KEY_EVENT
constexpr int kMaxPointers = 16;

/** Closed ranges of KeyEvent.KEYCODE_* values. */
struct Range {
    int first;
    int last;
};

constexpr Range kAllowedKeys[] = {
    {4, 4},      // BACK
    {7, 18},     // 0 to POUND
    {19, 23},    // DPAD_UP to DPAD_CENTER
    {29, 62},    // A to SPACE
    {66, 67},    // ENTER, DEL
    {68, 77},    // GRAVE to AT
    {81, 81},    // PLUS
    {92, 93},    // PAGE_UP, PAGE_DOWN
    {96, 110},   // BUTTON_A to BUTTON_MODE
    {111, 116},  // ESCAPE to SCROLL_LOCK
    {119, 119},  // FUNCTION
    {121, 125},  // BREAK to FORWARD
    {131, 163},  // F1 to NUMPAD_RIGHT_PAREN
    {188, 203},  // BUTTON_1 to BUTTON_16
    {211, 218},  // ZENKAKU_HANKAKU to KANA
    {277, 279},  // CUT to PASTE
};

/** The HMAC Android signs real input with. An event made in Java has none, which is written as length -1. */
void skipHmac(ParcelReader& p) {
    const int32_t size = p.readInt32();
    if (size > 0) p.seek(p.position() + ((static_cast<size_t>(size) + 3) & ~size_t{3}));
}

bool allowedCode(int keyCode) {
    for (const Range& r : kAllowedKeys) {
        if (keyCode >= r.first && keyCode <= r.last) return true;
    }
    return false;
}

}  // namespace

bool callerAllowed(int uid, int lxAppId) { return lxAppId >= 0 && uid % kPerUserRange == lxAppId; }

bool controlAllowed(int uid) { return uid == kRootUid || uid == kShellUid; }

bool sizeAllowed(int width, int height, int densityDpi) {
    return width >= 1 && width <= kMaxSize && height >= 1 && height <= kMaxSize && densityDpi >= kMinDpi && densityDpi <= kMaxDpi;
}

bool keyAllowed(int keyCode, int metaState, int action) {
    if (action != kKeyDown && action != kKeyUp) return false;
    if (!allowedCode(keyCode)) return false;
    // Shortcuts fire on the press; a release with a modifier still held must reach the app.
    if (action == kKeyDown) {
        if ((metaState & kMetaMask) != 0) return false;
        if (keyCode == kTab && (metaState & kAltMask) != 0) return false;
    }
    return true;
}

int orientationIn(std::string_view line) {
    if (line.find("PORTRAIT") != std::string_view::npos) return kOrientationPortrait;
    if (line.find("LANDSCAPE") != std::string_view::npos) return kOrientationLandscape;
    return kOrientationUnspecified;
}

int orientationOf(int screenOrientation) {
    switch (screenOrientation) {
        case 0:   // LANDSCAPE
        case 6:   // SENSOR_LANDSCAPE
        case 8:   // REVERSE_LANDSCAPE
        case 11:  // USER_LANDSCAPE
            return kOrientationLandscape;
        case 1:   // PORTRAIT
        case 7:   // SENSOR_PORTRAIT
        case 9:   // REVERSE_PORTRAIT
        case 12:  // USER_PORTRAIT
            return kOrientationPortrait;
        default:
            return kOrientationUnspecified;
    }
}

int64_t pollDelay(bool sawTask, int emptyPolls) { return !sawTask || emptyPolls > 0 ? kArrivalPollMs : kSteadyPollMs; }

int64_t lookDelay(int64_t previous, bool changed) {
    if (changed || previous < kFirstLookMs) return kFirstLookMs;
    return std::min(previous * 2, kLongestLookMs);
}

std::optional<int> requestedOrientation(std::string_view dump, int displayId) {
    char wanted[32];
    size_t used = 0;
    for (const char* s = "Display #"; *s != '\0'; s++) wanted[used++] = *s;
    char digits[12];
    size_t count = 0;
    unsigned value = displayId < 0 ? 0u : static_cast<unsigned>(displayId);
    do {
        digits[count++] = static_cast<char>('0' + value % 10);
        value /= 10;
    } while (value != 0);
    while (count > 0) wanted[used++] = digits[--count];
    wanted[used++] = ' ';
    const std::string_view display(wanted, used);

    bool here = false;
    while (!dump.empty()) {
        const size_t end = dump.find('\n');
        const std::string_view line = dump.substr(0, end);
        dump = end == std::string_view::npos ? std::string_view{} : dump.substr(end + 1);
        if (line.find("Display #") != std::string_view::npos) here = line.find(display) != std::string_view::npos;
        if (here && line.find("requestedOrientation=") != std::string_view::npos) return orientationIn(line);
    }
    return std::nullopt;
}

void shellQuote(std::string_view text, char* out, size_t capacity) {
    size_t used = 0;
    const auto put = [&](char c) {
        if (used + 1 < capacity) out[used++] = c;
    };
    put('\'');
    for (const char c : text) {
        if (c == '\'') {
            for (const char* s = "'\\''"; *s != '\0'; s++) put(*s);
        } else {
            put(c);
        }
    }
    put('\'');
    if (capacity > 0) out[used] = '\0';
}

bool readMotionFields(ParcelReader& p, InputFields* out) {
    if (p.readInt32() != kParcelTokenMotion) return false;
    const int pointers = p.readInt32();
    p.readInt32();  // samples
    p.readInt32();  // id
    p.readInt32();  // device
    p.readInt32();  // source
    out->displayIdAt = p.position();
    p.readInt32();
    skipHmac(p);
    out->action = p.readInt32();
    return p.ok() && pointers >= 1 && pointers <= kMaxPointers;
}

bool readKeyFields(ParcelReader& p, InputFields* out) {
    if (p.readInt32() != kParcelTokenKey) return false;
    p.readInt32();  // id
    p.readInt32();  // device
    p.readInt32();  // source
    out->displayIdAt = p.position();
    p.readInt32();
    skipHmac(p);
    out->action = p.readInt32();
    out->keyCode = p.readInt32();
    p.readInt32();  // repeat count
    out->metaState = p.readInt32();
    return p.ok();
}

}  // namespace lxdb
