// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include "lx/window_manager.hpp"

using namespace lx;

class WindowManagerTest : public ::testing::Test {
protected:
    void SetUp() override {
        wm.setViewport({0, 100, 1000, 800}, {0, 0, 1000, 1000});
        wm.setConstraints({200, 150, 40});
    }
    WindowManager wm;
};

TEST_F(WindowManagerTest, OpenAssignsUniqueIdsAndFocusesNewest) {
    uint32_t a = wm.open("settings", "Settings", 1);
    uint32_t b = wm.open("files", "Files", 1);
    EXPECT_NE(a, 0u);
    EXPECT_NE(a, b);
    EXPECT_EQ(wm.focused(1), b);
    EXPECT_TRUE(wm.focus(a));
    EXPECT_EQ(wm.focused(1), a);
}

TEST(WindowManagerHint, UsesOrientationHintBeforeViewportIsKnown) {
    WindowManager wm;
    wm.setOrientationHint(true);
    uint32_t id = wm.open("welcome", "w", 1);
    wm.setViewport({0, 0, 2000, 1000}, {0, 0, 2000, 1000});
    // Landscape default: a bit over half the width.
    EXPECT_LT(wm.info(id)->bounds.w, 1300);
}

TEST_F(WindowManagerTest, RejectsInvalidOpen) {
    EXPECT_EQ(wm.open("Bad Kind", "x", 1), 0u);
    EXPECT_EQ(wm.open("files", "x", 0), 0u);
}

TEST_F(WindowManagerTest, EnforcesWindowLimit) {
    for (size_t i = 0; i < WindowManager::kMaxWindows; ++i) EXPECT_NE(wm.open("notes", "n", 1), 0u);
    EXPECT_EQ(wm.open("notes", "n", 1), 0u);
}

TEST_F(WindowManagerTest, ResizeNeverProducesNegativeOrTinySizes) {
    uint32_t id = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.resize(id, kEdgeRight | kEdgeBottom, -5000, -5000));
    auto r = wm.info(id)->bounds;
    EXPECT_GE(r.w, 200);
    EXPECT_GE(r.h, 150);
    ASSERT_TRUE(wm.resize(id, kEdgeLeft | kEdgeTop, 5000, 5000));
    r = wm.info(id)->bounds;
    EXPECT_GE(r.w, 200);
    EXPECT_GE(r.h, 150);
}

TEST_F(WindowManagerTest, ResizeIsCappedAtViewport) {
    uint32_t id = wm.open("files", "Files", 1);
    wm.resize(id, kEdgeRight | kEdgeBottom, 9000, 9000);
    auto r = wm.info(id)->bounds;
    EXPECT_LE(r.w, 1000);
    EXPECT_LE(r.h, 800);
}

// A side dragged past the viewport stops at its edge and the side across stays where it was. Before, the window was
// pushed back into the viewport, so the side across moved and the window grew under a finger past the edge.
TEST_F(WindowManagerTest, SideDraggedPastViewportStopsAndOppositeSideStays) {
    uint32_t id = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.resize(id, kEdgeRight | kEdgeBottom, -300, -200));
    ASSERT_TRUE(wm.move(id, 200, 150));
    auto before = wm.info(id)->bounds;

    ASSERT_TRUE(wm.resize(id, kEdgeTop, 0, -5000));
    auto r = wm.info(id)->bounds;
    EXPECT_FLOAT_EQ(r.y, 100);
    EXPECT_FLOAT_EQ(r.bottom(), before.bottom());

    before = r;
    ASSERT_TRUE(wm.resize(id, kEdgeLeft, -5000, 0));
    r = wm.info(id)->bounds;
    EXPECT_FLOAT_EQ(r.x, 0);
    EXPECT_FLOAT_EQ(r.right(), before.right());

    ASSERT_TRUE(wm.resize(id, kEdgeRight, -300, 0));
    before = wm.info(id)->bounds;
    ASSERT_TRUE(wm.resize(id, kEdgeRight, 5000, 0));
    r = wm.info(id)->bounds;
    EXPECT_FLOAT_EQ(r.right(), 1000);
    EXPECT_FLOAT_EQ(r.x, before.x);
}

