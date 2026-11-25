// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/window_manager.hpp"

#include <algorithm>
#include <cmath>

#include "lx/validate.hpp"

namespace lx {

const char* toString(WindowState s) {
    switch (s) {
        case WindowState::Floating: return "floating";
        case WindowState::Maximized: return "maximized";
        case WindowState::Minimized: return "minimized";
        case WindowState::SnappedLeft: return "snapped_left";
        case WindowState::SnappedRight: return "snapped_right";
        case WindowState::Fullscreen: return "fullscreen";
    }
    return "floating";
}

std::optional<WindowState> windowStateFromString(const std::string& s) {
    if (s == "floating") return WindowState::Floating;
    if (s == "maximized") return WindowState::Maximized;
    if (s == "minimized") return WindowState::Minimized;
    if (s == "snapped_left") return WindowState::SnappedLeft;
    if (s == "snapped_right") return WindowState::SnappedRight;
    if (s == "fullscreen") return WindowState::Fullscreen;
    return std::nullopt;
}

void WindowManager::setViewport(const Rect& usable, const Rect& full) {
    if (!finite(usable) || usable.empty() || !finite(full) || full.empty()) return;
    viewport_ = usable;
    full_ = full;
    viewportKnown_ = true;
}

void WindowManager::setConstraints(const WindowConstraints& c) {
    WindowConstraints v = c;
    v.minWidth = clampf(v.minWidth, 48, 4096);
    v.minHeight = clampf(v.minHeight, 48, 4096);
    v.titleBarHeight = clampf(v.titleBarHeight, 16, 256);
    c_ = v;
}

WindowManager::Win* WindowManager::find(uint32_t id) {
    for (auto& w : windows_)
        if (w.id == id) return &w;
    return nullptr;
}

const WindowManager::Win* WindowManager::find(uint32_t id) const {
    for (const auto& w : windows_)
        if (w.id == id) return &w;
    return nullptr;
}

Rect WindowManager::defaultNorm(size_t index) const {
    float step = 0.035f * static_cast<float>(index % 6);
    bool landscape = viewportKnown_ ? viewport_.w >= viewport_.h : landscapeHint_;
    if (landscape) return {0.08f + step, 0.06f + step, 0.55f, 0.7f};
    return {0.03f + step * 0.5f, 0.05f + step, 0.94f - step * 0.5f, 0.55f};
}

// Keeps a floating window usable: never smaller than the minimum, never larger
// than the viewport, and the whole title bar on screen. The window buttons sit
// at one end of the title bar (the right, or the left in right-to-left
// languages), so letting the window slide past either side can hide them.
Rect WindowManager::clampFloating(Rect r) const {
    const Rect& vp = viewport_;
    float minW = std::min(c_.minWidth, vp.w);
    float minH = std::min(c_.minHeight, vp.h);
    r.w = clampf(r.w, minW, vp.w);
    r.h = clampf(r.h, minH, vp.h);
    r.x = clampf(r.x, vp.x, vp.right() - r.w);
    r.y = clampf(r.y, vp.y, vp.bottom() - std::min(c_.titleBarHeight, r.h));
    return r;
}

Rect WindowManager::floatingPx(const Win& w) const {
    const Rect& vp = viewport_;
    Rect px{vp.x + w.norm.x * vp.w, vp.y + w.norm.y * vp.h, w.norm.w * vp.w, w.norm.h * vp.h};
    return clampFloating(px);
}

void WindowManager::storeFloatingPx(Win& w, const Rect& px) {
    Rect r = clampFloating(px);
    const Rect& vp = viewport_;
    w.norm = {(r.x - vp.x) / vp.w, (r.y - vp.y) / vp.h, r.w / vp.w, r.h / vp.h};
}

Rect WindowManager::effective(const Win& w) const {
    const Rect& vp = viewport_;
    bool landscape = vp.w >= vp.h;
    switch (w.state) {
        case WindowState::Maximized: return vp;
        case WindowState::Fullscreen: return full_;
        case WindowState::SnappedLeft:
            return landscape ? Rect{vp.x, vp.y, vp.w / 2, vp.h} : Rect{vp.x, vp.y, vp.w, vp.h / 2};
        case WindowState::SnappedRight:
            return landscape ? Rect{vp.x + vp.w / 2, vp.y, vp.w / 2, vp.h}
                             : Rect{vp.x, vp.y + vp.h / 2, vp.w, vp.h / 2};
        case WindowState::Floating:
        case WindowState::Minimized:
            return floatingPx(w);
    }
    return floatingPx(w);
}

uint32_t WindowManager::open(const std::string& kind, const std::string& title,
                             uint32_t workspaceId, WindowState initial) {
    if (windows_.size() >= kMaxWindows || workspaceId == 0 || !validate::isToken(kind, 32))
        return 0;
    if (initial == WindowState::Minimized) initial = WindowState::Floating;
    Win w{};
    w.id = nextId_++;
    w.kind = kind;
    w.title = validate::truncateUtf8(title, 128);
    w.workspaceId = workspaceId;
    w.state = initial;
    w.beforeMinimize = initial;
    w.beforeFullscreen = initial == WindowState::Fullscreen ? WindowState::Floating : initial;
    w.z = nextZ_++;
    size_t inWorkspace = 0;
    size_t sameKind = 0;
    for (const auto& o : windows_) {
        if (o.workspaceId != workspaceId) continue;
        ++inWorkspace;
        if (o.kind == kind) ++sameKind;
    }
    if (auto place = rememberedPlace(kind)) {
        // A second window of the same kind steps down and right so it does not hide the first.
        float step = 0.035f * static_cast<float>(sameKind % 6);
        w.norm = {place->x + step, place->y + step, place->w, place->h};
    } else {
        w.norm = defaultNorm(inWorkspace);
    }
    windows_.push_back(std::move(w));
    return windows_.back().id;
}

bool WindowManager::close(uint32_t id) {
    auto it = std::find_if(windows_.begin(), windows_.end(), [&](const Win& w) { return w.id == id; });
    if (it == windows_.end()) return false;
    rememberPlace(*it);
    windows_.erase(it);
    return true;
}

void WindowManager::rememberPlace(const Win& w) {
    // norm is the floating geometry even while the window is maximised or snapped.
    auto it = std::find_if(places_.begin(), places_.end(), [&](const auto& p) { return p.first == w.kind; });
    if (it != places_.end()) places_.erase(it);
    places_.insert(places_.begin(), {w.kind, w.norm});
    if (places_.size() > kMaxPlaces) places_.resize(kMaxPlaces);
}

std::optional<Rect> WindowManager::rememberedPlace(const std::string& kind) const {
    for (const auto& [k, r] : places_)
        if (k == kind) return r;
    return std::nullopt;
}

json::Value WindowManager::placesToJson() const {
    json::Value arr{json::Array{}};
    for (const auto& [kind, r] : places_) {
        json::Value o{json::Object{}};
        o.set("kind", kind);
        o.set("x", static_cast<double>(r.x));
        o.set("y", static_cast<double>(r.y));
        o.set("w", static_cast<double>(r.w));
        o.set("h", static_cast<double>(r.h));
        arr.push(std::move(o));
    }
    return arr;
}

size_t WindowManager::placesFromJson(const json::Value& v) {
    places_.clear();
    size_t skipped = 0;
    for (const auto& item : v.asArray()) {
        const json::Value* kind = item.find("kind");
        Rect n{static_cast<float>(validate::numberOr(item.find("x"), -9)),
               static_cast<float>(validate::numberOr(item.find("y"), -9)),
               static_cast<float>(validate::numberOr(item.find("w"), 0)),
               static_cast<float>(validate::numberOr(item.find("h"), 0))};
        if (places_.size() >= kMaxPlaces || !kind || !validate::isToken(kind->asString(), 32) || !finite(n) || n.w <= 0 ||
            n.h <= 0 || n.w > 1.5f || n.h > 1.5f || n.x < -1.5f || n.x > 1.5f || n.y < -1.5f || n.y > 1.5f ||
            rememberedPlace(kind->asString())) {
            ++skipped;
            continue;
        }
        places_.emplace_back(kind->asString(), n);
    }
    return skipped;
}

bool WindowManager::focus(uint32_t id) {
    Win* w = find(id);
    if (!w) return false;
    if (w->state == WindowState::Minimized) w->state = w->beforeMinimize;
    w->z = nextZ_++;
    return true;
}

bool WindowManager::move(uint32_t id, float dx, float dy) {
    Win* w = find(id);
    if (!w || !finite(dx) || !finite(dy) || w->state == WindowState::Minimized) return false;
    if (w->state != WindowState::Floating) {
        // Dragging a maximised or snapped window detaches it, like desktop Linux.
        w->state = WindowState::Floating;
    }
    Rect r = floatingPx(*w);
    r.x += dx;
    r.y += dy;
    storeFloatingPx(*w, r);
    return true;
}

bool WindowManager::resize(uint32_t id, uint32_t edges, float dx, float dy) {
    Win* w = find(id);
    if (!w || !finite(dx) || !finite(dy) || w->state == WindowState::Minimized) return false;
    if ((edges & (kEdgeLeft | kEdgeTop | kEdgeRight | kEdgeBottom)) == 0) return false;
    Rect r = effective(*w);
    if (w->state != WindowState::Floating) w->state = WindowState::Floating;
    const Rect& vp = viewport_;
    float minW = std::min(c_.minWidth, vp.w);
    float minH = std::min(c_.minHeight, vp.h);
    // The side being dragged moves and the side across stays. A dragged side stops at the size limits and at the
    // edge of the viewport; before, it went past that edge and clampFloating pushed the window back, which moved
    // the side across instead, so a finger past the edge grew the window on the far side. The bottom side may
    // stay below the viewport, as only the title bar has to be on screen.
    if (edges & kEdgeLeft) {
        float right = r.right();
        float left = clampf(r.x + dx, std::max(vp.x, right - vp.w), right - minW);
        r.w = right - left;
        r.x = left;
    }
    if (edges & kEdgeRight) {
        float right = clampf(r.right() + dx, r.x + minW, std::min(vp.right(), r.x + vp.w));
        r.w = right - r.x;
    }
    if (edges & kEdgeTop) {
        float bottom = r.bottom();
        float top = clampf(r.y + dy, std::max(vp.y, bottom - vp.h), bottom - minH);
        r.h = bottom - top;
        r.y = top;
    }
    if (edges & kEdgeBottom) r.h = clampf(r.h + dy, minH, vp.h);
    storeFloatingPx(*w, r);
    return true;
}

bool WindowManager::setState(uint32_t id, WindowState state) {
    Win* w = find(id);
    if (!w) return false;
    if (state == WindowState::Minimized) {
        if (w->state != WindowState::Minimized) w->beforeMinimize = w->state;
        w->state = WindowState::Minimized;
        return true;
    }
    if (state == WindowState::Fullscreen && w->state != WindowState::Fullscreen) {
        WindowState was = w->state == WindowState::Minimized ? w->beforeMinimize : w->state;
        w->beforeFullscreen = was == WindowState::Fullscreen ? WindowState::Floating : was;
    }
    w->state = state;
    w->z = nextZ_++;
    return true;
}

bool WindowManager::toggleMaximize(uint32_t id) {
    Win* w = find(id);
    if (!w) return false;
    // Restore leaves both maximised and fullscreen; the title bar shows Restore for either.
    bool large = w->state == WindowState::Maximized || w->state == WindowState::Fullscreen;
    return setState(id, large ? WindowState::Floating : WindowState::Maximized);
}

bool WindowManager::leaveFullscreen(uint32_t id) {
    Win* w = find(id);
    if (!w || w->state != WindowState::Fullscreen) return false;
    return setState(id, w->beforeFullscreen);
}

bool WindowManager::restore(uint32_t id) {
    Win* w = find(id);
    if (!w) return false;
    if (w->state == WindowState::Minimized) return focus(id);
    return setState(id, WindowState::Floating);
}

bool WindowManager::setTitle(uint32_t id, const std::string& title) {
    Win* w = find(id);
    if (!w) return false;
    w->title = validate::truncateUtf8(title, 128);
    return true;
}

bool WindowManager::moveToWorkspace(uint32_t id, uint32_t workspaceId) {
    Win* w = find(id);
    if (!w || workspaceId == 0) return false;
    w->workspaceId = workspaceId;
    w->z = nextZ_++;
    return true;
}

void WindowManager::tile(uint32_t workspaceId) {
    std::vector<Win*> visible;
    for (auto& w : windows_)
        if (w.workspaceId == workspaceId && w.state != WindowState::Minimized) visible.push_back(&w);
    if (visible.empty()) return;
    std::sort(visible.begin(), visible.end(), [](Win* a, Win* b) { return a->z < b->z; });
    const size_t n = visible.size();
    bool landscape = viewport_.w >= viewport_.h;
    size_t major = static_cast<size_t>(std::ceil(std::sqrt(static_cast<double>(n))));
    size_t minor = (n + major - 1) / major;
    size_t cols = landscape ? major : minor;
    size_t rows = landscape ? minor : major;
    const float gap = 0.01f;
    float cw = 1.0f / static_cast<float>(cols);
    float ch = 1.0f / static_cast<float>(rows);
    for (size_t i = 0; i < n; ++i) {
        size_t col = i % cols;
        size_t row = i / cols;
        Win* w = visible[i];
        w->state = WindowState::Floating;
        w->norm = {static_cast<float>(col) * cw + gap / 2, static_cast<float>(row) * ch + gap / 2,
                   cw - gap, ch - gap};
    }
}

void WindowManager::minimizeAll(uint32_t workspaceId) {
    for (auto& w : windows_) {
        if (w.workspaceId == workspaceId && w.state != WindowState::Minimized) {
            w.beforeMinimize = w.state;
            w.state = WindowState::Minimized;
        }
    }
}

void WindowManager::reassignWorkspace(uint32_t removed, uint32_t fallback) {
    for (auto& w : windows_)
        if (w.workspaceId == removed) w.workspaceId = fallback;
}

void WindowManager::clear() {
    windows_.clear();
    places_.clear();
}

std::optional<WindowInfo> WindowManager::info(uint32_t id) const {
    const Win* w = find(id);
    if (!w) return std::nullopt;
    return WindowInfo{w->id, w->kind, w->title, w->workspaceId, w->state, w->beforeMinimize,
                      w->z, effective(*w)};
}

std::vector<WindowInfo> WindowManager::windowsIn(uint32_t workspaceId) const {
    std::vector<WindowInfo> out;
    for (const auto& w : windows_)
        if (w.workspaceId == workspaceId) out.push_back(*info(w.id));
    std::sort(out.begin(), out.end(), [](const WindowInfo& a, const WindowInfo& b) { return a.z < b.z; });
    return out;
}

std::vector<WindowInfo> WindowManager::all() const {
    std::vector<WindowInfo> out;
    for (const auto& w : windows_) out.push_back(*info(w.id));
    std::sort(out.begin(), out.end(), [](const WindowInfo& a, const WindowInfo& b) { return a.z < b.z; });
    return out;
}

std::optional<uint32_t> WindowManager::focused(uint32_t workspaceId) const {
    const Win* best = nullptr;
    for (const auto& w : windows_) {
        if (w.workspaceId != workspaceId || w.state == WindowState::Minimized) continue;
        if (!best || w.z > best->z) best = &w;
    }
    if (!best) return std::nullopt;
    return best->id;
}

std::optional<uint32_t> WindowManager::hitTest(uint32_t workspaceId, float x, float y) const {
    const Win* best = nullptr;
    for (const auto& w : windows_) {
        if (w.workspaceId != workspaceId || w.state == WindowState::Minimized) continue;
        if (!effective(w).contains(x, y)) continue;
        if (!best || w.z > best->z) best = &w;
    }
    if (!best) return std::nullopt;
    return best->id;
}

std::optional<uint32_t> WindowManager::findByKind(uint32_t workspaceId, const std::string& kind) const {
    for (const auto& w : windows_)
        if (w.workspaceId == workspaceId && w.kind == kind) return w.id;
    return std::nullopt;
}

std::optional<WindowState> WindowManager::snapTarget(float x, float y, float threshold) const {
    if (!finite(x) || !finite(y) || !(threshold > 0)) return std::nullopt;
    const Rect& vp = viewport_;
    bool landscape = vp.w >= vp.h;
    if (y <= vp.y + threshold) return WindowState::Maximized;
    if (landscape) {
        if (x <= vp.x + threshold) return WindowState::SnappedLeft;
        if (x >= vp.right() - threshold) return WindowState::SnappedRight;
    } else if (y >= vp.bottom() - threshold) {
        return WindowState::SnappedRight;  // bottom half in portrait
    }
    return std::nullopt;
}

json::Value WindowManager::toJson() const {
    json::Value arr{json::Array{}};
    for (const auto& w : windows_) {
        json::Value o{json::Object{}};
        o.set("id", w.id);
        o.set("kind", w.kind);
        o.set("title", w.title);
        o.set("ws", w.workspaceId);
        o.set("state", toString(w.state));
        o.set("before", toString(w.beforeMinimize));
        o.set("beforeFull", toString(w.beforeFullscreen));
        o.set("z", static_cast<double>(w.z));
        o.set("x", static_cast<double>(w.norm.x));
        o.set("y", static_cast<double>(w.norm.y));
        o.set("w", static_cast<double>(w.norm.w));
        o.set("h", static_cast<double>(w.norm.h));
        arr.push(std::move(o));
    }
    return arr;
}

size_t WindowManager::fromJson(const json::Value& v) {
    windows_.clear();
    nextId_ = 1;
    nextZ_ = 1;
    size_t skipped = 0;
    for (const auto& item : v.asArray()) {
        if (windows_.size() >= kMaxWindows) {
            ++skipped;
            continue;
        }
        auto id = validate::idFrom(item.find("id"));
        auto ws = validate::idFrom(item.find("ws"));
        const json::Value* kind = item.find("kind");
        const json::Value* stateV = item.find("state");
        if (!id || !ws || !kind || !validate::isToken(kind->asString(), 32) || find(*id)) {
            ++skipped;
            continue;
        }
        auto state = windowStateFromString(stateV ? stateV->asString() : "");
        auto before = windowStateFromString(item.find("before") ? item.find("before")->asString() : "");
        auto beforeFull = windowStateFromString(item.find("beforeFull") ? item.find("beforeFull")->asString() : "");
        Rect n{static_cast<float>(validate::numberOr(item.find("x"), 0.1)),
               static_cast<float>(validate::numberOr(item.find("y"), 0.1)),
               static_cast<float>(validate::numberOr(item.find("w"), 0.5)),
               static_cast<float>(validate::numberOr(item.find("h"), 0.5))};
        if (!state || !finite(n) || n.w <= 0 || n.h <= 0 || n.w > 1.5f || n.h > 1.5f ||
            n.x < -1.5f || n.x > 1.5f || n.y < -1.5f || n.y > 1.5f) {
            ++skipped;
            continue;
        }
        Win w{};
        w.id = *id;
        w.kind = kind->asString();
        const json::Value* title = item.find("title");
        w.title = validate::truncateUtf8(title ? title->asString() : std::string(), 128);
        w.workspaceId = *ws;
        w.state = *state;
        w.beforeMinimize = before && *before != WindowState::Minimized ? *before : WindowState::Floating;
        w.beforeFullscreen = beforeFull && *beforeFull != WindowState::Minimized && *beforeFull != WindowState::Fullscreen
                                 ? *beforeFull
                                 : WindowState::Floating;
        double z = validate::numberOr(item.find("z"), 0);
        w.z = z > 0 && z < 1e15 ? static_cast<uint64_t>(z) : 0;
        w.norm = n;
        nextId_ = std::max(nextId_, w.id + 1);
        nextZ_ = std::max(nextZ_, w.z + 1);
        windows_.push_back(std::move(w));
    }
    return skipped;
}

}  // namespace lx
