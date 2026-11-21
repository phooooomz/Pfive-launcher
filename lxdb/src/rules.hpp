// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// What the daemon decides by itself, with no system call: who may ask it, which keys it injects, and how it reads what
// an event or a dump says.
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>

namespace lxdb {

constexpr int kPerUserRange = 100000;
constexpr int kRootUid = 0;
constexpr int kShellUid = 2000;
constexpr int kMaxSize = 8192;
constexpr int kMinDpi = 32;
constexpr int kMaxDpi = 1280;
constexpr size_t kMaxDisplays = 16;

constexpr int kOk = 0;
constexpr int kErrArgument = -1;
constexpr int kErrLimit = -2;
constexpr int kErrDisplay = -3;
constexpr int kErrUser = -5;
constexpr int kErrDenied = -6;
constexpr int kErrFailed = -7;
constexpr int kErrUnsupported = -8;

constexpr int kOrientationUnknown = -100;
constexpr int kOrientationPortrait = 1;     // ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
constexpr int kOrientationLandscape = 0;    // ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
constexpr int kOrientationUnspecified = -1;  // ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

/** LX's app id in any user. */
bool callerAllowed(int uid, int lxAppId);
/** Who may use the control socket. LX reaches the daemon through Binder; SELinux keeps every app off this socket anyway. */
bool controlAllowed(int uid);

bool sizeAllowed(int width, int height, int densityDpi);

/**
 * The keys an app in an LX window may receive; EmbedKeys.kt is the same list. Android acts on some keys for the whole
 * phone whatever display they name: Power, Sleep, Wakeup, End call, Assist, Recents, Notifications, Print Screen, volume
 * and media keys, and the Meta and Alt+Tab shortcuts.
 */
bool keyAllowed(int keyCode, int metaState, int action);

/** The orientation in a dump's "requestedOrientation=SCREEN_ORIENTATION_..." line. */
int orientationIn(std::string_view line);

/** An ActivityInfo.SCREEN_ORIENTATION_* value, as a task event gives it, reduced the same way as [orientationIn]. */
int orientationOf(int screenOrientation);

/** While an app has not arrived on its display yet, and while a display that had a task looks empty. */
constexpr int64_t kArrivalPollMs = 400;
/** A display whose app is running. Android's task events ring sooner when a task goes away. */
constexpr int64_t kSteadyPollMs = 3000;

/**
 * How long until the watcher asks Android again whether a display still has a task. Quick while the app is arriving and
 * while an emptiness is being confirmed, slow once the app runs: one binder call per window every few seconds instead
 * of two and a half a second.
 */
int64_t pollDelay(bool sawTask, int emptyPolls);

/** The first gap between dump reads of a display's orientation, and the longest. */
constexpr int64_t kFirstLookMs = 1500;
constexpr int64_t kLongestLookMs = 30000;

/**
 * The next gap between dump reads, from the last one: back to the first gap after a change, otherwise twice as long, up
 * to the longest. A display held in portrait or landscape backs off like any other; the task event says when it lets go.
 */
int64_t lookDelay(int64_t previous, bool changed);

/**
 * The requestedOrientation of the app on [displayId] in the output of "dumpsys activity -a -p <package> activities". Only
 * the part for that display counts: the same app can also run on the main display or in a work profile.
 */
std::optional<int> requestedOrientation(std::string_view dump, int displayId);

/** [text] as one word for sh. */
void shellQuote(std::string_view text, char* out, size_t capacity);

/** Where MotionEvent and KeyEvent parcels keep what the daemon reads and changes. */
struct InputFields {
    size_t displayIdAt = 0;
    int action = 0;
    int keyCode = 0;
    int metaState = 0;
};

class ParcelReader;
/** [p] sits on an InputEvent's token. False for any other shape. */
bool readMotionFields(ParcelReader& p, InputFields* out);
bool readKeyFields(ParcelReader& p, InputFields* out);

constexpr int kMaskAction = 0xff;
constexpr int kMotionDown = 0;
constexpr int kMotionUp = 1;

}  // namespace lxdb
