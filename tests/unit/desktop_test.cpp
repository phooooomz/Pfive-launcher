// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include "lx/desktop.hpp"

using namespace lx;

namespace {
const std::string kA = "com.example.mail/com.example.mail.Main#0";
const std::string kB = "org.example.maps/org.example.maps.MapActivity#0";
const std::string kC = "net.example.chat/net.example.chat.Launch$Alias#10";
}  // namespace

class DesktopTest : public ::testing::Test {
protected:
    void SetUp() override {
        for (const char* n : {"One", "Two", "Three", "Four"}) ids.push_back(d.addWorkspace(n));
        d.setGrid(4, 4);
    }
    Desktop d;
    std::vector<uint32_t> ids;
};

TEST_F(DesktopTest, CreatesFourWorkspacesWithStableIds) {
    ASSERT_EQ(d.workspaces().size(), 4u);
    EXPECT_EQ(d.active(), ids[0]);
    EXPECT_TRUE(d.renameWorkspace(ids[1], "  Work  "));
    EXPECT_EQ(d.workspace(ids[1])->name, "Work");
    EXPECT_FALSE(d.renameWorkspace(ids[1], "   "));
    EXPECT_FALSE(d.renameWorkspace(999, "x"));
}

TEST_F(DesktopTest, WorkspaceLimits) {
    while (d.workspaces().size() < Desktop::kMaxWorkspaces) ASSERT_NE(d.addWorkspace("w"), 0u);
    EXPECT_EQ(d.addWorkspace("w"), 0u);
    Desktop single;
    uint32_t only = single.addWorkspace("Only");
    EXPECT_FALSE(single.removeWorkspace(only, 0).has_value());
}

TEST_F(DesktopTest, NeighbourWraps) {
    EXPECT_EQ(d.neighbour(1), ids[1]);
    EXPECT_EQ(d.neighbour(-1), ids[3]);
    d.setActive(ids[3]);
    EXPECT_EQ(d.neighbour(1), ids[0]);
}

TEST_F(DesktopTest, RemoveWorkspaceMovesContent) {
    uint32_t s = d.addShortcut(ids[1], kA);
    uint32_t w = d.addWidget(ids[1], "clock", -1, 0, 0, 0.5f, 0.2f);
    d.setActive(ids[1]);
    auto released = d.removeWorkspace(ids[1], ids[2]);
    ASSERT_TRUE(released);
    EXPECT_TRUE(released->empty());
    EXPECT_EQ(d.active(), ids[2]);
    EXPECT_EQ(d.shortcuts().front().workspaceId, ids[2]);
    EXPECT_EQ(d.widget(w)->workspaceId, ids[2]);
    EXPECT_EQ(d.shortcuts().front().id, s);
}

TEST_F(DesktopTest, RemoveWorkspaceDeletesContentAndReleasesWidgets) {
    d.addShortcut(ids[1], kA);
    d.addWidget(ids[1], "appwidget", 42, 0, 0, 0.5f, 0.2f);
    auto released = d.removeWorkspace(ids[1], 0);
    ASSERT_TRUE(released);
    ASSERT_EQ(released->size(), 1u);
    EXPECT_EQ((*released)[0], 42);
    EXPECT_TRUE(d.shortcuts().empty());
    EXPECT_TRUE(d.widgets().empty());
    EXPECT_FALSE(d.removeWorkspace(ids[2], ids[2]).has_value());
    EXPECT_FALSE(d.removeWorkspace(ids[2], 999).has_value());
}

