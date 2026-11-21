// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Small, bounded JSON reader/writer used for layout persistence and import.
// Every limit is enforced while parsing so hostile input cannot exhaust memory
// or the stack.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace lx::json {

class Value;
using Array = std::vector<Value>;
using Object = std::vector<std::pair<std::string, Value>>;

class Value {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Value() = default;
    Value(std::nullptr_t) {}
    Value(bool b) : data_(b) {}
    Value(double d) : data_(d) {}
    Value(int i) : data_(static_cast<double>(i)) {}
    Value(int64_t i) : data_(static_cast<double>(i)) {}
    Value(uint32_t i) : data_(static_cast<double>(i)) {}
    Value(const char* s) : data_(std::string(s)) {}
    Value(std::string s) : data_(std::move(s)) {}
    Value(Array a) : data_(std::make_shared<Array>(std::move(a))) {}
    Value(Object o) : data_(std::make_shared<Object>(std::move(o))) {}

    Type type() const;
    bool isNull() const { return type() == Type::Null; }
    bool isBool() const { return type() == Type::Bool; }
    bool isNumber() const { return type() == Type::Number; }
    bool isString() const { return type() == Type::String; }
    bool isArray() const { return type() == Type::Array; }
    bool isObject() const { return type() == Type::Object; }

    bool asBool(bool fallback = false) const;
    double asNumber(double fallback = 0.0) const;
    const std::string& asString() const;  // empty string when not a string
    const Array& asArray() const;          // empty array when not an array
    const Object& asObject() const;        // empty object when not an object

    Array& mutableArray();    // converts to array when needed
    Object& mutableObject();  // converts to object when needed

    // Object helpers. Lookup is linear; documents here are small.
    const Value* find(std::string_view key) const;
    Value& set(std::string key, Value v);
    void push(Value v);

private:
    std::variant<std::nullptr_t, bool, double, std::string, std::shared_ptr<Array>,
                 std::shared_ptr<Object>>
        data_{nullptr};
};

struct Limits {
    size_t maxBytes = 2 * 1024 * 1024;
    size_t maxDepth = 32;
    size_t maxStringBytes = 64 * 1024;
    size_t maxElements = 200000;  // total values in the document
};

struct ParseError {
    size_t offset = 0;
    std::string message;
};

// Returns std::nullopt and fills `error` (when given) on malformed input or
// when a limit is exceeded. Duplicate object keys keep the last value.
std::optional<Value> parse(std::string_view text, const Limits& limits = {},
                           ParseError* error = nullptr);

// Compact serialisation. Non-finite numbers are written as null.
std::string serialize(const Value& v);

}  // namespace lx::json
