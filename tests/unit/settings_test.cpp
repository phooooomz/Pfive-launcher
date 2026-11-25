// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include "lx/settings.hpp"

using namespace lx;

TEST(Settings, DefaultsMatchSchema) {
    Settings s;
    for (const auto& spec : Settings::schema()) EXPECT_EQ(s.get(spec.key), spec.defaultValue) << spec.key;
    EXPECT_EQ(s.get("perf.mode"), "balanced");
    EXPECT_EQ(s.get("dock.position"), "auto");
    EXPECT_EQ(s.get("theme.palette"), "plasma");
    EXPECT_EQ(s.get("theme.accent"), "palette");
    EXPECT_EQ(s.get("ui.font"), "roboto");
    EXPECT_EQ(s.get("theme.iconStyle"), "vivid");
    EXPECT_TRUE(s.getBool("apps.freeform"));
    EXPECT_FALSE(s.getBool("display.immersive"));
}

TEST(Settings, RejectsUnknownKeysAndBadValues) {
    Settings s;
    EXPECT_FALSE(s.set("no.such.key", "1"));
    EXPECT_FALSE(s.set("theme.neon", "yes"));
    EXPECT_FALSE(s.set("desktop.gridCols", "2"));
    EXPECT_FALSE(s.set("desktop.gridCols", "13"));
    EXPECT_FALSE(s.set("desktop.gridCols", "4.5"));
    EXPECT_FALSE(s.set("theme.panelOpacity", "1.5"));
    EXPECT_FALSE(s.set("theme.panelOpacity", "0,5"));
    EXPECT_FALSE(s.set("dock.position", "top"));
    EXPECT_EQ(s.get("desktop.gridCols"), "5");
}

TEST(Settings, NormalizesAcceptedValues) {
    Settings s;
    EXPECT_TRUE(s.set("theme.neon", "0"));
    EXPECT_EQ(s.get("theme.neon"), "false");
    EXPECT_TRUE(s.set("theme.panelOpacity", "0.5"));
    EXPECT_DOUBLE_EQ(s.getFloat("theme.panelOpacity"), 0.5);
    EXPECT_TRUE(s.set("desktop.gridCols", "7"));
    EXPECT_EQ(s.getInt("desktop.gridCols"), 7);
}

TEST(Settings, PresetsApplyAndEditsMarkCustom) {
    Settings s;
    EXPECT_TRUE(s.applyPreset("minimal"));
    EXPECT_EQ(s.get("theme.preset"), "minimal");
    EXPECT_FALSE(s.getBool("theme.blur"));
    EXPECT_TRUE(s.set("theme.blur", "true"));
    EXPECT_EQ(s.get("theme.preset"), "custom");
    EXPECT_TRUE(s.set("theme.preset", "cyberpunk"));
    EXPECT_TRUE(s.getBool("theme.neon"));
    EXPECT_FALSE(s.applyPreset("does-not-exist"));
    // Settings outside the preset do not reset the preset name.
    EXPECT_TRUE(s.set("clock.24h", "false"));
    EXPECT_EQ(s.get("theme.preset"), "cyberpunk");
}

TEST(Settings, JsonRoundTripAndTolerance) {
    Settings s;
    s.set("dock.position", "bottom");
    s.set("ui.scale", "1.15");
    Settings t;
    EXPECT_EQ(t.fromJson(s.toJson()), 0u);
    EXPECT_EQ(t.get("dock.position"), "bottom");
    EXPECT_DOUBLE_EQ(t.getFloat("ui.scale"), 1.15);

    auto doc = json::parse(R"({"dock.position":"sideways","ui.scale":"9","evil":"x","theme.neon":true,"clock.24h":"false"})");
    Settings u;
    EXPECT_EQ(u.fromJson(*doc), 4u);
    EXPECT_EQ(u.get("dock.position"), "auto");
    EXPECT_FALSE(u.getBool("clock.24h"));
}