TEST_F(DesktopTest, NewShortcutsSkipCellsUnderWidgets) {
    // Phone canvas: the default widgets run the full width, so the first free
    // cell used to land on top of the clock.
    d.setCanvasAspect(0.5f);
    d.addWidget(ids[0], "clock", -1, 0.0f, 0.0f, 1.0f, 0.5f);
    uint32_t a = d.addShortcut(ids[0], kA);
    EXPECT_EQ(d.shortcuts()[0].cell, (Cell{0, 2}));
    // Another workspace's widget does not count.
    d.addWidget(ids[1], "status", -1, 0.0f, 0.5f, 1.0f, 0.5f);
    EXPECT_EQ(d.addShortcut(ids[0], kB), a + 2);
    EXPECT_EQ(d.shortcuts()[1].cell, (Cell{1, 2}));
    // With every uncovered cell taken, a covered one still beats "desktop full".
    for (int i = 0; i < 6; ++i) EXPECT_NE(d.addShortcut(ids[0], "com.example.fill" + std::to_string(i) + "/x.Main#0"), 0u);
    uint32_t last = d.addShortcut(ids[0], kC);
    ASSERT_NE(last, 0u);
    EXPECT_EQ(d.shortcuts().back().cell, (Cell{0, 0}));
}

TEST_F(DesktopTest, MovingAWidgetOverIconsMovesThemAside) {
    d.setCanvasAspect(0.5f);
    uint32_t w = d.addWidget(ids[0], "notes", -1, 0.0f, 0.0f, 1.0f, 0.25f);
    d.addShortcut(ids[0], kA, Cell{0, 3});
    d.addShortcut(ids[1], kB, Cell{0, 3});
    // Dragged down over the bottom row: the icon there moves to a clear cell.
    ASSERT_TRUE(d.setWidgetRect(w, 0.0f, 0.75f, 1.0f, 0.25f));
    EXPECT_EQ(d.shortcuts()[0].cell, (Cell{0, 0}));
    // An icon on another workspace stays put.
    EXPECT_EQ(d.shortcuts()[1].cell, (Cell{0, 3}));
}

TEST_F(DesktopTest, AutoPlacedWidgetsAvoidWidgetsAndIcons) {
    // Widgets added from LX Settings used to stack at the top left, on top of
    // whatever was there.
    d.setCanvasAspect(0.5f);
    uint32_t clock = d.addWidget(ids[0], "clock", -1, 0.03f, 0.01f, 0.94f, 0.14f);
    d.addShortcut(ids[0], kA, Cell{0, 1});  // rows are 0.25 tall on the 4x4 grid
    uint32_t notes = d.addWidget(ids[0], "notes", -1, -1, -1, 0.94f, 0.2f);
    ASSERT_NE(notes, 0u);
    WidgetRect c = d.widgetRect(*d.widget(clock));
    WidgetRect n = d.widgetRect(*d.widget(notes));
    EXPECT_GE(n.y, c.y + c.h);   // below the clock
    EXPECT_GE(n.y, 0.5f);        // and below the shortcut in row 1
    EXPECT_LE(n.y + n.h, 1.0f);
    // With no free spot left the widget is still added rather than refused.
    uint32_t big = d.addWidget(ids[0], "status", -1, -1, -1, 1.0f, 0.9f);
    EXPECT_NE(big, 0u);
}

TEST_F(DesktopTest, ShortcutsFillFreeCellsAndDeduplicate) {
    uint32_t a = d.addShortcut(ids[0], kA);
    EXPECT_EQ(d.addShortcut(ids[0], kA), a);
    uint32_t b = d.addShortcut(ids[0], kB, Cell{3, 3});
    EXPECT_EQ(d.shortcuts()[1].cell, (Cell{3, 3}));
    EXPECT_NE(a, b);
    EXPECT_EQ(d.addShortcut(ids[0], "not a key"), 0u);
    EXPECT_EQ(d.addShortcut(99, kC), 0u);
}

TEST_F(DesktopTest, GridFullRefusesNewShortcut) {
    for (int i = 0; i < 16; ++i) {
        std::string key = "com.example.app" + std::to_string(i) + "/com.example.Main#0";
        ASSERT_NE(d.addShortcut(ids[0], key), 0u) << i;
    }
    EXPECT_EQ(d.addShortcut(ids[0], kC), 0u);
}

