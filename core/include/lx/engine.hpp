// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// The launcher's model: settings, workspaces and their contents, internal
// windows. Engine itself is not thread safe; the JNI layer serialises access
// with mutex().
#pragma once

#include <deque>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "lx/desktop.hpp"
#include "lx/persistence.hpp"
#include "lx/settings.hpp"
#include "lx/window_manager.hpp"

namespace lx {

struct DefaultsSpec {
    std::vector<std::string> workspaceNames;  // at least one
    std::string welcomeTitle = "Welcome";
    bool landscape = false;
};

struct LoadReport {
    bool ok = false;
    bool fresh = false;        // nothing stored yet, defaults were used
    bool usedBackup = false;
    bool mainCorrupt = false;
    int schemaVersion = 0;
    size_t skipped = 0;        // entities dropped during validation
    std::string error;
};

class Engine {
public:
    static constexpr int kSchemaVersion = 1;
    static constexpr const char* kFormat = "lx-launcher-layout";
    static constexpr size_t kMaxImportBytes = 1024 * 1024;

    Engine();

    void resetToDefaults(const DefaultsSpec& spec);
    // Replaces only layout (workspaces, items, windows); settings stay.
    void resetLayout(const DefaultsSpec& spec);

    std::string exportDocument() const;
    // On error the current state is left untouched. App widgets are dropped
    // because their ids are only meaningful on the device that bound them.
    LoadReport importDocument(std::string_view text);
    LoadReport loadFrom(const Store& store, const DefaultsSpec& defaults);
    bool saveTo(const Store& store) const;

    // Removes a workspace and keeps windows consistent: windows move with the
    // content or are closed. Returns released app widget ids.
    std::optional<std::vector<int32_t>> removeWorkspace(uint32_t id, uint32_t moveTo);
    // Keeps the desktop grid in step with the settings.
    bool setSetting(const std::string& key, const std::string& value);
    bool applyPreset(const std::string& name);

    // Window viewport and the canvas shape used for widget geometry.
    // canvasAspect is the desktop's own width over height; 0 takes it from usable. The window area
    // shrinks above the keyboard, but widgets placed meanwhile must keep the desktop's proportions.
    void setViewport(const Rect& usable, const Rect& full, float canvasAspect = 0);
    // Window calls that name a workspace check it exists; the window manager alone cannot.
    uint32_t openWindow(const std::string& kind, const std::string& title, uint32_t workspaceId, WindowState state);
    bool moveWindowToWorkspace(uint32_t windowId, uint32_t workspaceId);

    // Everything the UI renders, as JSON.
    std::string snapshot() const;

    Settings& settings() { return settings_; }
    const Settings& settings() const { return settings_; }
    Desktop& desktop() { return desktop_; }
    const Desktop& desktop() const { return desktop_; }
    WindowManager& windows() { return windows_; }
    const WindowManager& windows() const { return windows_; }

    void log(std::string event);
    std::vector<std::string> diagnostics() const;

    std::mutex& mutex() { return mutex_; }

private:
    json::Value document() const;
    // Applies a validated document. Returns false when the format or schema
    // version is unusable.
    bool apply(const json::Value& doc, LoadReport& report, bool dropAppWidgets);
    // Parses a stored layout with the store's own size limit and applies it.
    bool applyStored(const std::string& payload, LoadReport& report);
    void syncGrid();

    Settings settings_;
    Desktop desktop_;
    WindowManager windows_;
    std::deque<std::string> diagnostics_;
    std::mutex mutex_;
};

}  // namespace lx
