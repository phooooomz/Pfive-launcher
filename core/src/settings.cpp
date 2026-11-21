// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/settings.hpp"

#include <algorithm>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <utility>

namespace lx {

namespace {

using Kind = Settings::Kind;

std::vector<Settings::Spec> buildSchema() {
    return {
        {"theme.preset", Kind::Choice, 0, 0, {"default", "minimal", "cyberpunk", "productivity", "custom"}, "default"},
        // "palette" follows the colour theme; the others pin a neon accent over it.
        {"theme.accent", Kind::Choice, 0, 0, {"palette", "cyan", "magenta", "blue", "purple"}, "palette"},
        {"theme.palette", Kind::Choice, 0, 0,
         {"plasma", "arctic", "graphite", "solarized", "rose", "forest", "amber", "oceanic", "lavender", "paper"},
         "plasma"},
        {"theme.iconStyle", Kind::Choice, 0, 0, {"vivid", "line"}, "vivid"},
        {"ui.font", Kind::Choice, 0, 0,
         {"roboto", "inter", "notosans", "notoserif", "notosansmono", "sourcesans3", "nunitosans", "lato", "plexsans",
          "jetbrainsmono", "plusjakartasans", "manrope", "poppins", "montserrat", "opensans", "worksans", "dmsans",
          "figtree", "redhattext", "lexend", "atkinsonnext", "exo2", "rubik", "nunito", "literata"},
         "roboto"},
        {"theme.neon", Kind::Bool, 0, 0, {}, "true"},
        {"theme.panelOpacity", Kind::Float, 0.35, 1.0, {}, "0.74"},
        {"theme.blur", Kind::Bool, 0, 0, {}, "true"},
        {"theme.blurRadius", Kind::Int, 0, 24, {}, "14"},
        {"theme.cornerRadius", Kind::Int, 0, 20, {}, "12"},
        {"motion.mode", Kind::Choice, 0, 0, {"system", "on", "off"}, "system"},
        // How a category's apps arrive in the Applications menu; "ribbon" is the motion LX always had.
        {"menu.animation", Kind::Choice, 0, 0, {"ribbon", "slide", "zoom", "curtain", "hinge", "wave", "off"}, "ribbon"},
        {"ui.scale", Kind::Float, 0.85, 1.3, {}, "1"},
        {"perf.mode", Kind::Choice, 0, 0, {"performance", "balanced", "quality"}, "balanced"},
        {"desktop.gridCols", Kind::Int, 3, 12, {}, "5"},
        {"desktop.gridRows", Kind::Int, 3, 12, {}, "6"},
        {"desktop.iconSize", Kind::Int, 36, 72, {}, "52"},
        {"desktop.labels", Kind::Bool, 0, 0, {}, "true"},
        {"desktop.locked", Kind::Bool, 0, 0, {}, "false"},
        // "auto" puts the dock at the bottom of a phone held upright and on the left elsewhere.
        {"dock.position", Kind::Choice, 0, 0, {"auto", "left", "right", "bottom"}, "auto"},
        {"dock.visible", Kind::Bool, 0, 0, {}, "true"},
        {"dock.autoHide", Kind::Bool, 0, 0, {}, "false"},
        {"dock.iconSize", Kind::Int, 28, 56, {}, "36"},
        {"dock.opacity", Kind::Float, 0.3, 1.0, {}, "0.82"},
        {"taskbar.labels", Kind::Bool, 0, 0, {}, "false"},
        {"taskbar.showWindows", Kind::Bool, 0, 0, {}, "true"},
        {"panel.top", Kind::Bool, 0, 0, {}, "true"},
        // Hides the status and navigation bars; a swipe from the edge shows them for a moment.
        {"display.immersive", Kind::Bool, 0, 0, {}, "false"},
        {"panel.search", Kind::Bool, 0, 0, {}, "true"},
        {"window.defaultState", Kind::Choice, 0, 0, {"auto", "floating", "maximized"}, "auto"},
        {"window.edgeSnap", Kind::Bool, 0, 0, {}, "true"},
        // Look of LX's own windows: "theme" follows the colour theme, the others bring their own palette,
        // "custom" is a base style adjusted with the window.custom.* keys below.
        {"window.style", Kind::Choice, 0, 0,
         {"theme", "lxmodern", "minimal", "glass", "cyberpunk", "rounded", "tactical", "compact", "material", "retro", "nature",
          "linux", "custom"},
         "lxmodern"},
        {"window.custom.base", Kind::Choice, 0, 0,
         {"lxmodern", "minimal", "glass", "cyberpunk", "rounded", "tactical", "compact", "material", "retro", "nature", "linux"},
         "lxmodern"},
        // "style" keeps the base style's accent; the others are fixed hues, still corrected for contrast.
        {"window.custom.accent", Kind::Choice, 0, 0,
         {"style", "blue", "cyan", "teal", "green", "amber", "orange", "magenta", "purple", "red"}, "style"},
        {"window.custom.corner", Kind::Int, 0, 28, {}, "20"},
        {"window.custom.border", Kind::Float, 0, 3, {}, "1.5"},
        {"window.custom.caption", Kind::Int, 40, 64, {}, "52"},
        {"window.custom.titleSize", Kind::Int, 12, 18, {}, "15"},
        {"window.custom.iconSize", Kind::Int, 16, 28, {}, "20"},
        {"window.custom.buttons", Kind::Choice, 0, 0, {"dots", "glyphs", "boxed"}, "dots"},
        {"window.custom.shadow", Kind::Int, 0, 24, {}, "12"},
        // Windows without focus fade their title and buttons a little.
        {"window.custom.dimInactive", Kind::Bool, 0, 0, {}, "true"},
        // On by default: Android ignores the window request where apps cannot float.
        {"apps.freeform", Kind::Bool, 0, 0, {}, "true"},
        // "lx" runs apps on a virtual display inside an LX window (needs LX Bridge); "system" leaves the frame to Android.
        {"apps.windowFrame", Kind::Choice, 0, 0, {"system", "lx"}, "lx"},
        {"a11y.largeTargets", Kind::Bool, 0, 0, {}, "false"},
        {"a11y.highContrast", Kind::Bool, 0, 0, {}, "false"},
        {"clock.24h", Kind::Bool, 0, 0, {}, "true"},
    };
}

const std::vector<std::pair<std::string, std::vector<std::pair<std::string, std::string>>>>& presetTable() {
    static const std::vector<std::pair<std::string, std::vector<std::pair<std::string, std::string>>>> table = {
        {"default",
         {{"theme.neon", "true"}, {"theme.blur", "true"}, {"theme.blurRadius", "14"},
          {"theme.panelOpacity", "0.74"}, {"theme.cornerRadius", "12"}, {"dock.autoHide", "false"},
          {"taskbar.labels", "false"}, {"panel.search", "true"}}},
        {"minimal",
         {{"theme.neon", "false"}, {"theme.blur", "false"}, {"theme.panelOpacity", "0.94"},
          {"theme.cornerRadius", "8"}, {"dock.autoHide", "true"}, {"taskbar.labels", "false"},
          {"panel.search", "false"}}},
        {"cyberpunk",
         {{"theme.neon", "true"}, {"theme.blur", "true"}, {"theme.blurRadius", "20"},
          {"theme.panelOpacity", "0.6"}, {"theme.cornerRadius", "10"}, {"dock.autoHide", "false"},
          {"taskbar.labels", "false"}, {"panel.search", "true"}}},
        {"productivity",
         {{"theme.neon", "false"}, {"theme.blur", "false"}, {"theme.panelOpacity", "0.9"},
          {"theme.cornerRadius", "6"}, {"dock.autoHide", "false"}, {"taskbar.labels", "true"},
          {"panel.search", "true"}, {"window.defaultState", "floating"}, {"window.edgeSnap", "true"}}},
    };
    return table;
}

bool presetTouches(const std::string& key) {
    for (const auto& [name, values] : presetTable())
        for (const auto& [k, v] : values)
            if (k == key) return true;
    return false;
}

bool parseInt(const std::string& s, long long& out) {
    if (s.empty() || s.size() > 18) return false;
    auto res = std::from_chars(s.data(), s.data() + s.size(), out);
    return res.ec == std::errc() && res.ptr == s.data() + s.size();
}

// Accepts "0.75", "1", "-0.5". Locale independent.
bool parseDecimal(const std::string& s, double& out) {
    if (s.empty() || s.size() > 24) return false;
    size_t i = 0;
    bool neg = false;
    if (s[i] == '-') {
        neg = true;
        ++i;
    }
    double v = 0;
    int digits = 0;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
        v = v * 10 + (s[i] - '0');
        ++i;
        ++digits;
    }
    if (i < s.size() && s[i] == '.') {
        ++i;
        double scale = 0.1;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
            v += (s[i] - '0') * scale;
            scale *= 0.1;
            ++i;
            ++digits;
        }
    }
    if (i != s.size() || digits == 0) return false;
    out = neg ? -v : v;
    return true;
}