TEST_F(DesktopTest, MoveSwapsOccupants) {
    uint32_t a = d.addShortcut(ids[0], kA, Cell{0, 0});
    uint32_t b = d.addShortcut(ids[0], kB, Cell{1, 0});
    EXPECT_TRUE(d.moveItem(a, {1, 0}));
    EXPECT_EQ(d.shortcuts()[0].cell, (Cell{1, 0}));
    EXPECT_EQ(d.shortcuts()[1].cell, (Cell{0, 0}));
    EXPECT_FALSE(d.moveItem(b, {9, 9}));
    EXPECT_FALSE(d.moveItem(999, {0, 0}));
}

TEST_F(DesktopTest, FolderLifecycle) {
    uint32_t a = d.addShortcut(ids[0], kA, Cell{0, 0});
    uint32_t b = d.addShortcut(ids[0], kB, Cell{1, 0});
    uint32_t c = d.addShortcut(ids[0], kC, Cell{2, 0});
    uint32_t f = d.createFolder(a, b, "Tools");
    ASSERT_NE(f, 0u);
    EXPECT_EQ(d.folders().front().cell, (Cell{0, 0}));
    EXPECT_EQ(d.folders().front().apps.size(), 2u);
    EXPECT_TRUE(d.addToFolder(f, c));
    EXPECT_EQ(d.folders().front().apps.size(), 3u);
    EXPECT_TRUE(d.shortcuts().empty());
    EXPECT_TRUE(d.renameFolder(f, "Daily"));
    EXPECT_EQ(d.folders().front().name, "Daily");
    uint32_t back = d.removeFromFolder(f, kC);
    EXPECT_NE(back, 0u);
    EXPECT_EQ(d.shortcuts().size(), 1u);
    d.removeFromFolder(f, kA);
    d.removeFromFolder(f, kB);
    EXPECT_TRUE(d.folders().empty());
    EXPECT_EQ(d.shortcuts().size(), 3u);
}

TEST_F(DesktopTest, UninstallInOneProfileKeepsTheOtherProfilesApp) {
    // Removing an app from the work profile used to take the personal profile's copy with it.
    const std::string personal = kA;                                      // user serial 0
    const std::string work = "com.example.mail/com.example.mail.Main#10";  // user serial 10
    d.addShortcut(ids[0], personal);
    d.addShortcut(ids[0], work, Cell{1, 1});
    d.pin(PinList::Dock, "app:" + personal);
    d.pin(PinList::Dock, "app:" + work);
    d.pin(PinList::Favorites, personal);
    d.pin(PinList::Recent, work);
    size_t removed = d.removePackage("com.example.mail", 10);
    EXPECT_EQ(removed, 3u);
    ASSERT_EQ(d.shortcuts().size(), 1u);
    EXPECT_EQ(d.shortcuts().front().appKey, personal);
    EXPECT_TRUE(d.isPinned(PinList::Dock, "app:" + personal));
    EXPECT_FALSE(d.isPinned(PinList::Dock, "app:" + work));
    EXPECT_TRUE(d.isPinned(PinList::Favorites, personal));
    EXPECT_FALSE(d.isPinned(PinList::Recent, work));
}

TEST_F(DesktopTest, UninstallWithoutAProfileStillRemovesEveryCopy) {
    d.addShortcut(ids[0], kA);
    d.addShortcut(ids[0], "com.example.mail/com.example.mail.Main#10", Cell{1, 1});
    EXPECT_EQ(d.removePackage("com.example.mail", 11), 0u);
    EXPECT_EQ(d.removePackage("com.example.mail"), 2u);
    EXPECT_TRUE(d.shortcuts().empty());
}

TEST_F(DesktopTest, RemovingShortcutKeepsOtherReferences) {
    uint32_t a = d.addShortcut(ids[0], kA);
    d.pin(PinList::Taskbar, "app:" + kA);
    EXPECT_TRUE(d.removeItem(a));
    EXPECT_TRUE(d.isPinned(PinList::Taskbar, "app:" + kA));
}

