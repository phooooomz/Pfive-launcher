// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// One test per bug found during development, named after what broke.
#include <gtest/gtest.h>

#include "lx/desktop.hpp"
#include "lx/engine.hpp"
#include "lx/json.hpp"

using namespace lx;

// Copies of a json::Value shared one container, so editing a copy of the
// document edited the original too.
TEST(Regression, JsonCopyOnWrite) {
    json::Value a{json::Object{}};
    a.set("k", 1);
    json::Value b = a;
    b.set("k", 2);
    EXPECT_DOUBLE_EQ(a.find("k")->asNumber(), 1);
}

// Taking the last app out of a folder must not touch the folder after it
// has been deleted.
TEST(Regression, LastAppOutOfFolder) {
    Desktop d;
    uint32_t ws = d.addWorkspace("w");
    uint32_t a = d.addShortcut(ws, "com.example.a/com.example.A#0");
    uint32_t b = d.addShortcut(ws, "com.example.b/com.example.B#0");
    uint32_t f = d.createFolder(a, b, "f");
    EXPECT_NE(d.removeFromFolder(f, "com.example.a/com.example.A#0"), 0u);
    EXPECT_NE(d.removeFromFolder(f, "com.example.b/com.example.B#0"), 0u);
    EXPECT_TRUE(d.folders().empty());
    EXPECT_EQ(d.shortcuts().size(), 2u);
}

// Importing must not reset host-provided window constraints or the viewport.
TEST(Regression, ImportKeepsViewport) {
    Engine e;
    e.windows().setViewport({10, 20, 700, 1200}, {0, 0, 720, 1600});
    e.windows().setConstraints({300, 200, 48});
    std::string doc = e.exportDocument();
    ASSERT_TRUE(e.importDocument(doc).ok);
    EXPECT_EQ(e.windows().viewport(), (Rect{10, 20, 700, 1200}));
    EXPECT_EQ(e.windows().fullArea(), (Rect{0, 0, 720, 1600}));
    EXPECT_FLOAT_EQ(e.windows().constraints().minWidth, 300);
}
