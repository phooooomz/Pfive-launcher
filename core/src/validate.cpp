// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/validate.hpp"

#include <algorithm>
#include <cmath>

namespace lx::validate {

bool isToken(std::string_view s, size_t maxLen) {
    if (s.empty() || s.size() > maxLen) return false;
    for (char c : s) {
        bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
        if (!ok) return false;
    }
    return true;
}

namespace {
bool isJavaNameChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
           c == '_' || c == '.' || c == '$';
}
}  // namespace

bool isAppKey(std::string_view s) {
    if (s.size() < 5 || s.size() > 512) return false;
    size_t slash = s.find('/');
    size_t hash = s.rfind('#');
    if (slash == std::string_view::npos || hash == std::string_view::npos || hash < slash) return false;
    std::string_view pkg = s.substr(0, slash);
    std::string_view cls = s.substr(slash + 1, hash - slash - 1);
    std::string_view user = s.substr(hash + 1);
    if (pkg.empty() || cls.empty() || user.empty() || user.size() > 19) return false;
    if (pkg.front() == '.' || pkg.find("..") != std::string_view::npos) return false;
    for (char c : pkg)
        if (!isJavaNameChar(c) || c == '$') return false;
    for (char c : cls)
        if (!isJavaNameChar(c)) return false;
    for (char c : user)
        if (c < '0' || c > '9') return false;
    return true;
}

std::string truncateUtf8(std::string_view s, size_t maxBytes, bool keepNewlines) {
    std::string out;
    out.reserve(std::min(s.size(), maxBytes));
    size_t i = 0;
    while (i < s.size()) {
        auto c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if (c >= 0xF0) len = 4;
        else if (c >= 0xE0) len = 3;
        else if (c >= 0xC0) len = 2;
        else if (c >= 0x80) {  // stray continuation byte
            ++i;
            continue;
        }
        if (i + len > s.size()) break;
        if (out.size() + len > maxBytes) break;
        if (len == 1 && ((c < 0x20 && c != '\t' && !(keepNewlines && c == '\n')) || c == 0x7F)) {
            ++i;
            continue;
        }
        out.append(s.data() + i, len);
        i += len;
    }
    return out;
}

std::optional<uint32_t> idFrom(const json::Value* v) {
    if (!v || !v->isNumber()) return std::nullopt;
    double d = v->asNumber();
    if (!std::isfinite(d) || d < 1 || d > 2147483647.0 || d != std::floor(d)) return std::nullopt;
    return static_cast<uint32_t>(d);
}

int clampedInt(double v, int lo, int hi, int fallback) {
    if (std::isnan(v)) return fallback;
    if (v <= static_cast<double>(lo)) return lo;
    if (v >= static_cast<double>(hi)) return hi;
    return static_cast<int>(v);
}

double numberOr(const json::Value* v, double fallback) {
    if (!v || !v->isNumber()) return fallback;
    double d = v->asNumber();
    return std::isfinite(d) ? d : fallback;
}

bool isWallpaperRef(std::string_view s) {
    if (s == "system") return true;
    if (s.substr(0, 8) == "builtin:") return isToken(s.substr(8), 32);
    // An image LX copied into its private storage; the token is the file name.
    if (s.substr(0, 6) == "local:") return isToken(s.substr(6), 48);
    if (s.substr(0, 6) == "color:") {
        std::string_view hex = s.substr(6);
        if (hex.size() != 7 || hex[0] != '#') return false;
        for (size_t i = 1; i < hex.size(); ++i) {
            char c = hex[i];
            bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }
    if (s.substr(0, 10) == "content://") {
        if (s.size() > 1024) return false;
        for (char c : s)
            if (static_cast<unsigned char>(c) <= 0x20 || c == '\\') return false;
        return true;
    }
    return false;
}

}  // namespace lx::validate
