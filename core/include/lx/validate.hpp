// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Input checks shared by every module that accepts data from Java, from disk or
// from an imported layout file.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

#include "lx/json.hpp"

namespace lx::validate {

// [a-z0-9_.-], 1..maxLen characters.
bool isToken(std::string_view s, size_t maxLen);

// "package/activity#userSerial" as produced by the Android layer. Package and
// class names follow Java identifier rules loosely; no path separators, no
// control characters.
bool isAppKey(std::string_view s);

// Cuts at maxBytes without splitting a UTF-8 sequence and strips control
// characters except tab (and newline when keepNewlines is set).
std::string truncateUtf8(std::string_view s, size_t maxBytes, bool keepNewlines = false);

// Positive integer id below 2^31 stored as a JSON number.
std::optional<uint32_t> idFrom(const json::Value* v);

double numberOr(const json::Value* v, double fallback);
// [v] as an int in [lo, hi], clamped while still a double: casting a double outside the int range (a file
// saying "col": 1e300) is undefined behaviour. NaN gives [fallback].
int clampedInt(double v, int lo, int hi, int fallback);

// Wallpaper references we accept: "system", "builtin:<token>",
// "color:#RRGGBB", "local:<token>" (an image copied into LX's private
// storage), or a content:// URI chosen through the system picker.
bool isWallpaperRef(std::string_view s);

}  // namespace lx::validate
