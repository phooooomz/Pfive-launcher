// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <cstdlib>
#include <string>

#include <unistd.h>

#include "lx/engine.hpp"
#include "lx/persistence.hpp"
#include "lx/validate.hpp"

#include <cmath>

using namespace lx;

namespace {

DefaultsSpec defaults(bool landscape = false) {
    return {{"Workspace 1", "Workspace 2", "Workspace 3", "Workspace 4"}, "Welcome", landscape};
}

std::string tempPath() {
    char tmpl[] = "/tmp/lxengineXXXXXX";
    std::string dir = mkdtemp(tmpl);
    return dir + "/layout.lxl";
}

}  // namespace

TEST(Engine, DefaultsHaveFourWorkspacesWidgetsAndDock) {
    Engine e;
    e.resetToDefaults(defaults());
    EXPECT_EQ(e.desktop().workspaces().size(), 4u);
    EXPECT_EQ(e.desktop().widgets().size(), 3u);  // portrait keeps room for icons
    Engine wide;
    wide.resetToDefaults(defaults(true));
    EXPECT_EQ(wide.desktop().widgets().size(), 4u);
    EXPECT_FALSE(e.desktop().pins(PinList::Dock).empty());
    ASSERT_EQ(e.windows().all().size(), 1u);
    EXPECT_EQ(e.windows().all()[0].kind, "welcome");
}

TEST(Engine, PortraitDefaultsLeaveRoomForShortcuts) {
    // On a phone the four default widgets used to cover the whole canvas, so
    // the first shortcut landed on top of a widget.
    Engine e;
    e.resetToDefaults(defaults());  // portrait
    auto& d = e.desktop();
    ASSERT_FALSE(d.landscape());
    for (const auto& w : d.widgets()) {
        WidgetRect r = d.widgetRect(w);
        EXPECT_LE(r.y + r.h, 0.66f) << w.kind;
    }
    // Two full rows of the 5x6 grid stay free for icons.
    for (int i = 0; i < 2 * d.cols(); ++i)
        ASSERT_NE(d.addShortcut(d.active(), "com.example.app" + std::to_string(i) + "/x.Main#0"), 0u);
    for (const auto& s : d.shortcuts()) EXPECT_GE(s.cell.row, d.rows() - 2) << s.appKey;
}

TEST(Engine, ExportImportRoundTrip) {
    Engine a;
    a.resetToDefaults(defaults());
    a.setSetting("dock.position", "right");
    a.desktop().addShortcut(a.desktop().active(), "com.example.mail/com.example.mail.Main#0");
    a.windows().open("files", "Files", a.desktop().active());
    std::string doc = a.exportDocument();

    Engine b;
    LoadReport r = b.importDocument(doc);
    ASSERT_TRUE(r.ok) << r.error;
    EXPECT_EQ(r.skipped, 0u);
    EXPECT_EQ(b.exportDocument(), doc);
}

TEST(Engine, ImportRefusesGarbageAndKeepsState) {
    Engine e;
    e.resetToDefaults(defaults());
    std::string before = e.exportDocument();
    EXPECT_FALSE(e.importDocument("not json").ok);
    EXPECT_FALSE(e.importDocument("{\"format\":\"something-else\",\"version\":1}").ok);
    EXPECT_FALSE(e.importDocument("{\"format\":\"lx-launcher-layout\"}").ok);
    LoadReport future = e.importDocument("{\"format\":\"lx-launcher-layout\",\"version\":99}");
    EXPECT_FALSE(future.ok);
    EXPECT_EQ(future.error, "newer schema version");
    EXPECT_FALSE(e.importDocument(std::string(Engine::kMaxImportBytes + 1, ' ')).ok);
    EXPECT_EQ(e.exportDocument(), before);
}

TEST(Engine, ImportDropsDeviceBoundAppWidgets) {
    Engine a;
    a.resetToDefaults(defaults());
    a.desktop().addWidget(a.desktop().active(), "appwidget", 12, 0, 0, 0.5f, 0.3f);
    Engine b;
    LoadReport r = b.importDocument(a.exportDocument());
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.skipped, 1u);
    for (const auto& w : b.desktop().widgets()) EXPECT_NE(w.kind, "appwidget");
}

