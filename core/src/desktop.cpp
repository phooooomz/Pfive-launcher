// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/desktop.hpp"

#include <algorithm>
#include <cmath>
#include <set>
#include <tuple>
#include <utility>
#include <vector>

#include "lx/geometry.hpp"
#include "lx/validate.hpp"

namespace lx {

namespace {

std::string cleanName(const std::string& name, size_t maxBytes) {
    std::string s = validate::truncateUtf8(name, maxBytes);
    size_t b = s.find_first_not_of(" \t");
    if (b == std::string::npos) return {};
    size_t e = s.find_last_not_of(" \t");
    return s.substr(b, e - b + 1);
}

void normalizeRect(float& x, float& y, float& w, float& h) {
    w = clampf(w, 0.05f, 1.0f);
    h = clampf(h, 0.05f, 1.0f);
    x = clampf(x, 0.0f, 1.0f - w);
    y = clampf(y, 0.0f, 1.0f - h);
}

WidgetRect makeRect(float x, float y, float w, float h, float aspect) {
    normalizeRect(x, y, w, h);
    return {x, y, w, h, aspect, true};
}

json::Value rectJson(const WidgetRect& r) {
    json::Value o{json::Object{}};
    o.set("x", static_cast<double>(r.x));
    o.set("y", static_cast<double>(r.y));
    o.set("w", static_cast<double>(r.w));
    o.set("h", static_cast<double>(r.h));
    o.set("a", static_cast<double>(r.aspect));
    return o;
}

WidgetRect rectFrom(const json::Value& o) {
    float aspect = static_cast<float>(validate::numberOr(o.find("a"), 0));
    if (!(aspect > 0.05f && aspect < 20.0f)) aspect = 0;
    return makeRect(static_cast<float>(validate::numberOr(o.find("x"), 0)),
                    static_cast<float>(validate::numberOr(o.find("y"), 0)),
                    static_cast<float>(validate::numberOr(o.find("w"), 0.4)),
                    static_cast<float>(validate::numberOr(o.find("h"), 0.2)), aspect);
}

std::string packageOf(const std::string& appKey) {
    size_t slash = appKey.find('/');
    return slash == std::string::npos ? std::string() : appKey.substr(0, slash);
}

// The profile serial after '#', or nothing when the key has none or it is not a number.
std::optional<int64_t> userOf(const std::string& appKey) {
    size_t hash = appKey.rfind('#');
    if (hash == std::string::npos || hash + 1 >= appKey.size() || appKey.size() - hash > 20) return std::nullopt;
    int64_t serial = 0;
    for (size_t i = hash + 1; i < appKey.size(); ++i) {
        char c = appKey[i];
        if (c < '0' || c > '9') return std::nullopt;
        serial = serial * 10 + (c - '0');
    }
    return serial;
}

}  // namespace

void Desktop::clear() {
    workspaces_.clear();
    shortcuts_.clear();
    folders_.clear();
    widgets_.clear();
    dock_.clear();
    taskbar_.clear();
    favorites_.clear();
    hidden_.clear();
    recent_.clear();
    active_ = 0;
    nextWorkspaceId_ = 1;
    nextItemId_ = 1;
}

// ---- workspaces ----

uint32_t Desktop::addWorkspace(const std::string& name) {
    if (workspaces_.size() >= kMaxWorkspaces) return 0;
    std::string n = cleanName(name, 48);
    if (n.empty()) return 0;
    Workspace w;
    w.id = nextWorkspaceId_++;
    w.name = n;
    // Every workspace shows the same wallpaper, so a new one takes the current wallpaper.
    if (!workspaces_.empty()) w.wallpaper = workspaces_.front().wallpaper;
    workspaces_.push_back(w);
    if (active_ == 0) active_ = w.id;
    return w.id;
}

bool Desktop::renameWorkspace(uint32_t id, const std::string& name) {
    std::string n = cleanName(name, 48);
    if (n.empty()) return false;
    for (auto& w : workspaces_) {
        if (w.id == id) {
            w.name = n;
            return true;
        }
    }
    return false;
}

std::optional<std::vector<int32_t>> Desktop::removeWorkspace(uint32_t id, uint32_t moveTo) {
    if (workspaces_.size() <= kMinWorkspaces || !workspace(id)) return std::nullopt;
    if (moveTo != 0 && (moveTo == id || !workspace(moveTo))) return std::nullopt;
    std::vector<int32_t> released;
    if (moveTo != 0) {
        for (auto& s : shortcuts_) {
            if (s.workspaceId != id) continue;
            s.workspaceId = moveTo;
            if (occupied(moveTo, s.cell, s.id)) {
                if (auto c = freeCell(moveTo)) s.cell = *c;
            }
        }
        for (auto& f : folders_) {
            if (f.workspaceId != id) continue;
            f.workspaceId = moveTo;
            if (occupied(moveTo, f.cell, f.id)) {
                if (auto c = freeCell(moveTo)) f.cell = *c;
            }
        }
        for (auto& w : widgets_)
            if (w.workspaceId == id) w.workspaceId = moveTo;
    } else {
        for (const auto& w : widgets_)
            if (w.workspaceId == id && w.appWidgetId >= 0) released.push_back(w.appWidgetId);
        auto inWs = [id](const auto& e) { return e.workspaceId == id; };
        shortcuts_.erase(std::remove_if(shortcuts_.begin(), shortcuts_.end(), inWs), shortcuts_.end());
        folders_.erase(std::remove_if(folders_.begin(), folders_.end(), inWs), folders_.end());
        widgets_.erase(std::remove_if(widgets_.begin(), widgets_.end(), inWs), widgets_.end());
    }
    workspaces_.erase(std::remove_if(workspaces_.begin(), workspaces_.end(),
                                     [id](const Workspace& w) { return w.id == id; }),
                      workspaces_.end());
    if (active_ == id) active_ = moveTo != 0 ? moveTo : workspaces_.front().id;
    return released;
}

bool Desktop::setActive(uint32_t id) {
    if (!workspace(id)) return false;
    active_ = id;
    return true;
}

bool Desktop::setWallpaper(uint32_t id, const std::string& ref) {
    if (!validate::isWallpaperRef(ref) || !workspace(id)) return false;
    // One wallpaper for the whole desktop: switching workspace never changes the background.
    for (auto& w : workspaces_) w.wallpaper = ref;
    return true;
}

const Workspace* Desktop::workspace(uint32_t id) const {
    for (const auto& w : workspaces_)
        if (w.id == id) return &w;
    return nullptr;
}

uint32_t Desktop::neighbour(int direction) const {
    if (workspaces_.empty()) return 0;
    auto it = std::find_if(workspaces_.begin(), workspaces_.end(),
                           [this](const Workspace& w) { return w.id == active_; });
    long idx = it == workspaces_.end() ? 0 : it - workspaces_.begin();
    long n = static_cast<long>(workspaces_.size());
    long next = ((idx + (direction >= 0 ? 1 : -1)) % n + n) % n;
    return workspaces_[static_cast<size_t>(next)].id;
}

void Desktop::ensureWorkspace() {
    if (workspaces_.empty()) addWorkspace("1");
    if (!workspace(active_)) active_ = workspaces_.front().id;
}

// ---- grid ----

void Desktop::setGrid(int cols, int rows) {
    cols_ = std::clamp(cols, 3, 12);
    rows_ = std::clamp(rows, 3, 12);
    relocateOutside();
}

void Desktop::relocateOutside() {
    auto outside = [this](const Cell& c) { return c.col < 0 || c.row < 0 || c.col >= cols_ || c.row >= rows_; };
    for (auto& s : shortcuts_) {
        if (!outside(s.cell)) continue;
        if (auto c = freeCell(s.workspaceId)) s.cell = *c;
        else s.cell = {std::min(s.cell.col, cols_ - 1), std::min(s.cell.row, rows_ - 1)};
    }
    for (auto& f : folders_) {
        if (!outside(f.cell)) continue;
        if (auto c = freeCell(f.workspaceId)) f.cell = *c;
        else f.cell = {std::min(f.cell.col, cols_ - 1), std::min(f.cell.row, rows_ - 1)};
    }
}

uint32_t Desktop::occupant(uint32_t workspaceId, Cell c) const {
    for (const auto& s : shortcuts_)
        if (s.workspaceId == workspaceId && s.cell == c) return s.id;
    for (const auto& f : folders_)
        if (f.workspaceId == workspaceId && f.cell == c) return f.id;
    return 0;
}

bool Desktop::occupied(uint32_t workspaceId, Cell c, uint32_t ignoreId) const {
    for (const auto& s : shortcuts_)
        if (s.id != ignoreId && s.workspaceId == workspaceId && s.cell == c) return true;
    for (const auto& f : folders_)
        if (f.id != ignoreId && f.workspaceId == workspaceId && f.cell == c) return true;
    return false;
}

// A cell counts as under a widget when a visible widget of the same workspace
// covers more than a quarter of it in the current orientation. Testing only
// the centre let icons squeeze into the gap between two stacked widgets.
bool Desktop::underWidget(uint32_t workspaceId, Cell c) const {
    float cw = 1.0f / static_cast<float>(cols_);
    float ch = 1.0f / static_cast<float>(rows_);
    float x0 = static_cast<float>(c.col) * cw;
    float y0 = static_cast<float>(c.row) * ch;
    for (const auto& w : widgets_) {
        if (w.workspaceId != workspaceId || !w.visible) continue;
        WidgetRect r = widgetRect(w);
        float ox = std::min(x0 + cw, r.x + r.w) - std::max(x0, r.x);
        float oy = std::min(y0 + ch, r.y + r.h) - std::max(y0, r.y);
        if (ox > 0 && oy > 0 && ox * oy > 0.25f * cw * ch) return true;
    }
    return false;
}

// Prefers cells no widget covers. When every such cell is taken, a covered
// cell is still better than refusing the shortcut.
std::optional<Cell> Desktop::freeCell(uint32_t workspaceId) const {
    for (bool allowCovered : {false, true})
        for (int r = 0; r < rows_; ++r)
            for (int c = 0; c < cols_; ++c)
                if (!occupied(workspaceId, {c, r}) && (allowCovered || !underWidget(workspaceId, {c, r})))
                    return Cell{c, r};
    return std::nullopt;
}

// ---- shortcuts and folders ----

Shortcut* Desktop::shortcut(uint32_t id) {
    for (auto& s : shortcuts_)
        if (s.id == id) return &s;
    return nullptr;
}

Folder* Desktop::folder(uint32_t id) {
    for (auto& f : folders_)
        if (f.id == id) return &f;
    return nullptr;
}

uint32_t Desktop::addShortcut(uint32_t workspaceId, const std::string& appKey, std::optional<Cell> cell) {
    if (!workspace(workspaceId) || !validate::isAppKey(appKey)) return 0;
    for (const auto& s : shortcuts_)
        if (s.workspaceId == workspaceId && s.appKey == appKey) return s.id;
    Cell target;
    if (cell && cell->col >= 0 && cell->row >= 0 && cell->col < cols_ && cell->row < rows_ &&
        !occupied(workspaceId, *cell)) {
        target = *cell;
    } else if (auto free = freeCell(workspaceId)) {
        target = *free;
    } else {
        return 0;
    }
    Shortcut s{nextItemId_++, workspaceId, appKey, target};
    shortcuts_.push_back(s);
    return s.id;
}

bool Desktop::moveItem(uint32_t itemId, Cell cell) {
    if (cell.col < 0 || cell.row < 0 || cell.col >= cols_ || cell.row >= rows_) return false;
    Cell* from = nullptr;
    uint32_t ws = 0;
    if (Shortcut* s = shortcut(itemId)) {
        from = &s->cell;
        ws = s->workspaceId;
    } else if (Folder* f = folder(itemId)) {
        from = &f->cell;
        ws = f->workspaceId;
    }
    if (!from) return false;
    if (*from == cell) return true;
    uint32_t other = occupant(ws, cell);
    Cell old = *from;
    *from = cell;
    if (other != 0) {
        if (Shortcut* s = shortcut(other)) s->cell = old;
        else if (Folder* f = folder(other)) f->cell = old;
    }
    return true;
}

bool Desktop::moveItemToWorkspace(uint32_t itemId, uint32_t workspaceId) {
    if (!workspace(workspaceId)) return false;
    auto free = freeCell(workspaceId);
    if (Shortcut* s = shortcut(itemId)) {
        if (s->workspaceId == workspaceId) return true;
        for (const auto& o : shortcuts_)
            if (o.workspaceId == workspaceId && o.appKey == s->appKey) return removeItem(itemId);
        if (!free) return false;
        s->workspaceId = workspaceId;
        s->cell = *free;
        return true;
    }
    if (Folder* f = folder(itemId)) {
        if (f->workspaceId == workspaceId) return true;
        if (!free) return false;
        f->workspaceId = workspaceId;
        f->cell = *free;
        return true;
    }
    return false;
}

bool Desktop::removeItem(uint32_t itemId) {
    auto byId = [itemId](const auto& e) { return e.id == itemId; };
    auto sit = std::find_if(shortcuts_.begin(), shortcuts_.end(), byId);
    if (sit != shortcuts_.end()) {
        shortcuts_.erase(sit);
        return true;
    }
    auto fit = std::find_if(folders_.begin(), folders_.end(), byId);
    if (fit != folders_.end()) {
        folders_.erase(fit);
        return true;
    }
    return false;
}

uint32_t Desktop::createFolder(uint32_t a, uint32_t b, const std::string& name) {
    Shortcut* sa = shortcut(a);
    Shortcut* sb = shortcut(b);
    if (!sa || !sb || a == b || sa->workspaceId != sb->workspaceId) return 0;
    std::string n = cleanName(name, 48);
    if (n.empty()) n = "Folder";
    Folder f{nextItemId_++, sa->workspaceId, n, sa->cell, {sa->appKey}};
    if (sb->appKey != sa->appKey) f.apps.push_back(sb->appKey);
    removeItem(a);
    removeItem(b);
    folders_.push_back(f);
    return f.id;
}

bool Desktop::addToFolder(uint32_t folderId, uint32_t shortcutId) {
    Folder* f = folder(folderId);
    Shortcut* s = shortcut(shortcutId);
    if (!f || !s || f->workspaceId != s->workspaceId) return false;
    if (std::find(f->apps.begin(), f->apps.end(), s->appKey) == f->apps.end()) {
        if (f->apps.size() >= kMaxFolderApps) return false;
        f->apps.push_back(s->appKey);
    }
    return removeItem(shortcutId);
}

uint32_t Desktop::removeFromFolder(uint32_t folderId, const std::string& appKey) {
    Folder* f = folder(folderId);
    if (!f) return 0;
    auto it = std::find(f->apps.begin(), f->apps.end(), appKey);
    if (it == f->apps.end()) return 0;
    uint32_t ws = f->workspaceId;
    f->apps.erase(it);
    bool empty = f->apps.empty();
    Cell folderCell = f->cell;
    if (empty) removeItem(folderId);  // f is dangling after this line
    std::optional<Cell> cell = empty ? std::optional<Cell>(folderCell) : freeCell(ws);
    if (!cell) {
        // No room on the grid: put the app back so nothing is lost.
        if (Folder* again = folder(folderId)) again->apps.push_back(appKey);
        return 0;
    }
    return addShortcut(ws, appKey, cell);
}

bool Desktop::renameFolder(uint32_t folderId, const std::string& name) {
    Folder* f = folder(folderId);
    std::string n = cleanName(name, 48);
    if (!f || n.empty()) return false;
    f->name = n;
    return true;
}

size_t Desktop::removePackage(const std::string& package, int64_t userSerial) {
    if (package.empty()) return 0;
    size_t removed = 0;
    auto matches = [&](const std::string& key) {
        if (packageOf(key) != package) return false;
        return userSerial == kAnyUser || userOf(key) == std::optional<int64_t>(userSerial);
    };
    size_t before = shortcuts_.size();
    shortcuts_.erase(std::remove_if(shortcuts_.begin(), shortcuts_.end(),
                                    [&](const Shortcut& s) { return matches(s.appKey); }),
                     shortcuts_.end());
    removed += before - shortcuts_.size();
    for (auto& f : folders_) {
        size_t n = f.apps.size();
        f.apps.erase(std::remove_if(f.apps.begin(), f.apps.end(), matches), f.apps.end());
        removed += n - f.apps.size();
    }
    folders_.erase(std::remove_if(folders_.begin(), folders_.end(),
                                  [](const Folder& f) { return f.apps.empty(); }),
                   folders_.end());
    for (auto* l : {&dock_, &taskbar_}) {
        size_t n = l->size();
        l->erase(std::remove_if(l->begin(), l->end(),
                                [&](const std::string& e) { return e.rfind("app:", 0) == 0 && matches(e.substr(4)); }),
                 l->end());
        removed += n - l->size();
    }
    for (auto* l : {&favorites_, &hidden_, &recent_}) {
        size_t n = l->size();
        l->erase(std::remove_if(l->begin(), l->end(), matches), l->end());
        removed += n - l->size();
    }
    return removed;
}

// ---- widgets ----

Widget* Desktop::mutableWidget(uint32_t id) {
    for (auto& w : widgets_)
        if (w.id == id) return &w;
    return nullptr;
}

const Widget* Desktop::widget(uint32_t id) const {
    for (const auto& w : widgets_)
        if (w.id == id) return &w;
    return nullptr;
}

// First spot, top to bottom and then left to right, where a w x h widget
// touches no visible widget and no shortcut or folder of the workspace.
std::optional<std::pair<float, float>> Desktop::freeWidgetSpot(uint32_t workspaceId, float w, float h) const {
    constexpr float kGap = 0.01f;
    constexpr float kStep = 0.01f;
    struct Box { float x, y, w, h; };
    std::vector<Box> taken;
    for (const auto& o : widgets_) {
        if (o.workspaceId != workspaceId || !o.visible) continue;
        WidgetRect r = widgetRect(o);
        taken.push_back({r.x, r.y, r.w, r.h});
    }
    float cw = 1.0f / static_cast<float>(cols_);
    float ch = 1.0f / static_cast<float>(rows_);
    auto addCell = [&](uint32_t ws, Cell c) {
        if (ws == workspaceId) taken.push_back({static_cast<float>(c.col) * cw, static_cast<float>(c.row) * ch, cw, ch});
    };
    for (const auto& s : shortcuts_) addCell(s.workspaceId, s.cell);
    for (const auto& f : folders_) addCell(f.workspaceId, f.cell);
    auto clear = [&](float x, float y) {
        for (const auto& b : taken)
            if (x < b.x + b.w + kGap && b.x < x + w + kGap && y < b.y + b.h + kGap && b.y < y + h + kGap) return false;
        return true;
    };
    float x0 = std::max(0.0f, std::min(0.03f, (1.0f - w) / 2));
    for (float y = 0.01f; y + h <= 1.0f + 1e-4f; y += kStep)
        for (float x = x0; x + w <= 1.0f + 1e-4f; x += kStep)
            if (clear(x, y)) return std::make_pair(x, y);
    return std::nullopt;
}

uint32_t Desktop::addWidget(uint32_t workspaceId, const std::string& kind, int32_t appWidgetId,
                            float x, float y, float w, float h) {
    if (!workspace(workspaceId) || !validate::isToken(kind, 32)) return 0;
    if ((kind == "appwidget") != (appWidgetId >= 0)) return 0;
    if (!finite(x) || !finite(y) || !finite(w) || !finite(h)) return 0;
    size_t count = 0;
    for (const auto& o : widgets_)
        if (o.workspaceId == workspaceId) ++count;
    if (count >= kMaxWidgetsPerWorkspace) return 0;
    if (x < 0 || y < 0) {
        w = clampf(w, 0.05f, 1.0f);
        h = clampf(h, 0.05f, 1.0f);
        if (auto spot = freeWidgetSpot(workspaceId, w, h)) {
            std::tie(x, y) = *spot;
        } else {
            // Nowhere free: cascade from the top left so the new widget is at least visible.
            float offset = static_cast<float>(count % 5) * 0.04f;
            x = 0.03f + offset;
            y = 0.03f + offset;
        }
    }
    Widget wd;
    wd.id = nextItemId_++;
    wd.workspaceId = workspaceId;
    wd.kind = kind;
    wd.appWidgetId = appWidgetId;
    (landscape() ? wd.landscape : wd.portrait) = makeRect(x, y, w, h, aspect_);
    widgets_.push_back(wd);
    return wd.id;
}

void Desktop::setCanvasAspect(float a) {
    if (finite(a) && a > 0.05f && a < 20.0f) aspect_ = a;
}

bool Desktop::setWidgetRect(uint32_t id, float x, float y, float w, float h) {
    Widget* wd = mutableWidget(id);
    if (!wd || !finite(x) || !finite(y) || !finite(w) || !finite(h)) return false;
    (landscape() ? wd->landscape : wd->portrait) = makeRect(x, y, w, h, aspect_);
    uncoverItems(wd->workspaceId);
    return true;
}

// A widget moved or resized over icons would hide them and swallow their taps.
// Each covered icon moves to the first free cell no widget covers; with none
// left it stays where it is.
void Desktop::uncoverItems(uint32_t workspaceId) {
    auto clearCell = [this, workspaceId]() -> std::optional<Cell> {
        for (int r = 0; r < rows_; ++r)
            for (int c = 0; c < cols_; ++c)
                if (!occupied(workspaceId, {c, r}) && !underWidget(workspaceId, {c, r})) return Cell{c, r};
        return std::nullopt;
    };
    for (auto& s : shortcuts_)
        if (s.workspaceId == workspaceId && underWidget(workspaceId, s.cell))
            if (auto c = clearCell()) s.cell = *c;
    for (auto& f : folders_)
        if (f.workspaceId == workspaceId && underWidget(workspaceId, f.cell))
            if (auto c = clearCell()) f.cell = *c;
}

WidgetRect Desktop::widgetRect(const Widget& w) const {
    const WidgetRect& own = landscape() ? w.landscape : w.portrait;
    if (own.set) return own;
    const WidgetRect& other = landscape() ? w.portrait : w.landscape;
    if (!other.set) return makeRect(0, 0, 0.4f, 0.2f, aspect_);
    // Keep the widget's physical width: a rectangle arranged on a narrow
    // canvas becomes a narrower fraction of a wide one, and the reverse.
    float k = other.aspect > 0 ? other.aspect / aspect_ : 1.0f;
    WidgetRect r = makeRect(other.x * k, other.y, other.w * k, other.h, aspect_);
    r.set = false;
    return r;
}

std::optional<int32_t> Desktop::removeWidget(uint32_t id) {
    auto it = std::find_if(widgets_.begin(), widgets_.end(), [id](const Widget& w) { return w.id == id; });
    if (it == widgets_.end()) return std::nullopt;
    int32_t appWidgetId = it->appWidgetId;
    widgets_.erase(it);
    return appWidgetId;
}

bool Desktop::setWidgetConfig(uint32_t id, const std::string& key, const std::string& value) {
    Widget* wd = mutableWidget(id);
    if (!wd || !validate::isToken(key, 32)) return false;
    if (value.size() > kMaxConfigValue) return false;
    if (!wd->config.count(key) && wd->config.size() >= kMaxWidgetConfig) return false;
    std::string clean = validate::truncateUtf8(value, kMaxConfigValue, true);
    wd->config[key] = clean;
    return true;
}

bool Desktop::setWidgetVisible(uint32_t id, bool visible) {
    Widget* wd = mutableWidget(id);
    if (!wd) return false;
    wd->visible = visible;
    return true;
}

bool Desktop::moveWidgetToWorkspace(uint32_t id, uint32_t workspaceId) {
    Widget* wd = mutableWidget(id);
    if (!wd || !workspace(workspaceId)) return false;
    wd->workspaceId = workspaceId;
    return true;
}

// ---- pins ----

std::vector<std::string>& Desktop::list(PinList l) {
    switch (l) {
        case PinList::Dock: return dock_;
        case PinList::Taskbar: return taskbar_;
        case PinList::Favorites: return favorites_;
        case PinList::Hidden: return hidden_;
        case PinList::Recent: return recent_;
    }
    return dock_;
}

const std::vector<std::string>& Desktop::pins(PinList l) const {
    return const_cast<Desktop*>(this)->list(l);
}

size_t Desktop::limit(PinList l) const {
    switch (l) {
        case PinList::Dock: return 16;
        case PinList::Taskbar: return 16;
        case PinList::Favorites: return 64;
        case PinList::Hidden: return 512;
        case PinList::Recent: return 12;
    }
    return 16;
}

bool Desktop::isPinEntry(PinList l, const std::string& e) {
    if (l == PinList::Dock || l == PinList::Taskbar) {
        if (e.rfind("app:", 0) == 0) return validate::isAppKey(e.substr(4));
        for (const char* prefix : {"tool:", "action:", "intent:"}) {
            std::string p(prefix);
            if (e.rfind(p, 0) == 0) return validate::isToken(e.substr(p.size()), 32);
        }
        return false;
    }
    return validate::isAppKey(e);
}

bool Desktop::pin(PinList l, const std::string& entry, int index) {
    if (!isPinEntry(l, entry)) return false;
    auto& v = list(l);
    auto it = std::find(v.begin(), v.end(), entry);
    if (l == PinList::Recent) {
        if (it != v.end()) v.erase(it);
        v.insert(v.begin(), entry);
        if (v.size() > limit(l)) v.resize(limit(l));
        return true;
    }
    if (it != v.end() || v.size() >= limit(l)) return false;
    if (index < 0 || static_cast<size_t>(index) > v.size()) v.push_back(entry);
    else v.insert(v.begin() + index, entry);
    return true;
}

bool Desktop::unpin(PinList l, const std::string& entry) {
    auto& v = list(l);
    auto it = std::find(v.begin(), v.end(), entry);
    if (it == v.end()) return false;
    v.erase(it);
    return true;
}

bool Desktop::movePin(PinList l, const std::string& entry, int delta) {
    auto& v = list(l);
    auto it = std::find(v.begin(), v.end(), entry);
    if (it == v.end()) return false;
    long from = it - v.begin();
    long to = std::clamp(from + delta, 0L, static_cast<long>(v.size()) - 1);
    if (from == to) return false;
    std::string e = *it;
    v.erase(it);
    v.insert(v.begin() + to, e);
    return true;
}

bool Desktop::isPinned(PinList l, const std::string& entry) const {
    const auto& v = pins(l);
    return std::find(v.begin(), v.end(), entry) != v.end();
}

void Desktop::setPins(PinList l, std::vector<std::string> entries) {
    auto& v = list(l);
    v.clear();
    for (auto& e : entries) {
        if (v.size() >= limit(l)) break;
        if (isPinEntry(l, e) && std::find(v.begin(), v.end(), e) == v.end()) v.push_back(std::move(e));
    }
}

// ---- serialisation ----

json::Value Desktop::toJson(bool resolved) const {
    json::Value root{json::Object{}};
    json::Value ws{json::Array{}};
    for (const auto& w : workspaces_) {
        json::Value o{json::Object{}};
        o.set("id", w.id);
        o.set("name", w.name);
        o.set("wallpaper", w.wallpaper);
        ws.push(std::move(o));
    }
    root.set("workspaces", std::move(ws));
    root.set("active", active_);
    root.set("cols", cols_);
    root.set("rows", rows_);

    json::Value sc{json::Array{}};
    for (const auto& s : shortcuts_) {
        json::Value o{json::Object{}};
        o.set("id", s.id);
        o.set("ws", s.workspaceId);
        o.set("app", s.appKey);
        o.set("col", s.cell.col);
        o.set("row", s.cell.row);
        sc.push(std::move(o));
    }
    root.set("shortcuts", std::move(sc));

    json::Value fo{json::Array{}};
    for (const auto& f : folders_) {
        json::Value o{json::Object{}};
        o.set("id", f.id);
        o.set("ws", f.workspaceId);
        o.set("name", f.name);
        o.set("col", f.cell.col);
        o.set("row", f.cell.row);
        json::Value apps{json::Array{}};
        for (const auto& a : f.apps) apps.push(a);
        o.set("apps", std::move(apps));
        fo.push(std::move(o));
    }
    root.set("folders", std::move(fo));

    json::Value wi{json::Array{}};
    for (const auto& w : widgets_) {
        json::Value o{json::Object{}};
        o.set("id", w.id);
        o.set("ws", w.workspaceId);
        o.set("kind", w.kind);
        o.set("appWidgetId", static_cast<double>(w.appWidgetId));
        if (w.portrait.set) o.set("portrait", rectJson(w.portrait));
        if (w.landscape.set) o.set("landscape", rectJson(w.landscape));
        if (resolved) {
            WidgetRect r = widgetRect(w);
            o.set("x", static_cast<double>(r.x));
            o.set("y", static_cast<double>(r.y));
            o.set("w", static_cast<double>(r.w));
            o.set("h", static_cast<double>(r.h));
        }
        o.set("visible", w.visible);
        json::Value cfg{json::Object{}};
        for (const auto& [k, v] : w.config) cfg.set(k, v);
        o.set("config", std::move(cfg));
        wi.push(std::move(o));
    }
    root.set("widgets", std::move(wi));

    auto strings = [](const std::vector<std::string>& v) {
        json::Value a{json::Array{}};
        for (const auto& s : v) a.push(s);
        return a;
    };
    root.set("dock", strings(dock_));
    root.set("taskbar", strings(taskbar_));
    root.set("favorites", strings(favorites_));
    root.set("hidden", strings(hidden_));
    root.set("recent", strings(recent_));
    return root;
}

size_t Desktop::fromJson(const json::Value& v) {
    clear();
    size_t skipped = 0;
    std::set<uint32_t> usedItemIds;
    uint32_t maxItem = 0;

    if (const json::Value* c = v.find("cols")) cols_ = validate::clampedInt(c->asNumber(5), 3, 12, 5);
    if (const json::Value* r = v.find("rows")) rows_ = validate::clampedInt(r->asNumber(6), 3, 12, 6);

    if (const json::Value* ws = v.find("workspaces")) {
        for (const auto& item : ws->asArray()) {
            auto id = validate::idFrom(item.find("id"));
            const json::Value* nameV = item.find("name");
            std::string name = cleanName(nameV ? nameV->asString() : std::string(), 48);
            if (!id || name.empty() || workspace(*id) || workspaces_.size() >= kMaxWorkspaces) {
                ++skipped;
                continue;
            }
            Workspace w{*id, name, "builtin:theme"};
            const json::Value* wp = item.find("wallpaper");
            if (wp && validate::isWallpaperRef(wp->asString())) w.wallpaper = wp->asString();
            workspaces_.push_back(w);
            nextWorkspaceId_ = std::max(nextWorkspaceId_, *id + 1);
        }
    }
    ensureWorkspace();
    if (auto a = validate::idFrom(v.find("active")); a && workspace(*a)) active_ = *a;
    // Layouts saved while each workspace had its own wallpaper take the active one's everywhere.
    if (const Workspace* a = workspace(active_)) {
        const std::string shared = a->wallpaper;
        for (auto& w : workspaces_) w.wallpaper = shared;
    }

    auto claimId = [&](const json::Value& item) -> std::optional<uint32_t> {
        auto id = validate::idFrom(item.find("id"));
        if (!id || usedItemIds.count(*id)) return std::nullopt;
        usedItemIds.insert(*id);
        maxItem = std::max(maxItem, *id);
        return id;
    };
    auto cellOf = [](const json::Value& item) {
        return Cell{validate::clampedInt(validate::numberOr(item.find("col"), -1), -1, 1000, -1),
                    validate::clampedInt(validate::numberOr(item.find("row"), -1), -1, 1000, -1)};
    };

    if (const json::Value* sc = v.find("shortcuts")) {
        for (const auto& item : sc->asArray()) {
            auto ws = validate::idFrom(item.find("ws"));
            const json::Value* app = item.find("app");
            if (!ws || !workspace(*ws) || !app || !validate::isAppKey(app->asString())) {
                ++skipped;
                continue;
            }
            auto id = claimId(item);
            if (!id) {
                ++skipped;
                continue;
            }
            shortcuts_.push_back({*id, *ws, app->asString(), cellOf(item)});
        }
    }
    if (const json::Value* fo = v.find("folders")) {
        for (const auto& item : fo->asArray()) {
            auto ws = validate::idFrom(item.find("ws"));
            const json::Value* nameV = item.find("name");
            std::string name = cleanName(nameV ? nameV->asString() : std::string(), 48);
            if (!ws || !workspace(*ws) || name.empty()) {
                ++skipped;
                continue;
            }
            Folder f;
            f.workspaceId = *ws;
            f.name = name;
            f.cell = cellOf(item);
            if (const json::Value* apps = item.find("apps")) {
                for (const auto& a : apps->asArray()) {
                    if (f.apps.size() < kMaxFolderApps && validate::isAppKey(a.asString()) &&
                        std::find(f.apps.begin(), f.apps.end(), a.asString()) == f.apps.end())
                        f.apps.push_back(a.asString());
                }
            }
            auto id = f.apps.empty() ? std::nullopt : claimId(item);
            if (!id) {
                ++skipped;
                continue;
            }
            f.id = *id;
            folders_.push_back(std::move(f));
        }
    }
    if (const json::Value* wi = v.find("widgets")) {
        std::map<uint32_t, size_t> perWs;
        for (const auto& item : wi->asArray()) {
            auto ws = validate::idFrom(item.find("ws"));
            const json::Value* kind = item.find("kind");
            double awid = validate::numberOr(item.find("appWidgetId"), -1);
            bool kindOk = kind && validate::isToken(kind->asString(), 32);
            bool appOk = kindOk && ((kind->asString() == "appwidget") == (awid >= 0)) && awid < 2147483647.0;
            if (!ws || !workspace(*ws) || !appOk || perWs[*ws] >= kMaxWidgetsPerWorkspace) {
                ++skipped;
                continue;
            }
            auto id = claimId(item);
            if (!id) {
                ++skipped;
                continue;
            }
            Widget w;
            w.id = *id;
            w.workspaceId = *ws;
            w.kind = kind->asString();
            w.appWidgetId = awid >= 0 ? static_cast<int32_t>(awid) : -1;
            if (const json::Value* p = item.find("portrait"); p && p->isObject()) w.portrait = rectFrom(*p);
            if (const json::Value* l = item.find("landscape"); l && l->isObject()) w.landscape = rectFrom(*l);
            // A widget with no stored orientation keeps its top-level rectangle as portrait.
            if (!w.portrait.set && !w.landscape.set) w.portrait = rectFrom(item);
            const json::Value* vis = item.find("visible");
            w.visible = vis ? vis->asBool(true) : true;
            if (const json::Value* cfg = item.find("config")) {
                for (const auto& [k, val] : cfg->asObject()) {
                    if (w.config.size() >= kMaxWidgetConfig) break;
                    if (validate::isToken(k, 32) && val.isString() && val.asString().size() <= kMaxConfigValue)
                        w.config[k] = val.asString();
                }
            }
            ++perWs[*ws];
            widgets_.push_back(std::move(w));
        }
    }
    nextItemId_ = maxItem + 1;

    // Two items claiming one cell: the later one moves to a free cell.
    std::vector<std::pair<uint32_t, Cell>> seen;
    auto settle = [&](uint32_t ws, Cell& cell) {
        for (const auto& [w, c] : seen) {
            if (w == ws && c == cell) {
                for (int r = 0; r < rows_; ++r)
                    for (int col = 0; col < cols_; ++col) {
                        Cell cand{col, r};
                        bool taken = std::any_of(seen.begin(), seen.end(), [&](const auto& p) {
                            return p.first == ws && p.second == cand;
                        });
                        if (!taken) {
                            cell = cand;
                            seen.emplace_back(ws, cell);
                            return;
                        }
                    }
                break;
            }
        }
        seen.emplace_back(ws, cell);
    };
    for (auto& s : shortcuts_) settle(s.workspaceId, s.cell);
    for (auto& f : folders_) settle(f.workspaceId, f.cell);
    relocateOutside();

    auto loadList = [&](const char* key, PinList l) {
        std::vector<std::string> entries;
        if (const json::Value* a = v.find(key))
            for (const auto& e : a->asArray()) {
                if (isPinEntry(l, e.asString())) entries.push_back(e.asString());
                else ++skipped;
            }
        setPins(l, std::move(entries));
    };
    loadList("dock", PinList::Dock);
    loadList("taskbar", PinList::Taskbar);
    loadList("favorites", PinList::Favorites);
    loadList("hidden", PinList::Hidden);
    loadList("recent", PinList::Recent);
    return skipped;
}

}  // namespace lx
