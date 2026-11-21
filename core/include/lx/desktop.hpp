// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Workspaces and everything the user places on them: app shortcuts, folders,
// widgets, plus the dock and taskbar pin lists. All entities carry stable ids,
// and entities refer to workspaces by id, never by pointer.
#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "lx/json.hpp"

namespace lx {

struct Workspace {
    uint32_t id = 0;
    std::string name;
    std::string wallpaper = "builtin:theme";  // drawn in the active colour theme
};

struct Cell {
    int col = 0;
    int row = 0;
    bool operator==(const Cell& o) const { return col == o.col && row == o.row; }
};

struct Shortcut {
    uint32_t id = 0;
    uint32_t workspaceId = 0;
    std::string appKey;
    Cell cell;
};

struct Folder {
    uint32_t id = 0;
    uint32_t workspaceId = 0;
    std::string name;
    Cell cell;
    std::vector<std::string> apps;
};

// Widget geometry as fractions of the desktop canvas. Portrait and landscape
// keep separate rectangles, so arranging one orientation never stretches the
// other. `aspect` is the canvas width / height the rectangle was set for.
struct WidgetRect {
    float x = 0, y = 0, w = 0.4f, h = 0.2f;
    float aspect = 0;  // 0 when unknown (older or imported layouts)
    bool set = false;
};

struct Widget {
    uint32_t id = 0;
    uint32_t workspaceId = 0;
    std::string kind;        // "clock", "status", "notes", ... or "appwidget"
    int32_t appWidgetId = -1;  // only for kind == "appwidget"
    WidgetRect portrait;
    WidgetRect landscape;
    bool visible = true;
    std::map<std::string, std::string> config;
};

enum class PinList { Dock, Taskbar, Favorites, Hidden, Recent };

class Desktop {
public:
    static constexpr size_t kMinWorkspaces = 1;
    static constexpr size_t kMaxWorkspaces = 12;
    static constexpr size_t kMaxWidgetsPerWorkspace = 24;
    static constexpr size_t kMaxFolderApps = 64;
    static constexpr size_t kMaxWidgetConfig = 16;
    static constexpr size_t kMaxConfigValue = 8192;

    void clear();

    // Workspaces
    uint32_t addWorkspace(const std::string& name);
    bool renameWorkspace(uint32_t id, const std::string& name);
    // Moves the workspace's shortcuts, folders and widgets to `moveTo`, or
    // deletes them when moveTo is 0. Returns the app widget ids that were
    // deleted so the host can release them.
    std::optional<std::vector<int32_t>> removeWorkspace(uint32_t id, uint32_t moveTo);
    bool setActive(uint32_t id);
    uint32_t active() const { return active_; }
    // The desktop has one wallpaper: this sets it on every workspace. `id` must name an existing one.
    bool setWallpaper(uint32_t id, const std::string& ref);
    const std::vector<Workspace>& workspaces() const { return workspaces_; }
    const Workspace* workspace(uint32_t id) const;
    // Next or previous workspace, wrapping around.
    uint32_t neighbour(int direction) const;

    // Grid
    void setGrid(int cols, int rows);
    int cols() const { return cols_; }
    int rows() const { return rows_; }
    std::optional<Cell> freeCell(uint32_t workspaceId) const;

    // Shortcuts and folders share one id space with widgets.
    uint32_t addShortcut(uint32_t workspaceId, const std::string& appKey,
                         std::optional<Cell> cell = std::nullopt);
    // Moves a shortcut or folder; swaps with whatever occupies the target cell.
    bool moveItem(uint32_t itemId, Cell cell);
    bool moveItemToWorkspace(uint32_t itemId, uint32_t workspaceId);
    bool removeItem(uint32_t itemId);
    uint32_t createFolder(uint32_t shortcutA, uint32_t shortcutB, const std::string& name);
    bool addToFolder(uint32_t folderId, uint32_t shortcutId);
    // Takes the app out of the folder and places it on a free cell.
    uint32_t removeFromFolder(uint32_t folderId, const std::string& appKey);
    bool renameFolder(uint32_t folderId, const std::string& name);
    // Any profile, for removePackage.
    static constexpr int64_t kAnyUser = -1;
    // Removes every reference to apps of `package` (uninstall). Returns the
    // number of references removed. With a user serial, only keys of that
    // profile ("pkg/cls#serial") go: the same app in another profile stays.
    size_t removePackage(const std::string& package, int64_t userSerial = kAnyUser);