TEST(Engine, PersistsAndRecoversFromCorruption) {
    std::string path = tempPath();
    Store store(path);
    Engine a;
    LoadReport first = a.loadFrom(store, defaults());
    EXPECT_TRUE(first.fresh);
    a.setSetting("theme.accent", "magenta");
    ASSERT_TRUE(a.saveTo(store));
    a.setSetting("theme.accent", "purple");
    ASSERT_TRUE(a.saveTo(store));

    Engine b;
    LoadReport r = b.loadFrom(store, defaults());
    EXPECT_TRUE(r.ok);
    EXPECT_FALSE(r.fresh);
    EXPECT_EQ(b.settings().get("theme.accent"), "purple");

    // Damage the main file: the backup (magenta) comes back.
    FILE* f = std::fopen(path.c_str(), "wb");
    std::fputs("LXL1 deadbeef 2\n{}", f);
    std::fclose(f);
    Engine c;
    LoadReport rc = c.loadFrom(store, defaults());
    EXPECT_TRUE(rc.usedBackup);
    EXPECT_TRUE(rc.mainCorrupt);
    EXPECT_EQ(c.settings().get("theme.accent"), "magenta");
    EXPECT_FALSE(c.diagnostics().empty());
}

TEST(Engine, UsableBackupSurvivesAMainFileThatDoesNotLoad) {
    // A main file can pass its checksum and still not load: it comes from a newer build, or it is
    // not a layout. The good backup used to be skipped, and the next two saves overwrote it.
    std::string path = tempPath();
    Store store(path);
    Engine a;
    a.resetToDefaults(defaults());
    a.setSetting("theme.accent", "purple");
    ASSERT_TRUE(a.saveTo(store));
    a.setSetting("theme.accent", "magenta");
    ASSERT_TRUE(a.saveTo(store));
    const std::string newer = "{\"format\":\"lx-launcher-layout\",\"version\":99}";
    ASSERT_TRUE(writeFileAtomic(path, wrapEnvelope(newer)));

    Engine b;
    LoadReport r = b.loadFrom(store, defaults());
    ASSERT_TRUE(r.ok) << r.error;
    EXPECT_TRUE(r.usedBackup);
    EXPECT_EQ(b.settings().get("theme.accent"), "purple");
    // The file the newer build wrote is set aside, not overwritten.
    auto aside = readFile(path + ".unusable", Store::kMaxFileBytes);
    ASSERT_TRUE(aside.has_value());
    EXPECT_EQ(unwrapEnvelope(*aside).value_or(""), newer);

    // Two saves later the backup still holds a layout that loads.
    ASSERT_TRUE(b.saveTo(store));
    ASSERT_TRUE(b.saveTo(store));
    Engine c;
    LoadReport rc = c.loadFrom(store, defaults());
    ASSERT_TRUE(rc.ok);
    EXPECT_FALSE(rc.usedBackup);
    EXPECT_EQ(c.settings().get("theme.accent"), "purple");
}

TEST(Engine, BackupThatDoesNotLoadIsSetAsideToo) {
    std::string path = tempPath();
    Store store(path);
    const std::string newer = "{\"format\":\"lx-launcher-layout\",\"version\":99}";
    ASSERT_TRUE(writeFileAtomic(path, wrapEnvelope("not json")));
    ASSERT_TRUE(writeFileAtomic(path + ".bak", wrapEnvelope(newer)));
    Engine e;
    LoadReport r = e.loadFrom(store, defaults());
    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.fresh);
    EXPECT_EQ(e.desktop().workspaces().size(), 4u);
    ASSERT_TRUE(e.saveTo(store));
    ASSERT_TRUE(e.saveTo(store));
    auto aside = readFile(path + ".bak.unusable", Store::kMaxFileBytes);
    ASSERT_TRUE(aside.has_value());
    EXPECT_EQ(unwrapEnvelope(*aside).value_or(""), newer);
}

TEST(Engine, LayoutsUpToTheStoreLimitLoad) {
    // The store accepts files up to 4 MB; the parser used to stop at 2 MB and reset the layout.
    std::string path = tempPath();
    Store store(path);
    Engine a;
    a.resetToDefaults(defaults());
    a.setSetting("theme.accent", "purple");
    std::string doc = a.exportDocument();
    ASSERT_EQ(doc.back(), '}');
    doc.pop_back();
    doc += ",\"padding\":\"";
    while (doc.size() < 3 * 1024 * 1024) doc += std::string(60000, 'x') + "\",\"p" + std::to_string(doc.size()) + "\":\"";
    doc += "\"}";
    ASSERT_GT(doc.size(), 2u * 1024 * 1024);
    ASSERT_TRUE(store.save(doc));
    Engine b;
    LoadReport r = b.loadFrom(store, defaults());
    ASSERT_TRUE(r.ok) << r.error;
    EXPECT_TRUE(r.error.empty()) << r.error;
    EXPECT_EQ(b.settings().get("theme.accent"), "purple");
}