TEST_F(DesktopTest, UninstallRemovesEveryReference) {
    d.addShortcut(ids[0], kA);
    d.addShortcut(ids[1], kA);
    uint32_t b = d.addShortcut(ids[0], kB, Cell{2, 2});
    uint32_t c = d.addShortcut(ids[0], kC, Cell{3, 3});
    d.createFolder(b, c, "F");
    d.pin(PinList::Dock, "app:" + kA);
    d.pin(PinList::Favorites, kA);
    d.pin(PinList::Recent, kA);
    size_t removed = d.removePackage("com.example.mail");
    EXPECT_EQ(removed, 5u);
    EXPECT_EQ(d.shortcuts().size(), 0u);
    EXPECT_EQ(d.folders().front().apps.size(), 2u);
    EXPECT_FALSE(d.isPinned(PinList::Dock, "app:" + kA));
}

TEST_F(DesktopTest, WidgetGeometryIsClamped) {
    uint32_t w = d.addWidget(ids[0], "clock", -1, 0.9f, 0.9f, 0.5f, 0.5f);
    WidgetRect r = d.widgetRect(*d.widget(w));
    EXPECT_LE(r.x + r.w, 1.0001f);
    EXPECT_LE(r.y + r.h, 1.0001f);
    EXPECT_TRUE(d.setWidgetRect(w, -1, -1, 0.0f, 9));
    r = d.widgetRect(*d.widget(w));
    EXPECT_GE(r.w, 0.05f);
    EXPECT_LE(r.h, 1.0f);
    EXPECT_GE(r.x, 0.0f);
    EXPECT_FALSE(d.setWidgetRect(w, 0.0f / 0.0f, 0, 0.1f, 0.1f));
}

TEST_F(DesktopTest, OrientationsKeepSeparateGeometry) {
    d.setCanvasAspect(0.5f);  // portrait phone canvas
    uint32_t w = d.addWidget(ids[0], "status", -1, 0.0f, 0.2f, 0.9f, 0.25f);
    d.setCanvasAspect(2.0f);  // rotate
    WidgetRect derived = d.widgetRect(*d.widget(w));
    EXPECT_FALSE(derived.set);
    EXPECT_NEAR(derived.w, 0.9f * 0.5f / 2.0f, 1e-4);  // same physical width
    EXPECT_NEAR(derived.y, 0.2f, 1e-4);
    EXPECT_TRUE(d.setWidgetRect(w, 0.6f, 0.1f, 0.3f, 0.4f));
    d.setCanvasAspect(0.5f);  // back to portrait: untouched
    WidgetRect back = d.widgetRect(*d.widget(w));
    EXPECT_TRUE(back.set);
    EXPECT_NEAR(back.w, 0.9f, 1e-4);
    d.setCanvasAspect(2.0f);
    EXPECT_NEAR(d.widgetRect(*d.widget(w)).x, 0.6f, 1e-4);
    // Both rectangles survive a round trip.
    Desktop other;
    other.fromJson(d.toJson());
    other.setCanvasAspect(0.5f);
    EXPECT_NEAR(other.widgetRect(*other.widget(w)).w, 0.9f, 1e-4);
}

TEST_F(DesktopTest, WidgetValidation) {
    EXPECT_EQ(d.addWidget(ids[0], "appwidget", -1, 0, 0, 0.3f, 0.3f), 0u);
    EXPECT_EQ(d.addWidget(ids[0], "clock", 5, 0, 0, 0.3f, 0.3f), 0u);
    EXPECT_EQ(d.addWidget(ids[0], "Bad Kind", -1, 0, 0, 0.3f, 0.3f), 0u);
    for (size_t i = 0; i < Desktop::kMaxWidgetsPerWorkspace; ++i)
        ASSERT_NE(d.addWidget(ids[0], "clock", -1, 0, 0, 0.3f, 0.3f), 0u);
    EXPECT_EQ(d.addWidget(ids[0], "clock", -1, 0, 0, 0.3f, 0.3f), 0u);
}