TEST_F(WindowManagerTest, DragCannotLoseWindowOutsideViewport) {
    uint32_t id = wm.open("files", "Files", 1);
    wm.move(id, -100000, -100000);
    auto r = wm.info(id)->bounds;
    EXPECT_GE(r.x, 0);              // the whole title bar stays on screen
    EXPECT_GE(r.y, 100);            // title bar below the top panel
    wm.move(id, 100000, 100000);
    r = wm.info(id)->bounds;
    EXPECT_LE(r.right(), 1000);     // the buttons at the end of the title bar too
    EXPECT_LE(r.y, 900 - 40);
}

TEST_F(WindowManagerTest, NarrowScreenDragKeepsWindowButtonsReachable) {
    // A 360 dp phone at 2x: a short drag to the right used to push the
    // maximise and close buttons past the screen edge.
    wm.setViewport({0, 100, 720, 1300}, {0, 0, 720, 1600});
    uint32_t id = wm.open("settings", "Settings", 1);
    auto before = wm.info(id)->bounds;
    wm.move(id, 180, 90);
    auto r = wm.info(id)->bounds;
    EXPECT_FLOAT_EQ(r.w, before.w);
    EXPECT_LE(r.right(), 720);
    EXPECT_GE(r.x, 0);
}

TEST_F(WindowManagerTest, IgnoresNonFiniteInput) {
    uint32_t id = wm.open("files", "Files", 1);
    auto before = wm.info(id)->bounds;
    EXPECT_FALSE(wm.move(id, 0.0f / 0.0f, 1));
    EXPECT_FALSE(wm.resize(id, kEdgeRight, 1.0f / 0.0f, 0));
    EXPECT_EQ(wm.info(id)->bounds, before);
}

TEST_F(WindowManagerTest, MaximizeUsesUsableViewport) {
    uint32_t id = wm.open("files", "Files", 1);
    wm.setState(id, WindowState::Maximized);
    EXPECT_EQ(wm.info(id)->bounds, (Rect{0, 100, 1000, 800}));
    wm.setState(id, WindowState::Fullscreen);
    EXPECT_EQ(wm.info(id)->bounds, (Rect{0, 0, 1000, 1000}));
}

TEST_F(WindowManagerTest, SnapSplitsByOrientation) {
    uint32_t id = wm.open("files", "Files", 1);
    wm.setState(id, WindowState::SnappedLeft);
    EXPECT_EQ(wm.info(id)->bounds, (Rect{0, 100, 500, 800}));
    wm.setViewport({0, 0, 600, 1200}, {0, 0, 600, 1300});
    wm.setState(id, WindowState::SnappedRight);
    EXPECT_EQ(wm.info(id)->bounds, (Rect{0, 600, 600, 600}));
}

TEST_F(WindowManagerTest, MinimizeAndRestoreKeepsPreviousState) {
    uint32_t a = wm.open("files", "Files", 1);
    uint32_t b = wm.open("notes", "Notes", 1);
    wm.setState(b, WindowState::Maximized);
    wm.setState(b, WindowState::Minimized);
    EXPECT_EQ(wm.focused(1), a);
    EXPECT_TRUE(wm.restore(b));
    EXPECT_EQ(wm.info(b)->state, WindowState::Maximized);
    EXPECT_EQ(wm.focused(1), b);
}

TEST_F(WindowManagerTest, ToggleLeavesFullscreenToo) {
    uint32_t a = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.setState(a, WindowState::Fullscreen));
    EXPECT_TRUE(wm.toggleMaximize(a));
    EXPECT_EQ(wm.info(a)->state, WindowState::Floating);
    EXPECT_TRUE(wm.toggleMaximize(a));
    EXPECT_EQ(wm.info(a)->state, WindowState::Maximized);
}

