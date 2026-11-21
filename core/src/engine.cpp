// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/engine.hpp"
#include "lx/validate.hpp"

#include <algorithm>

namespace lx {

namespace {

constexpr size_t kMaxDiagnostics = 64;

// The largest id an imported file may use. Ids count up from 1 one at a time, so an export never comes near it. A
// file with ids up to 2^31 - 1 was taken, the next id became 2^31, and the following load refused it: what was added
// after such an import was lost. Below this limit more than a billion ids are left before that happens.
constexpr double kMaxImportedId = 1e9;

// No member named "id" anywhere in [v] is above [limit].
bool idsWithin(const lx::json::Value& v, double limit) {
    if (v.isObject()) {
        for (const auto& [key, child] : v.asObject()) {
            if (key == "id" && child.isNumber() && child.asNumber() > limit) return false;
            if (!idsWithin(child, limit)) return false;
        }
    } else if (v.isArray()) {
        for (const auto& child : v.asArray()) {
            if (!idsWithin(child, limit)) return false;
        }
    }
    return true;
}

// Default widget placement, as fractions of the desktop canvas.
struct Placement {
    const char* kind;
    float x, y, w, h;
};

// Portrait is a phone: three widgets in the top two thirds, so the bottom rows
// of the grid stay free for app shortcuts. Media controls are one tap away in
// LX Settings; with nothing playing they would only take room here.
constexpr Placement kPortraitWidgets[] = {
    {"clock", 0.03f, 0.01f, 0.94f, 0.14f},
    {"status", 0.03f, 0.17f, 0.94f, 0.29f},
    {"notes", 0.03f, 0.49f, 0.94f, 0.15f},
};

constexpr Placement kLandscapeWidgets[] = {
    {"clock", 0.70f, 0.02f, 0.28f, 0.22f},
    {"status", 0.70f, 0.26f, 0.28f, 0.34f},
    {"media", 0.70f, 0.62f, 0.28f, 0.17f},
    {"notes", 0.70f, 0.81f, 0.28f, 0.17f},
};

const char* const kDefaultDock[] = {
    "action:home", "tool:files", "intent:browser", "tool:settings",
    "action:drawer", "action:games", "intent:gallery", "tool:monitor",
};

}  // namespace

Engine::Engine() {
    resetToDefaults({{"1"}, "Welcome", false});
}

void Engine::syncGrid() {
    desktop_.setGrid(settings_.getInt("desktop.gridCols"), settings_.getInt("desktop.gridRows"));
}

void Engine::resetLayout(const DefaultsSpec& spec) {
    desktop_.clear();
    windows_.clear();
    syncGrid();
    for (const auto& name : spec.workspaceNames) desktop_.addWorkspace(name);
    if (desktop_.workspaces().empty()) desktop_.addWorkspace("1");
    uint32_t first = desktop_.workspaces().front().id;
    desktop_.setActive(first);
    // Place the defaults for the orientation the host asked for, then return
    // to the real canvas shape.
    float actual = desktop_.canvasAspect();
    if (desktop_.landscape() != spec.landscape) desktop_.setCanvasAspect(spec.landscape ? 1.8f : 0.56f);
    auto place = [&](const auto& widgets) {
        for (const auto& p : widgets) desktop_.addWidget(first, p.kind, -1, p.x, p.y, p.w, p.h);
    };
    if (spec.landscape) place(kLandscapeWidgets);
    else place(kPortraitWidgets);
    desktop_.setCanvasAspect(actual);
    std::vector<std::string> dock(std::begin(kDefaultDock), std::end(kDefaultDock));
    desktop_.setPins(PinList::Dock, dock);
    windows_.setOrientationHint(spec.landscape);
    windows_.open("welcome", spec.welcomeTitle, first, WindowState::Floating);
}

void Engine::resetToDefaults(const DefaultsSpec& spec) {
    settings_.resetDefaults();
    resetLayout(spec);
}

json::Value Engine::document() const {
    json::Value doc{json::Object{}};
    doc.set("format", kFormat);
    doc.set("version", kSchemaVersion);
    doc.set("settings", settings_.toJson());
    doc.set("desktop", desktop_.toJson());
    doc.set("windows", windows_.toJson());
    doc.set("windowPlaces", windows_.placesToJson());
    return doc;
}

std::string Engine::exportDocument() const { return json::serialize(document()); }

bool Engine::apply(const json::Value& doc, LoadReport& report, bool dropAppWidgets) {
    if (!doc.isObject()) {
        report.error = "not an object";
        return false;
    }
    const json::Value* format = doc.find("format");
    if (!format || format->asString() != kFormat) {
        report.error = "unknown format";
        return false;
    }
    const json::Value* version = doc.find("version");
    int v = version ? validate::clampedInt(version->asNumber(0), 0, 1000000, 0) : 0;
    if (v < 1) {
        report.error = "missing schema version";
        return false;
    }
    if (v > kSchemaVersion) {
        // Written by a newer build. Refuse rather than silently dropping data.
        report.error = "newer schema version";
        report.schemaVersion = v;
        return false;
    }
    // Migration steps from older versions go here, one per version bump.
    json::Value migrated = doc;
    if (dropAppWidgets && !idsWithin(migrated, kMaxImportedId)) {
        // An import, refused whole before anything changes.
        report.error = "ids out of range";
        return false;
    }

    Settings settings;
    Desktop desktop;
    WindowManager windows;
    windows.setViewport(windows_.viewport(), windows_.fullArea());
    windows.setConstraints(windows_.constraints());
    size_t skipped = 0;
    if (const json::Value* s = migrated.find("settings")) skipped += settings.fromJson(*s);
    if (const json::Value* d = migrated.find("desktop")) {
        json::Value desk = *d;
        if (dropAppWidgets) {
            json::Value kept{json::Array{}};
            if (const json::Value* wi = desk.find("widgets")) {
                for (const auto& w : wi->asArray()) {
                    const json::Value* kind = w.find("kind");
                    if (kind && kind->asString() == "appwidget") ++skipped;
                    else kept.push(w);
                }
            }
            desk.set("widgets", std::move(kept));
        }
        skipped += desktop.fromJson(desk);
    } else {
        desktop.addWorkspace("1");
    }
    if (const json::Value* w = migrated.find("windows")) skipped += windows.fromJson(*w);
    if (const json::Value* p = migrated.find("windowPlaces")) skipped += windows.placesFromJson(*p);
    desktop.setGrid(settings.getInt("desktop.gridCols"), settings.getInt("desktop.gridRows"));
    desktop.setCanvasAspect(desktop_.canvasAspect());
    // Windows pointing at a workspace that did not survive validation join the
    // active one instead of disappearing.
    for (const auto& info : windows.all()) {
        if (!desktop.workspace(info.workspaceId)) windows.moveToWorkspace(info.id, desktop.active());
    }

    settings_ = std::move(settings);
    desktop_ = std::move(desktop);
    windows_ = std::move(windows);
    report.ok = true;
    report.schemaVersion = v;
    report.skipped = skipped;
    return true;
}

bool Engine::applyStored(const std::string& payload, LoadReport& report) {
    json::Limits limits;
    limits.maxBytes = Store::kMaxFileBytes;
    json::ParseError err;
    auto doc = json::parse(payload, limits, &err);
    if (!doc) {
        report.error = "invalid JSON: " + err.message;
        return false;
    }
    return apply(*doc, report, false);
}

LoadReport Engine::importDocument(std::string_view text) {
    LoadReport report;
    if (text.size() > kMaxImportBytes) {
        report.error = "file too large";
        return report;
    }
    json::ParseError err;
    json::Limits limits;
    limits.maxBytes = kMaxImportBytes;
    auto doc = json::parse(text, limits, &err);
    if (!doc) {
        report.error = "invalid JSON at byte " + std::to_string(err.offset) + ": " + err.message;
        return report;
    }
    apply(*doc, report, true);
    log(report.ok ? "import ok, skipped " + std::to_string(report.skipped) : "import refused: " + report.error);
    return report;
}

LoadReport Engine::loadFrom(const Store& store, const DefaultsSpec& defaults) {
    LoadReport report;
    LoadResult file = store.load();
    report.mainCorrupt = file.mainCorrupt;
    if (file.mainCorrupt) {
        store.quarantineMain();
        log("layout file failed its checksum and was set aside");
    }
    if (file.source == LoadSource::None) {
        resetToDefaults(defaults);
        report.ok = true;
        report.fresh = !file.mainCorrupt && !file.backupCorrupt;
        report.schemaVersion = kSchemaVersion;
        log(report.fresh ? "first start, defaults created" : "no readable layout, defaults created");
        return report;
    }
    report.usedBackup = file.source == LoadSource::Backup;
    if (!applyStored(file.payload, report)) {
        // The file passed its checksum but does not load: a newer build wrote it, or it is not a
        // layout. It is set aside rather than left where the next saves would rotate over it, and
        // the backup gets its turn.
        log("stored layout unusable (" + report.error + "), set aside");
        store.setAside(file.source, ".unusable");
        if (file.source == LoadSource::Main) {
            LoadResult bak = store.loadBackup();
            if (bak.source == LoadSource::Backup) {
                LoadReport second;
                second.mainCorrupt = file.mainCorrupt;
                if (applyStored(bak.payload, second)) {
                    second.usedBackup = true;
                    log("layout restored from backup");
                    if (second.skipped > 0) log("skipped " + std::to_string(second.skipped) + " invalid entries while loading");
                    return second;
                }
                log("backup unusable too (" + second.error + "), set aside");
                store.setAside(LoadSource::Backup, ".unusable");
            }
        }
        log("defaults created");
        std::string error = report.error;
        int version = report.schemaVersion;
        resetToDefaults(defaults);
        report = LoadReport{};
        report.ok = true;
        report.error = error;
        report.schemaVersion = version;
        report.mainCorrupt = file.mainCorrupt;
        return report;
    }
    if (report.usedBackup) log("layout restored from backup");
    if (report.skipped > 0) log("skipped " + std::to_string(report.skipped) + " invalid entries while loading");
    return report;
}

bool Engine::saveTo(const Store& store) const { return store.save(exportDocument()); }

std::optional<std::vector<int32_t>> Engine::removeWorkspace(uint32_t id, uint32_t moveTo) {
    auto released = desktop_.removeWorkspace(id, moveTo);
    if (!released) return std::nullopt;
    if (moveTo != 0) {
        windows_.reassignWorkspace(id, moveTo);
    } else {
        for (const auto& w : windows_.windowsIn(id)) windows_.close(w.id);
    }
    return released;
}

bool Engine::setSetting(const std::string& key, const std::string& value) {
    if (!settings_.set(key, value)) return false;
    if (key == "desktop.gridCols" || key == "desktop.gridRows") syncGrid();
    return true;
}

bool Engine::applyPreset(const std::string& name) {
    if (!settings_.applyPreset(name)) return false;
    syncGrid();
    return true;
}

uint32_t Engine::openWindow(const std::string& kind, const std::string& title, uint32_t workspaceId, WindowState state) {
    if (!desktop_.workspace(workspaceId)) return 0;
    return windows_.open(kind, title, workspaceId, state);
}

bool Engine::moveWindowToWorkspace(uint32_t windowId, uint32_t workspaceId) {
    if (!desktop_.workspace(workspaceId)) return false;
    return windows_.moveToWorkspace(windowId, workspaceId);
}

void Engine::setViewport(const Rect& usable, const Rect& full, float canvasAspect) {
    windows_.setViewport(usable, full);
    if (canvasAspect > 0) desktop_.setCanvasAspect(canvasAspect);
    else if (usable.h > 0) desktop_.setCanvasAspect(usable.w / usable.h);
}

std::string Engine::snapshot() const {
    json::Value root = desktop_.toJson(true);
    json::Value wins{json::Array{}};
    auto focused = windows_.focused(desktop_.active());
    for (const auto& w : windows_.all()) {
        json::Value o{json::Object{}};
        o.set("id", w.id);
        o.set("kind", w.kind);
        o.set("title", w.title);
        o.set("ws", w.workspaceId);
        o.set("state", toString(w.state));
        o.set("z", static_cast<double>(w.z));
        o.set("x", static_cast<double>(w.bounds.x));
        o.set("y", static_cast<double>(w.bounds.y));
        o.set("w", static_cast<double>(w.bounds.w));
        o.set("h", static_cast<double>(w.bounds.h));
        o.set("focused", focused && *focused == w.id);
        wins.push(std::move(o));
    }
    root.set("windows", std::move(wins));
    root.set("settings", settings_.toJson());
    return json::serialize(root);
}

void Engine::log(std::string event) {
    diagnostics_.push_back(std::move(event));
    while (diagnostics_.size() > kMaxDiagnostics) diagnostics_.pop_front();
}

std::vector<std::string> Engine::diagnostics() const {
    return {diagnostics_.begin(), diagnostics_.end()};
}

}  // namespace lx