TEST_F(DesktopTest, WidgetConfigLimitsAndNewlines) {
    uint32_t w = d.addWidget(ids[0], "notes", -1, 0, 0, 0.3f, 0.3f);
    EXPECT_TRUE(d.setWidgetConfig(w, "text", "line one\nline two\x01"));
    EXPECT_EQ(d.widget(w)->config.at("text"), "line one\nline two");
    EXPECT_FALSE(d.setWidgetConfig(w, "text", std::string(Desktop::kMaxConfigValue + 1, 'x')));
    EXPECT_FALSE(d.setWidgetConfig(w, "Bad Key", "v"));
    for (size_t i = 1; i < Desktop::kMaxWidgetConfig; ++i)
        ASSERT_TRUE(d.setWidgetConfig(w, "k" + std::to_string(i), "v"));
    EXPECT_FALSE(d.setWidgetConfig(w, "overflow", "v"));
    EXPECT_TRUE(d.setWidgetConfig(w, "text", "replace existing still works"));
}

TEST_F(DesktopTest, RemoveWidgetReturnsAppWidgetId) {
    uint32_t w = d.addWidget(ids[0], "appwidget", 7, 0, 0, 0.3f, 0.3f);
    auto id = d.removeWidget(w);
    ASSERT_TRUE(id);
    EXPECT_EQ(*id, 7);
    EXPECT_FALSE(d.removeWidget(w).has_value());
}

TEST_F(DesktopTest, PinListsValidateAndReorder) {
    EXPECT_TRUE(d.pin(PinList::Dock, "tool:files"));
    EXPECT_TRUE(d.pin(PinList::Dock, "app:" + kA));
    EXPECT_FALSE(d.pin(PinList::Dock, "tool:files"));
    EXPECT_FALSE(d.pin(PinList::Dock, "shell:rm -rf"));
    EXPECT_FALSE(d.pin(PinList::Favorites, "tool:files"));
    EXPECT_TRUE(d.movePin(PinList::Dock, "app:" + kA, -1));
    EXPECT_EQ(d.pins(PinList::Dock).front(), "app:" + kA);
    EXPECT_TRUE(d.unpin(PinList::Dock, "tool:files"));
    EXPECT_FALSE(d.unpin(PinList::Dock, "tool:files"));
}

TEST_F(DesktopTest, RecentListIsMostRecentFirstAndBounded) {
    for (int i = 0; i < 20; ++i)
        d.pin(PinList::Recent, "com.example.app" + std::to_string(i) + "/x.Main#0");
    d.pin(PinList::Recent, "com.example.app5/x.Main#0");
    const auto& r = d.pins(PinList::Recent);
    EXPECT_EQ(r.size(), 12u);
    EXPECT_EQ(r.front(), "com.example.app5/x.Main#0");
}

TEST_F(DesktopTest, ShrinkingGridRelocatesItems) {
    d.setGrid(6, 6);
    uint32_t a = d.addShortcut(ids[0], kA, Cell{5, 5});
    d.setGrid(3, 3);
    const auto& s = d.shortcuts().front();
    EXPECT_EQ(s.id, a);
    EXPECT_LT(s.cell.col, 3);
    EXPECT_LT(s.cell.row, 3);
}

TEST_F(DesktopTest, JsonRoundTrip) {
    uint32_t a = d.addShortcut(ids[0], kA);
    uint32_t b = d.addShortcut(ids[0], kB);
    d.createFolder(a, b, "Pair");
    uint32_t w = d.addWidget(ids[2], "notes", -1, 0.1f, 0.2f, 0.3f, 0.4f);
    d.setWidgetConfig(w, "text", "hello\nworld");
    d.setWallpaper(ids[1], "color:#112233");
    EXPECT_TRUE(d.setWallpaper(ids[3], "local:3f2a9c.img"));
    EXPECT_FALSE(d.setWallpaper(ids[3], "local:../../etc/passwd"));
    EXPECT_FALSE(d.setWallpaper(ids[3], "local:"));
    d.pin(PinList::Taskbar, "app:" + kC);
    d.setActive(ids[2]);
    Desktop other;
    EXPECT_EQ(other.fromJson(d.toJson()), 0u);
    EXPECT_EQ(json::serialize(other.toJson()), json::serialize(d.toJson()));
    // New ids continue after restored ones.
    EXPECT_GT(other.addShortcut(ids[3], kA), w);
}