TEST(Engine, RemovingWorkspaceKeepsWindowsConsistent) {
    Engine e;
    e.resetToDefaults(defaults());
    const auto& ws = e.desktop().workspaces();
    uint32_t second = ws[1].id, third = ws[2].id;
    uint32_t w1 = e.windows().open("files", "Files", second);
    ASSERT_TRUE(e.removeWorkspace(second, third));
    EXPECT_EQ(e.windows().info(w1)->workspaceId, third);
    uint32_t w2 = e.windows().open("notes", "Notes", third);
    ASSERT_TRUE(e.removeWorkspace(third, 0));
    EXPECT_FALSE(e.windows().info(w1).has_value());
    EXPECT_FALSE(e.windows().info(w2).has_value());
}

TEST(Engine, WindowCallsRejectWorkspacesThatDoNotExist) {
    Engine e;
    e.resetToDefaults(defaults());
    uint32_t first = e.desktop().workspaces()[0].id;
    uint32_t second = e.desktop().workspaces()[1].id;
    EXPECT_EQ(e.openWindow("files", "Files", 9999, WindowState::Floating), 0u);
    uint32_t w = e.openWindow("files", "Files", first, WindowState::Floating);
    ASSERT_NE(w, 0u);
    EXPECT_FALSE(e.moveWindowToWorkspace(w, 9999));
    EXPECT_EQ(e.windows().info(w)->workspaceId, first);
    EXPECT_TRUE(e.moveWindowToWorkspace(w, second));
    EXPECT_EQ(e.windows().info(w)->workspaceId, second);
}

TEST(Engine, GridSettingResizesDesktopGrid) {
    Engine e;
    EXPECT_TRUE(e.setSetting("desktop.gridCols", "8"));
    EXPECT_EQ(e.desktop().cols(), 8);
    EXPECT_FALSE(e.setSetting("desktop.gridCols", "99"));
    EXPECT_EQ(e.desktop().cols(), 8);
}

TEST(Engine, SnapshotIsValidJsonWithWindows) {
    Engine e;
    e.resetToDefaults(defaults(true));
    e.windows().setViewport({0, 0, 1600, 900}, {0, 0, 1600, 1000});
    std::string snap = e.snapshot();
    auto v = json::parse(snap);
    ASSERT_TRUE(v);
    ASSERT_TRUE(v->find("windows"));
    ASSERT_EQ(v->find("windows")->asArray().size(), 1u);
    EXPECT_TRUE(v->find("windows")->asArray()[0].find("focused")->asBool());
    EXPECT_TRUE(v->find("settings")->find("perf.mode"));
}

TEST(Engine, HugeNumbersInAFileAreClampedNotCast) {
    // static_cast<int> of a double outside the int range is undefined behaviour; UBSan flags it in the sanitizer run.
    Engine a;
    a.resetToDefaults(defaults());
    a.desktop().addShortcut(a.desktop().active(), "com.example.mail/com.example.mail.Main#0");
    std::string doc = a.exportDocument();
    auto replace = [&](const std::string& from, const std::string& to) {
        size_t at = doc.find(from);
        if (at != std::string::npos) doc.replace(at, from.size(), to);
    };
    replace("\"col\":", "\"col\":1e300,\"x_col\":");
    replace("\"cols\":", "\"cols\":-1e300,\"x_cols\":");
    Engine b;
    LoadReport r = b.importDocument(doc);
    EXPECT_TRUE(r.ok) << r.error;
    EXPECT_GE(b.desktop().cols(), 3);
    EXPECT_FALSE(b.importDocument("{\"format\":\"lx-launcher-layout\",\"version\":1e300}").ok);
    EXPECT_EQ(lx::validate::clampedInt(1e300, -1, 1000, 7), 1000);
    EXPECT_EQ(lx::validate::clampedInt(-1e300, -1, 1000, 7), -1);
    EXPECT_EQ(lx::validate::clampedInt(std::nan(""), -1, 1000, 7), 7);
}

// An imported file with ids near 2^31 is refused whole. Taken, the next id became 2^31, which the following load
// refused, so whatever was added after the import was lost.
TEST(Engine, ImportRefusesIdsThatLeaveNoRoom) {
    Engine a;
    a.resetToDefaults(defaults());
    std::string doc = a.exportDocument();
    auto at = doc.find("\"id\":1");
    ASSERT_NE(at, std::string::npos);
    std::string huge = doc.substr(0, at) + "\"id\":2147483647" + doc.substr(at + 6);
    Engine b;
    b.resetToDefaults(defaults());
    std::string before = b.exportDocument();
    LoadReport r = b.importDocument(huge);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.error, "ids out of range");
    EXPECT_EQ(b.exportDocument(), before);
    EXPECT_TRUE(b.importDocument(doc).ok);
}