std::string formatDecimal(double d) {
    char buf[32];
    auto res = std::to_chars(buf, buf + sizeof buf, std::round(d * 1000.0) / 1000.0);
    return std::string(buf, res.ptr);
}

}  // namespace

const std::vector<Settings::Spec>& Settings::schema() {
    static const std::vector<Spec> s = buildSchema();
    return s;
}

const Settings::Spec* Settings::spec(const std::string& key) {
    for (const auto& s : schema())
        if (s.key == key) return &s;
    return nullptr;
}

Settings::Settings() { resetDefaults(); }

void Settings::resetDefaults() {
    values_.clear();
    for (const auto& s : schema()) values_[s.key] = s.defaultValue;
}

std::optional<std::string> Settings::normalize(const Spec& s, const std::string& value) {
    switch (s.kind) {
        case Kind::Bool:
            if (value == "true" || value == "1") return std::string("true");
            if (value == "false" || value == "0") return std::string("false");
            return std::nullopt;
        case Kind::Int: {
            long long v;
            if (!parseInt(value, v) || static_cast<double>(v) < s.min || static_cast<double>(v) > s.max) return std::nullopt;
            return std::to_string(v);
        }
        case Kind::Float: {
            double v;
            if (!parseDecimal(value, v) || !std::isfinite(v) || v < s.min || v > s.max) return std::nullopt;
            return formatDecimal(v);
        }
        case Kind::Choice:
            if (std::find(s.choices.begin(), s.choices.end(), value) == s.choices.end()) return std::nullopt;
            return value;
    }
    return std::nullopt;
}