TEST_F(DesktopTest, FromJsonKeepsValidEntriesWhenSomeAreBroken) {
    auto doc = json::parse(R"JSON({
      "workspaces":[{"id":1,"name":"A"},{"id":1,"name":"dup"},{"id":2,"name":""},{"id":3,"name":"C","wallpaper":"file:///etc/passwd"}],
      "active":3, "cols":4, "rows":4,
      "shortcuts":[
        {"id":10,"ws":1,"app":"com.example.mail/com.example.mail.Main#0","col":0,"row":0},
        {"id":11,"ws":1,"app":"../../evil","col":1,"row":0},
        {"id":12,"ws":99,"app":"com.example.mail/com.example.mail.Main#0","col":1,"row":0},
        {"id":13,"ws":1,"app":"org.example.maps/org.example.maps.MapActivity#0","col":0,"row":0}
      ],
      "widgets":[{"id":20,"ws":3,"kind":"clock","x":5,"y":5,"w":3,"h":3},{"id":10,"ws":1,"kind":"clock"}],
      "dock":["tool:files","javascript:alert(1)"]
    })JSON");
    ASSERT_TRUE(doc);
    size_t skipped = d.fromJson(*doc);
    EXPECT_EQ(skipped, 6u);
    EXPECT_EQ(d.workspaces().size(), 2u);
    EXPECT_EQ(d.active(), 3u);
    EXPECT_EQ(d.workspace(3)->wallpaper, "builtin:theme");
    ASSERT_EQ(d.shortcuts().size(), 2u);
    // Both claimed cell (0,0); the second was moved.
    EXPECT_FALSE(d.shortcuts()[0].cell == d.shortcuts()[1].cell);
    ASSERT_EQ(d.widgets().size(), 1u);
    WidgetRect r = d.widgetRect(d.widgets()[0]);
    EXPECT_LE(r.x + r.w, 1.0001f);
    EXPECT_EQ(d.pins(PinList::Dock).size(), 1u);
}

TEST_F(DesktopTest, FromJsonOfGarbageStillLeavesAWorkspace) {
    EXPECT_EQ(d.fromJson(json::Value(42)), 0u);
    EXPECT_EQ(d.workspaces().size(), 1u);
    EXPECT_NE(d.active(), 0u);
}

TEST_F(DesktopTest, OneWallpaperForEveryWorkspace) {
    EXPECT_TRUE(d.setWallpaper(ids[2], "builtin:deep"));
    for (const auto& w : d.workspaces()) EXPECT_EQ(w.wallpaper, "builtin:deep") << w.name;
    EXPECT_FALSE(d.setWallpaper(9999, "builtin:rain"));
    EXPECT_EQ(d.workspaces().front().wallpaper, "builtin:deep");
    uint32_t added = d.addWorkspace("Fifth");
    ASSERT_NE(added, 0u);
    EXPECT_EQ(d.workspace(added)->wallpaper, "builtin:deep");
}

TEST_F(DesktopTest, LayoutsWithAWallpaperPerWorkspaceTakeTheActiveOne) {
    auto doc = json::parse(R"({"workspaces":[{"id":1,"name":"A","wallpaper":"builtin:rain"},)"
                           R"({"id":2,"name":"B","wallpaper":"color:#112233"},{"id":3,"name":"C"}],"active":2})");
    ASSERT_TRUE(doc);
    Desktop loaded;
    EXPECT_EQ(loaded.fromJson(*doc), 0u);
    ASSERT_EQ(loaded.workspaces().size(), 3u);
    for (const auto& w : loaded.workspaces()) EXPECT_EQ(w.wallpaper, "color:#112233") << w.name;
}