TEST_F(WindowManagerTest, LeavingFullscreenGoesBackToTheStateBefore) {
    uint32_t a = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.setState(a, WindowState::Maximized));
    ASSERT_TRUE(wm.setState(a, WindowState::Fullscreen));
    EXPECT_TRUE(wm.leaveFullscreen(a));
    EXPECT_EQ(wm.info(a)->state, WindowState::Maximized);
    EXPECT_FALSE(wm.leaveFullscreen(a));

    // Minimised from full screen and shown again, it still knows where it came from.
    ASSERT_TRUE(wm.setState(a, WindowState::SnappedLeft));
    ASSERT_TRUE(wm.setState(a, WindowState::Fullscreen));
    ASSERT_TRUE(wm.setState(a, WindowState::Minimized));
    ASSERT_TRUE(wm.restore(a));
    EXPECT_EQ(wm.info(a)->state, WindowState::Fullscreen);
    ASSERT_TRUE(wm.setState(a, WindowState::Fullscreen));
    EXPECT_TRUE(wm.leaveFullscreen(a));
    EXPECT_EQ(wm.info(a)->state, WindowState::SnappedLeft);

    // A window that opened full screen has nothing before it and floats.
    uint32_t b = wm.open("notes", "Notes", 1, WindowState::Fullscreen);
    EXPECT_TRUE(wm.leaveFullscreen(b));
    EXPECT_EQ(wm.info(b)->state, WindowState::Floating);
}

TEST_F(WindowManagerTest, TheStateBeforeFullscreenSurvivesARestart) {
    uint32_t a = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.setState(a, WindowState::Maximized));
    ASSERT_TRUE(wm.setState(a, WindowState::Fullscreen));
    WindowManager other;
    ASSERT_EQ(other.fromJson(wm.toJson()), 0u);
    EXPECT_TRUE(other.leaveFullscreen(a));
    EXPECT_EQ(other.info(a)->state, WindowState::Maximized);
}

TEST_F(WindowManagerTest, CloseMovesFocusToNextWindow) {
    uint32_t a = wm.open("files", "Files", 1);
    uint32_t b = wm.open("notes", "Notes", 1);
    EXPECT_TRUE(wm.close(b));
    EXPECT_FALSE(wm.close(b));
    EXPECT_EQ(wm.focused(1), a);
    EXPECT_TRUE(wm.close(a));
    EXPECT_FALSE(wm.focused(1).has_value());
}

TEST_F(WindowManagerTest, DraggingMaximizedWindowDetachesIt) {
    uint32_t id = wm.open("files", "Files", 1);
    wm.setState(id, WindowState::Maximized);
    wm.move(id, 10, 10);
    EXPECT_EQ(wm.info(id)->state, WindowState::Floating);
}

TEST_F(WindowManagerTest, BoundsFollowRotation) {
    uint32_t id = wm.open("files", "Files", 1);
    auto before = wm.info(id)->bounds;
    wm.setViewport({0, 50, 500, 1600}, {0, 0, 500, 1700});
    auto after = wm.info(id)->bounds;
    EXPECT_NE(before, after);
    EXPECT_GE(after.x, -after.w + 60);
    EXPECT_LE(after.w, 500);
    // Back to the first orientation restores the same geometry.
    wm.setViewport({0, 100, 1000, 800}, {0, 0, 1000, 1000});
    auto again = wm.info(id)->bounds;
    EXPECT_NEAR(again.x, before.x, 0.5);
    EXPECT_NEAR(again.w, before.w, 0.5);
}