TEST(Settings, ThemeFontAndIconChoices) {
    Settings s;
    for (const char* p : {"plasma", "arctic", "graphite", "solarized", "rose", "forest", "amber", "oceanic", "lavender",
                          "paper"})
        EXPECT_TRUE(s.set("theme.palette", p)) << p;
    EXPECT_FALSE(s.set("theme.palette", "neon-pink"));
    for (const char* f : {"roboto", "inter", "notosans", "notoserif", "notosansmono", "sourcesans3", "nunitosans",
                          "lato", "plexsans", "jetbrainsmono", "plusjakartasans", "manrope", "poppins", "montserrat",
                          "opensans", "worksans", "dmsans", "figtree", "redhattext", "lexend", "atkinsonnext", "exo2",
                          "rubik", "nunito", "literata"})
        EXPECT_TRUE(s.set("ui.font", f)) << f;
    EXPECT_FALSE(s.set("ui.font", "comic"));
    EXPECT_FALSE(s.set("ui.font", "../fonts/x.ttf"));
    EXPECT_TRUE(s.set("theme.iconStyle", "line"));
    EXPECT_FALSE(s.set("theme.iconStyle", "flat"));
    EXPECT_TRUE(s.set("theme.accent", "magenta"));
    EXPECT_TRUE(s.set("theme.accent", "palette"));
    // The look preset and the colour theme are chosen apart.
    EXPECT_TRUE(s.set("theme.palette", "paper"));
    EXPECT_TRUE(s.set("theme.preset", "minimal"));
    EXPECT_EQ(s.get("theme.palette"), "paper");
    EXPECT_EQ(s.get("theme.preset"), "minimal");
}

TEST(Settings, MenuAnimationChoices) {
    Settings s;
    EXPECT_EQ(s.get("menu.animation"), "ribbon");
    for (const char* m : {"ribbon", "slide", "zoom", "curtain", "hinge", "wave", "off"})
        EXPECT_TRUE(s.set("menu.animation", m)) << m;
    EXPECT_FALSE(s.set("menu.animation", "bounce"));
    EXPECT_FALSE(s.set("menu.animation", ""));
    // A layout saved before the setting existed opens with the motion it always had.
    auto doc = json::parse(R"({"motion.mode":"on"})");
    Settings old;
    EXPECT_EQ(old.fromJson(*doc), 0u);
    EXPECT_EQ(old.get("menu.animation"), "ribbon");
}

TEST(Settings, LayoutsSavedBeforeThemesKeepTheirValues) {
    // A 0.1.1 document: no palette, font or icon keys, the old accent and dock default.
    auto doc = json::parse(R"({"theme.accent":"cyan","dock.position":"left","theme.preset":"default"})");
    Settings s;
    EXPECT_EQ(s.fromJson(*doc), 0u);
    EXPECT_EQ(s.get("theme.accent"), "cyan");
    EXPECT_EQ(s.get("dock.position"), "left");
    EXPECT_EQ(s.get("theme.palette"), "plasma");
    EXPECT_EQ(s.get("ui.font"), "roboto");
}

TEST(Settings, RetiredDrawerKeysAreDroppedQuietly) {
    // Layouts saved while LX still had the grid drawer carry its two settings.
    auto doc = json::parse(R"({"drawer.sort":"recent","drawer.layout":"list","clock.24h":"false"})");
    Settings s;
    EXPECT_EQ(s.fromJson(*doc), 0u);
    EXPECT_FALSE(s.getBool("clock.24h"));
    EXPECT_EQ(Settings::spec("drawer.sort"), nullptr);
    EXPECT_FALSE(s.set("drawer.layout", "grid"));
}

TEST(Settings, WindowStyles) {
    Settings s;
    // New installs get LX Modern Dark, the reference look of LX windows.
    EXPECT_EQ(s.get("window.style"), "lxmodern");
    for (const char* id : {"theme", "lxmodern", "minimal", "glass", "cyberpunk", "rounded", "tactical", "compact", "material",
                           "retro", "nature", "linux", "custom"})
        EXPECT_TRUE(s.set("window.style", id)) << id;
    EXPECT_FALSE(s.set("window.style", "vista"));
    EXPECT_EQ(s.get("window.style"), "custom");
}

TEST(Settings, CustomWindowStyleStaysInRange) {
    Settings s;
    EXPECT_EQ(s.get("window.custom.base"), "lxmodern");
    // A custom style cannot be based on itself or on the LX theme.
    EXPECT_FALSE(s.set("window.custom.base", "custom"));
    EXPECT_FALSE(s.set("window.custom.base", "theme"));
    EXPECT_TRUE(s.set("window.custom.corner", "0"));
    EXPECT_FALSE(s.set("window.custom.corner", "29"));
    EXPECT_FALSE(s.set("window.custom.caption", "39"));
    EXPECT_TRUE(s.set("window.custom.caption", "64"));
    EXPECT_FALSE(s.set("window.custom.border", "3.5"));
    EXPECT_TRUE(s.set("window.custom.border", "0.5"));
    EXPECT_FALSE(s.set("window.custom.buttons", "round"));
    EXPECT_TRUE(s.set("window.custom.accent", "teal"));
    EXPECT_TRUE(s.getBool("window.custom.dimInactive"));
}
