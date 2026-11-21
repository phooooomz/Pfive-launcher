// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// User preferences with a fixed schema. Every value that reaches the store has
// been checked against the schema, so the UI can trust what it reads back.
#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "lx/json.hpp"

namespace lx {

class Settings {
public:
    enum class Kind { Bool, Int, Float, Choice };

    struct Spec {
        std::string key;
        Kind kind;
        double min = 0, max = 0;          // Int and Float
        std::vector<std::string> choices;  // Choice
        std::string defaultValue;
    };

    Settings();

    static const std::vector<Spec>& schema();
    static const Spec* spec(const std::string& key);

    // Values travel as strings across JNI. Returns false and leaves the store
    // unchanged when the key is unknown or the value does not fit the schema.
    bool set(const std::string& key, const std::string& value);
    std::string get(const std::string& key) const;
    bool getBool(const std::string& key) const;
    int getInt(const std::string& key) const;
    double getFloat(const std::string& key) const;

    void resetDefaults();
    // Applies a named preset over the current values. Unknown preset: false.
    bool applyPreset(const std::string& preset);
    static std::vector<std::string> presets();

    json::Value toJson() const;
    // Unknown keys and invalid values are ignored one by one; returns how many.
    size_t fromJson(const json::Value& v);

private:
    static std::optional<std::string> normalize(const Spec& s, const std::string& value);
    std::map<std::string, std::string> values_;
};

}  // namespace lx