TEST_F(WindowManagerTest, WorkspacesKeepSeparateFocus) {
    uint32_t a = wm.open("files", "Files", 1);
    uint32_t b = wm.open("files", "Files", 2);
    EXPECT_EQ(wm.focused(1), a);
    EXPECT_EQ(wm.focused(2), b);
    EXPECT_TRUE(wm.moveToWorkspace(a, 2));
    EXPECT_EQ(wm.windowsIn(2).size(), 2u);
    EXPECT_TRUE(wm.windowsIn(1).empty());
    wm.reassignWorkspace(2, 3);
    EXPECT_EQ(wm.windowsIn(3).size(), 2u);
}

TEST_F(WindowManagerTest, TileCoversViewportWithoutOverlap) {
    for (int i = 0; i < 4; ++i) wm.open("notes", "n", 1);
    wm.tile(1);
    auto wins = wm.windowsIn(1);
    ASSERT_EQ(wins.size(), 4u);
    for (size_t i = 0; i < wins.size(); ++i) {
        EXPECT_EQ(wins[i].state, WindowState::Floating);
        for (size_t j = i + 1; j < wins.size(); ++j) {
            const Rect& a = wins[i].bounds;
            const Rect& b = wins[j].bounds;
            bool overlap = a.x < b.right() - 1 && b.x < a.right() - 1 && a.y < b.bottom() - 1 && b.y < a.bottom() - 1;
            EXPECT_FALSE(overlap) << i << " " << j;
        }
    }
}

TEST_F(WindowManagerTest, HitTestReturnsTopmost) {
    uint32_t a = wm.open("files", "Files", 1);
    uint32_t b = wm.open("notes", "Notes", 1);
    wm.setState(a, WindowState::Maximized);
    wm.setState(b, WindowState::Maximized);
    EXPECT_EQ(wm.hitTest(1, 500, 500), b);
    wm.focus(a);
    EXPECT_EQ(wm.hitTest(1, 500, 500), a);
    EXPECT_FALSE(wm.hitTest(1, 500, 50).has_value());
}

TEST_F(WindowManagerTest, SnapTargetFromDropPoint) {
    EXPECT_EQ(wm.snapTarget(500, 105, 24), WindowState::Maximized);
    EXPECT_EQ(wm.snapTarget(5, 500, 24), WindowState::SnappedLeft);
    EXPECT_EQ(wm.snapTarget(995, 500, 24), WindowState::SnappedRight);
    EXPECT_FALSE(wm.snapTarget(500, 500, 24).has_value());
}

TEST_F(WindowManagerTest, JsonRoundTripPreservesState) {
    uint32_t a = wm.open("files", "Files", 1);
    uint32_t b = wm.open("notes", "Notes", 2);
    wm.move(a, 33, 44);
    wm.setState(b, WindowState::Minimized);
    WindowManager other;
    other.setViewport({0, 100, 1000, 800}, {0, 0, 1000, 1000});
    other.setConstraints({200, 150, 40});
    EXPECT_EQ(other.fromJson(wm.toJson()), 0u);
    EXPECT_NEAR(other.info(a)->bounds.x, wm.info(a)->bounds.x, 0.01);
    EXPECT_EQ(other.info(b)->state, WindowState::Minimized);
    // New ids never collide with restored ones.
    uint32_t c = other.open("monitor", "Monitor", 1);
    EXPECT_GT(c, b);
}

TEST_F(WindowManagerTest, FromJsonSkipsInvalidEntries) {
    auto doc = json::parse(R"([
        {"id":1,"kind":"files","ws":1,"state":"floating","x":0.1,"y":0.1,"w":0.5,"h":0.5},
        {"id":1,"kind":"files","ws":1,"state":"floating","x":0.1,"y":0.1,"w":0.5,"h":0.5},
        {"id":2,"kind":"../etc","ws":1,"state":"floating"},
        {"id":3,"kind":"files","ws":1,"state":"exploded"},
        {"id":4,"kind":"files","ws":1,"state":"floating","w":-1},
        {"kind":"files","ws":1,"state":"floating"}
    ])");
    ASSERT_TRUE(doc);
    EXPECT_EQ(wm.fromJson(*doc), 5u);
    EXPECT_EQ(wm.all().size(), 1u);
}

