// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Crash-safe storage for the layout document.
//
// File format: one header line "LXL1 <crc32 hex> <payload bytes>\n" followed by
// the payload. A write goes to "<path>.tmp", is fsync'd, the previous file is
// kept as "<path>.bak", and the temp file is renamed into place. A reader that
// finds a damaged main file falls back to the backup.
#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

namespace lx {

uint32_t crc32(std::string_view data);

std::string wrapEnvelope(std::string_view payload);
// Returns the payload when the header is well formed and the checksum matches.
std::optional<std::string> unwrapEnvelope(std::string_view file);

enum class LoadSource { Main, Backup, None };

struct LoadResult {
    LoadSource source = LoadSource::None;
    std::string payload;
    bool mainCorrupt = false;    // main existed but failed the checksum
    bool backupCorrupt = false;
};

class Store {
public:
    static constexpr size_t kMaxFileBytes = 4 * 1024 * 1024;

    explicit Store(std::string path) : path_(std::move(path)) {}

    LoadResult load() const;
    // Returns false (and leaves the existing files untouched) on any I/O error.
    bool save(std::string_view payload) const;
    // Keeps a damaged main file as "<path>.corrupt" for diagnostics.
    void quarantineMain() const;
    // Reads only the backup, for a main file that is intact but will not load.
    LoadResult loadBackup() const;
    // Moves a file that passed its checksum but will not load (written by a newer
    // build, say) to "<path><suffix>" or "<path>.bak<suffix>", so that later saves
    // cannot rotate over it.
    void setAside(LoadSource which, const std::string& suffix) const;

    const std::string& path() const { return path_; }

private:
    std::string path_;
};

std::optional<std::string> readFile(const std::string& path, size_t maxBytes);
bool writeFileAtomic(const std::string& path, std::string_view data);

}  // namespace lx