bool Settings::set(const std::string& key, const std::string& value) {
    const Spec* s = spec(key);
    if (!s) return false;
    auto v = normalize(*s, value);
    if (!v) return false;
    if (key == "theme.preset") {
        if (*v == "custom") {
            values_[key] = *v;
            return true;
        }
        return applyPreset(*v);
    }
    values_[key] = *v;
    if (presetTouches(key)) values_["theme.preset"] = "custom";
    return true;
}

std::string Settings::get(const std::string& key) const {
    auto it = values_.find(key);
    return it == values_.end() ? std::string() : it->second;
}

bool Settings::getBool(const std::string& key) const { return get(key) == "true"; }

int Settings::getInt(const std::string& key) const {
    long long v = 0;
    parseInt(get(key), v);
    return static_cast<int>(v);
}

double Settings::getFloat(const std::string& key) const {
    double v = 0;
    parseDecimal(get(key), v);
    return v;
}

std::vector<std::string> Settings::presets() {
    std::vector<std::string> out;
    for (const auto& [name, values] : presetTable()) out.push_back(name);
    return out;
}

bool Settings::applyPreset(const std::string& preset) {
    for (const auto& [name, values] : presetTable()) {
        if (name != preset) continue;
        for (const auto& [k, v] : values) {
            const Spec* s = spec(k);
            if (s && normalize(*s, v)) values_[k] = *normalize(*s, v);
        }
        values_["theme.preset"] = name;
        return true;
    }
    return false;
}

json::Value Settings::toJson() const {
    json::Value o{json::Object{}};
    for (const auto& s : schema()) o.set(s.key, get(s.key));
    return o;
}

size_t Settings::fromJson(const json::Value& v) {
    // Keys of features that were removed. Layouts saved before still carry them; they are dropped
    // without counting as skipped, so loading such a layout does not report a damaged entry.
    static const char* const retired[] = {"drawer.sort", "drawer.layout"};
    resetDefaults();
    size_t skipped = 0;
    std::string preset;
    for (const auto& [k, val] : v.asObject()) {
        if (std::find(std::begin(retired), std::end(retired), k) != std::end(retired)) continue;
        const Spec* s = spec(k);
        auto n = s && val.isString() ? normalize(*s, val.asString()) : std::nullopt;
        if (!n) {
            ++skipped;
            continue;
        }
        if (k == "theme.preset") preset = *n;
        else values_[k] = *n;
    }
    // The preset name is restored last so individual values loaded above win.
    if (!preset.empty()) values_["theme.preset"] = preset;
    return skipped;
}

}  // namespace lx