    const std::vector<Shortcut>& shortcuts() const { return shortcuts_; }
    const std::vector<Folder>& folders() const { return folders_; }

    // Widgets. Geometry calls apply to the orientation of the current canvas.
    // A negative x or y asks addWidget to find a free spot itself.
    void setCanvasAspect(float widthOverHeight);
    float canvasAspect() const { return aspect_; }
    bool landscape() const { return aspect_ >= 1.0f; }
    uint32_t addWidget(uint32_t workspaceId, const std::string& kind, int32_t appWidgetId,
                       float x, float y, float w, float h);
    bool setWidgetRect(uint32_t id, float x, float y, float w, float h);
    // Rectangle for the current orientation; derived from the other
    // orientation when this one was never arranged.
    WidgetRect widgetRect(const Widget& w) const;
    // Returns the app widget id (or -1) of the removed widget.
    std::optional<int32_t> removeWidget(uint32_t id);
    bool setWidgetConfig(uint32_t id, const std::string& key, const std::string& value);
    bool setWidgetVisible(uint32_t id, bool visible);
    bool moveWidgetToWorkspace(uint32_t id, uint32_t workspaceId);
    const std::vector<Widget>& widgets() const { return widgets_; }
    const Widget* widget(uint32_t id) const;

    // Pin lists. Entries are "app:<appKey>", "tool:<kind>", "action:<name>" or
    // "intent:<name>"; hidden, favorite and recent lists hold bare app keys.
    bool pin(PinList list, const std::string& entry, int index = -1);
    bool unpin(PinList list, const std::string& entry);
    bool movePin(PinList list, const std::string& entry, int delta);
    bool isPinned(PinList list, const std::string& entry) const;
    void setPins(PinList list, std::vector<std::string> entries);
    const std::vector<std::string>& pins(PinList list) const;
    static bool isPinEntry(PinList list, const std::string& entry);

    // With `resolved`, widgets also carry x/y/w/h for the current orientation
    // (used for the UI snapshot, not for storage).
    json::Value toJson(bool resolved = false) const;
    // Loads what is valid and skips the rest one entity at a time. Returns the
    // number of skipped entities. Always leaves at least one workspace.
    size_t fromJson(const json::Value& v);

private:
    bool occupied(uint32_t workspaceId, Cell c, uint32_t ignoreId = 0) const;
    bool underWidget(uint32_t workspaceId, Cell c) const;
    void uncoverItems(uint32_t workspaceId);
    std::optional<std::pair<float, float>> freeWidgetSpot(uint32_t workspaceId, float w, float h) const;
    uint32_t occupant(uint32_t workspaceId, Cell c) const;
    Shortcut* shortcut(uint32_t id);
    Folder* folder(uint32_t id);
    Widget* mutableWidget(uint32_t id);
    std::vector<std::string>& list(PinList l);
    size_t limit(PinList l) const;
    void ensureWorkspace();
    void relocateOutside();

    std::vector<Workspace> workspaces_;
    std::vector<Shortcut> shortcuts_;
    std::vector<Folder> folders_;
    std::vector<Widget> widgets_;
    std::vector<std::string> dock_, taskbar_, favorites_, hidden_, recent_;
    uint32_t active_ = 0;
    uint32_t nextWorkspaceId_ = 1;
    uint32_t nextItemId_ = 1;
    int cols_ = 5;
    int rows_ = 6;
    float aspect_ = 0.5625f;
};

}  // namespace lx
