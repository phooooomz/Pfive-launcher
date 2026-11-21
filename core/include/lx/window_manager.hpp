// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Tier 1 window management: windows that LX-LAUNCHER draws itself (Settings,
// Files, Monitor, Notes). Third-party Android activities are never modelled
// here; Android owns their windows.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "lx/geometry.hpp"
#include "lx/json.hpp"

namespace lx {

enum class WindowState : uint8_t {
    Floating,
    Maximized,
    Minimized,
    SnappedLeft,
    SnappedRight,
    Fullscreen,
};

const char* toString(WindowState s);
std::optional<WindowState> windowStateFromString(const std::string& s);

enum ResizeEdge : uint32_t {
    kEdgeLeft = 1,
    kEdgeTop = 2,
    kEdgeRight = 4,
    kEdgeBottom = 8,
};

struct WindowConstraints {
    float minWidth = 240;
    float minHeight = 160;
    float titleBarHeight = 44;
};

struct WindowInfo {
    uint32_t id = 0;
    std::string kind;   // which internal tool, e.g. "settings"
    std::string title;
    uint32_t workspaceId = 0;
    WindowState state = WindowState::Floating;
    WindowState stateBeforeMinimize = WindowState::Floating;
    uint64_t z = 0;
    Rect bounds;  // effective bounds in px for the current viewport
};

class WindowManager {
public:
    static constexpr size_t kMaxWindows = 48;

    // usable: the area windows may occupy (screen minus system insets, panels and
    // taskbar). full: the whole drawable area, used by internal fullscreen.
    void setViewport(const Rect& usable, const Rect& full);
    void setConstraints(const WindowConstraints& c);
    // Shape to assume for new windows before the host has reported a viewport.
    void setOrientationHint(bool landscape) { landscapeHint_ = landscape; }
    const Rect& viewport() const { return viewport_; }
    const Rect& fullArea() const { return full_; }
    const WindowConstraints& constraints() const { return c_; }

    // Returns 0 when the limit is reached or the arguments are invalid.
    uint32_t open(const std::string& kind, const std::string& title, uint32_t workspaceId,
                  WindowState initial = WindowState::Floating);
    bool close(uint32_t id);
    bool focus(uint32_t id);
    bool move(uint32_t id, float dx, float dy);
    bool resize(uint32_t id, uint32_t edges, float dx, float dy);
    bool setState(uint32_t id, WindowState state);
    bool toggleMaximize(uint32_t id);
    bool restore(uint32_t id);
    // Back from full screen to the state the window had before it; false when it is not full screen.
    bool leaveFullscreen(uint32_t id);
    bool setTitle(uint32_t id, const std::string& title);
    bool moveToWorkspace(uint32_t id, uint32_t workspaceId);
    // Arranges every visible window of the workspace in a grid.
    void tile(uint32_t workspaceId);
    // Minimises every window of the workspace ("show desktop").
    void minimizeAll(uint32_t workspaceId);
    // Moves windows of a removed workspace to `fallback`.
    void reassignWorkspace(uint32_t removed, uint32_t fallback);
    void clear();

    std::optional<WindowInfo> info(uint32_t id) const;
    // Sorted bottom to top.
    std::vector<WindowInfo> windowsIn(uint32_t workspaceId) const;
    std::vector<WindowInfo> all() const;
    std::optional<uint32_t> focused(uint32_t workspaceId) const;
    // Topmost visible window under the point, used for hit testing on the host.
    std::optional<uint32_t> hitTest(uint32_t workspaceId, float x, float y) const;
    std::optional<uint32_t> findByKind(uint32_t workspaceId, const std::string& kind) const;
    // State a drag released at (x, y) should snap to: top edge maximises, side
    // edges snap. std::nullopt means stay floating.
    std::optional<WindowState> snapTarget(float x, float y, float threshold) const;

    json::Value toJson() const;
    // Invalid entries are skipped; returns how many were skipped.
    size_t fromJson(const json::Value& v);

    // Where each kind of window last floated before it was closed, so the next one opens there.
    // Kept separately from toJson() because it outlives the windows themselves.
    static constexpr size_t kMaxPlaces = 32;
    std::optional<Rect> rememberedPlace(const std::string& kind) const;
    json::Value placesToJson() const;
    // Invalid entries are skipped; returns how many were skipped.
    size_t placesFromJson(const json::Value& v);

private:
    struct Win {
        uint32_t id;
        std::string kind;
        std::string title;
        uint32_t workspaceId;
        WindowState state;
        WindowState beforeMinimize;
        WindowState beforeFullscreen;
        uint64_t z;
        Rect norm;  // floating bounds as fractions of the usable viewport
    };

    Win* find(uint32_t id);
    const Win* find(uint32_t id) const;
    Rect effective(const Win& w) const;
    Rect floatingPx(const Win& w) const;
    void storeFloatingPx(Win& w, const Rect& px);
    Rect clampFloating(Rect r) const;
    Rect defaultNorm(size_t index) const;

    void rememberPlace(const Win& w);

    std::vector<Win> windows_;
    // Most recently closed kind first, at most kMaxPlaces.
    std::vector<std::pair<std::string, Rect>> places_;
    Rect viewport_{0, 0, 1080, 1920};
    Rect full_{0, 0, 1080, 1920};
    WindowConstraints c_;
    uint32_t nextId_ = 1;
    uint64_t nextZ_ = 1;
    bool viewportKnown_ = false;
    bool landscapeHint_ = false;
};

}  // namespace lx