TEST_F(WindowManagerTest, ReopeningAToolUsesWhereItLastFloated) {
    uint32_t a = wm.open("files", "Files", 1);
    ASSERT_TRUE(wm.move(a, 120, 80));
    ASSERT_TRUE(wm.resize(a, kEdgeRight | kEdgeBottom, -60, 40));
    Rect before = wm.info(a)->bounds;
    ASSERT_TRUE(wm.close(a));
    uint32_t b = wm.open("files", "Files", 1);
    Rect after = wm.info(b)->bounds;
    EXPECT_NEAR(after.x, before.x, 0.5f);
    EXPECT_NEAR(after.y, before.y, 0.5f);
    EXPECT_NEAR(after.w, before.w, 0.5f);
    EXPECT_NEAR(after.h, before.h, 0.5f);
    // Another kind still opens at the default place.
    uint32_t c = wm.open("notes", "Notes", 1);
    EXPECT_FALSE(wm.info(c)->bounds.x == after.x && wm.info(c)->bounds.y == after.y);
}

TEST_F(WindowManagerTest, MaximisedWindowRemembersItsFloatingPlace) {
    uint32_t a = wm.open("settings", "Settings", 1);
    ASSERT_TRUE(wm.move(a, 50, 60));
    Rect floating = wm.info(a)->bounds;
    ASSERT_TRUE(wm.setState(a, WindowState::Maximized));
    ASSERT_TRUE(wm.close(a));
    uint32_t b = wm.open("settings", "Settings", 1);
    EXPECT_NEAR(wm.info(b)->bounds.x, floating.x, 0.5f);
    EXPECT_NEAR(wm.info(b)->bounds.y, floating.y, 0.5f);
}

TEST_F(WindowManagerTest, PlacesSurviveJsonAndRejectBadEntries) {
    uint32_t a = wm.open("monitor", "Monitor", 1);
    ASSERT_TRUE(wm.move(a, 30, 40));
    ASSERT_TRUE(wm.close(a));
    auto json = wm.placesToJson();
    WindowManager other;
    other.setViewport(wm.viewport(), wm.fullArea());
    EXPECT_EQ(other.placesFromJson(json), 0u);
    ASSERT_TRUE(other.rememberedPlace("monitor").has_value());
    EXPECT_FLOAT_EQ(other.rememberedPlace("monitor")->x, wm.rememberedPlace("monitor")->x);

    auto bad = json::parse(R"([{"kind":"Bad Kind","x":0.1,"y":0.1,"w":0.5,"h":0.5},)"
                           R"({"kind":"files","x":"nan","y":0.1,"w":-1,"h":0.5},)"
                           R"({"kind":"files","x":0.1,"y":0.1,"w":0.5,"h":0.5},)"
                           R"({"kind":"files","x":0.2,"y":0.2,"w":0.5,"h":0.5}])");
    ASSERT_TRUE(bad.has_value());
    EXPECT_EQ(other.placesFromJson(*bad), 3u);
    EXPECT_FLOAT_EQ(other.rememberedPlace("files")->x, 0.1f);
}

TEST_F(WindowManagerTest, PlacesAreCapped) {
    for (int i = 0; i < 40; ++i) {
        uint32_t id = wm.open("tool" + std::to_string(i), "T", 1);
        ASSERT_NE(id, 0u);
        ASSERT_TRUE(wm.close(id));
    }
    EXPECT_EQ(wm.placesToJson().asArray().size(), WindowManager::kMaxPlaces);
    // The most recently closed kinds are the ones kept.
    EXPECT_TRUE(wm.rememberedPlace("tool39").has_value());
    EXPECT_FALSE(wm.rememberedPlace("tool0").has_value());
}

