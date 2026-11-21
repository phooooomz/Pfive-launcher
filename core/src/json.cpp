// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/json.hpp"

#include <array>
#include <charconv>
#include <cmath>
#include <cstring>
#include <unordered_map>

namespace lx::json {

namespace {
const std::string kEmptyString;
const Array kEmptyArray;
const Object kEmptyObject;
}  // namespace

Value::Type Value::type() const {
    switch (data_.index()) {
        case 0: return Type::Null;
        case 1: return Type::Bool;
        case 2: return Type::Number;
        case 3: return Type::String;
        case 4: return Type::Array;
        default: return Type::Object;
    }
}

bool Value::asBool(bool fallback) const {
    if (auto* b = std::get_if<bool>(&data_)) return *b;
    return fallback;
}

double Value::asNumber(double fallback) const {
    if (auto* d = std::get_if<double>(&data_)) return *d;
    return fallback;
}

const std::string& Value::asString() const {
    if (auto* s = std::get_if<std::string>(&data_)) return *s;
    return kEmptyString;
}

const Array& Value::asArray() const {
    if (auto* a = std::get_if<std::shared_ptr<Array>>(&data_)) return **a;
    return kEmptyArray;
}

const Object& Value::asObject() const {
    if (auto* o = std::get_if<std::shared_ptr<Object>>(&data_)) return **o;
    return kEmptyObject;
}

// Copies of a Value share their container, so a writer detaches first.
Array& Value::mutableArray() {
    auto* a = std::get_if<std::shared_ptr<Array>>(&data_);
    if (!a) {
        data_ = std::make_shared<Array>();
        a = std::get_if<std::shared_ptr<Array>>(&data_);
    } else if (a->use_count() > 1) {
        *a = std::make_shared<Array>(**a);
    }
    return **a;
}

Object& Value::mutableObject() {
    auto* o = std::get_if<std::shared_ptr<Object>>(&data_);
    if (!o) {
        data_ = std::make_shared<Object>();
        o = std::get_if<std::shared_ptr<Object>>(&data_);
    } else if (o->use_count() > 1) {
        *o = std::make_shared<Object>(**o);
    }
    return **o;
}

const Value* Value::find(std::string_view key) const {
    for (const auto& [k, v] : asObject()) {
        if (k == key) return &v;
    }
    return nullptr;
}

Value& Value::set(std::string key, Value v) {
    auto& obj = mutableObject();
    for (auto& [k, existing] : obj) {
        if (k == key) {
            existing = std::move(v);
            return existing;
        }
    }
    obj.emplace_back(std::move(key), std::move(v));
    return obj.back().second;
}

void Value::push(Value v) { mutableArray().push_back(std::move(v)); }

namespace {

class Parser {
public:
    /** Members an object holds before repeated keys are found through an index instead of a scan. */
    static constexpr size_t kIndexFrom = 32;

    Parser(std::string_view text, const Limits& limits) : s_(text), limits_(limits) {}

    std::optional<Value> run(ParseError* err) {
        if (s_.size() > limits_.maxBytes) return fail(err, 0, "document too large");
        skipWs();
        auto v = parseValue(0);
        if (!v) return fail(err, errorAt_, error_);
        skipWs();
        if (pos_ != s_.size()) return fail(err, pos_, "trailing characters");
        return v;
    }

private:
    std::string_view s_;
    Limits limits_;
    size_t pos_ = 0;
    size_t elements_ = 0;
    size_t errorAt_ = 0;
    const char* error_ = "";

    static std::optional<Value> fail(ParseError* err, size_t at, const char* msg) {
        if (err) {
            err->offset = at;
            err->message = msg;
        }
        return std::nullopt;
    }

    std::optional<Value> error(const char* msg) {
        errorAt_ = pos_;
        error_ = msg;
        return std::nullopt;
    }

    void skipWs() {
        while (pos_ < s_.size()) {
            char c = s_[pos_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') ++pos_;
            else break;
        }
    }

    bool consumeLiteral(std::string_view lit) {
        if (s_.substr(pos_, lit.size()) == lit) {
            pos_ += lit.size();
            return true;
        }
        return false;
    }

    std::optional<Value> parseValue(size_t depth) {
        if (++elements_ > limits_.maxElements) return error("too many elements");
        if (pos_ >= s_.size()) return error("unexpected end");
        char c = s_[pos_];
        switch (c) {
            case '{': return parseObject(depth + 1);
            case '[': return parseArray(depth + 1);
            case '"': {
                std::string str;
                if (!parseString(str)) return std::nullopt;
                return Value(std::move(str));
            }
            case 't':
                if (consumeLiteral("true")) return Value(true);
                return error("invalid literal");
            case 'f':
                if (consumeLiteral("false")) return Value(false);
                return error("invalid literal");
            case 'n':
                if (consumeLiteral("null")) return Value(nullptr);
                return error("invalid literal");
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                return error("unexpected character");
        }
    }

    std::optional<Value> parseObject(size_t depth) {
        if (depth > limits_.maxDepth) return error("nesting too deep");
        ++pos_;  // '{'
        Value obj{Object{}};
        Object& members = obj.mutableObject();
        // A repeated key keeps its first place and takes the last value, as Value::set does. Small objects find it
        // by scanning; a large one gets an index, because scanning for every key of an object with 100,000 keys
        // took the better part of a minute on the main thread when such a file was imported.
        std::unordered_map<std::string, size_t> index;
        skipWs();
        if (pos_ < s_.size() && s_[pos_] == '}') {
            ++pos_;
            return obj;
        }
        while (true) {
            skipWs();
            if (pos_ >= s_.size() || s_[pos_] != '"') return error("expected key");
            std::string key;
            if (!parseString(key)) return std::nullopt;
            skipWs();
            if (pos_ >= s_.size() || s_[pos_] != ':') return error("expected ':'");
            ++pos_;
            skipWs();
            auto v = parseValue(depth);
            if (!v) return std::nullopt;
            if (members.size() == kIndexFrom) {
                for (size_t i = 0; i < members.size(); ++i) index.emplace(members[i].first, i);
            }
            size_t at = members.size();
            if (members.size() >= kIndexFrom) {
                auto found = index.find(key);
                if (found != index.end()) at = found->second;
            } else {
                for (size_t i = 0; i < members.size(); ++i) {
                    if (members[i].first == key) {
                        at = i;
                        break;
                    }
                }
            }
            if (at < members.size()) {
                members[at].second = std::move(*v);
            } else {
                if (members.size() >= kIndexFrom) index.emplace(key, members.size());
                members.emplace_back(std::move(key), std::move(*v));
            }
            skipWs();
            if (pos_ >= s_.size()) return error("unterminated object");
            if (s_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (s_[pos_] == '}') {
                ++pos_;
                return obj;
            }
            return error("expected ',' or '}'");
        }
    }

    std::optional<Value> parseArray(size_t depth) {
        if (depth > limits_.maxDepth) return error("nesting too deep");
        ++pos_;  // '['
        Value arr{Array{}};
        skipWs();
        if (pos_ < s_.size() && s_[pos_] == ']') {
            ++pos_;
            return arr;
        }
        while (true) {
            skipWs();
            auto v = parseValue(depth);
            if (!v) return std::nullopt;
            arr.push(std::move(*v));
            skipWs();
            if (pos_ >= s_.size()) return error("unterminated array");
            if (s_[pos_] == ',') {
                ++pos_;
                continue;
            }
            if (s_[pos_] == ']') {
                ++pos_;
                return arr;
            }
            return error("expected ',' or ']'");
        }
    }

    static void appendUtf8(std::string& out, uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    bool readHex4(uint32_t& out) {
        if (pos_ + 4 > s_.size()) return false;
        out = 0;
        for (int i = 0; i < 4; ++i) {
            char c = s_[pos_++];
            out <<= 4;
            if (c >= '0' && c <= '9') out |= static_cast<uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') out |= static_cast<uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') out |= static_cast<uint32_t>(c - 'A' + 10);
            else return false;
        }
        return true;
    }

    // Validates one UTF-8 sequence starting at pos_ and copies it to out.
    bool copyUtf8(std::string& out) {
        auto b0 = static_cast<unsigned char>(s_[pos_]);
        size_t len;
        uint32_t cp;
        if (b0 < 0x80) {
            out.push_back(static_cast<char>(b0));
            ++pos_;
            return true;
        } else if ((b0 & 0xE0) == 0xC0) {
            len = 2;
            cp = b0 & 0x1F;
        } else if ((b0 & 0xF0) == 0xE0) {
            len = 3;
            cp = b0 & 0x0F;
        } else if ((b0 & 0xF8) == 0xF0) {
            len = 4;
            cp = b0 & 0x07;
        } else {
            return false;
        }
        if (pos_ + len > s_.size()) return false;
        for (size_t i = 1; i < len; ++i) {
            auto b = static_cast<unsigned char>(s_[pos_ + i]);
            if ((b & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (b & 0x3F);
        }
        static constexpr std::array<uint32_t, 5> kMin{0, 0, 0x80, 0x800, 0x10000};
        if (cp < kMin[len] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        out.append(s_.data() + pos_, len);
        pos_ += len;
        return true;
    }

    bool parseString(std::string& out) {
        ++pos_;  // opening quote
        while (true) {
            if (pos_ >= s_.size()) {
                error("unterminated string");
                return false;
            }
            if (out.size() > limits_.maxStringBytes) {
                error("string too long");
                return false;
            }
            char c = s_[pos_];
            if (c == '"') {
                ++pos_;
                return true;
            }
            if (static_cast<unsigned char>(c) < 0x20) {
                error("control character in string");
                return false;
            }
            if (c != '\\') {
                if (!copyUtf8(out)) {
                    error("invalid UTF-8");
                    return false;
                }
                continue;
            }
            ++pos_;
            if (pos_ >= s_.size()) {
                error("unterminated escape");
                return false;
            }
            char e = s_[pos_++];
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'u': {
                    uint32_t cp;
                    if (!readHex4(cp)) {
                        error("invalid \\u escape");
                        return false;
                    }
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        uint32_t lo;
                        if (pos_ + 2 > s_.size() || s_[pos_] != '\\' || s_[pos_ + 1] != 'u') {
                            error("unpaired surrogate");
                            return false;
                        }
                        pos_ += 2;
                        if (!readHex4(lo) || lo < 0xDC00 || lo > 0xDFFF) {
                            error("unpaired surrogate");
                            return false;
                        }
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                        error("unpaired surrogate");
                        return false;
                    }
                    appendUtf8(out, cp);
                    break;
                }
                default:
                    error("invalid escape");
                    return false;
            }
        }
    }

    // Locale independent number parsing; strtod would follow LC_NUMERIC.
    std::optional<Value> parseNumber() {
        size_t start = pos_;
        bool neg = false;
        if (s_[pos_] == '-') {
            neg = true;
            ++pos_;
        }
        if (pos_ >= s_.size()) return error("invalid number");
        long double mantissa = 0;
        int digits = 0;
        if (s_[pos_] == '0') {
            ++pos_;
            digits = 1;
        } else if (s_[pos_] >= '1' && s_[pos_] <= '9') {
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') {
                mantissa = mantissa * 10 + (s_[pos_] - '0');
                ++pos_;
                ++digits;
            }
        } else {
            return error("invalid number");
        }
        int exp10 = 0;
        if (pos_ < s_.size() && s_[pos_] == '.') {
            ++pos_;
            int frac = 0;
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') {
                if (frac < 30) {
                    mantissa = mantissa * 10 + (s_[pos_] - '0');
                    --exp10;
                }
                ++pos_;
                ++frac;
            }
            if (frac == 0) return error("invalid number");
        }
        if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
            ++pos_;
            bool eneg = false;
            if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-')) {
                eneg = s_[pos_] == '-';
                ++pos_;
            }
            int e = 0;
            int edigits = 0;
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') {
                if (e < 10000) e = e * 10 + (s_[pos_] - '0');
                ++pos_;
                ++edigits;
            }
            if (edigits == 0) return error("invalid number");
            exp10 += eneg ? -e : e;
        }
        if (pos_ - start > 64 || digits > 400) return error("number too long");
        long double value = mantissa * std::pow(10.0L, static_cast<long double>(exp10));
        double d = static_cast<double>(neg ? -value : value);
        if (!std::isfinite(d)) return error("number out of range");
        return Value(d);
    }
};

void writeString(std::string& out, const std::string& s) {
    out.push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (c < 0x20) {
                    static const char* hex = "0123456789abcdef";
                    out += "\\u00";
                    out.push_back(hex[c >> 4]);
                    out.push_back(hex[c & 0xF]);
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

void writeNumber(std::string& out, double d) {
    if (!std::isfinite(d)) {
        out += "null";
        return;
    }
    if (d == std::floor(d) && std::fabs(d) < 9007199254740992.0) {
        char buf[32];
        auto res = std::to_chars(buf, buf + sizeof buf, static_cast<long long>(d));
        out.append(buf, res.ptr);
        return;
    }
    char buf[64];
    // Positions and sizes are floats widened to double: 0.94f would print as 0.9399999976158142. The
    // shortest text that reads back as the same float is written instead, and reading it gives that float again.
    const float f = static_cast<float>(d);
    if (std::isfinite(f) && static_cast<double>(f) == d) {
        auto res = std::to_chars(buf, buf + sizeof buf, f);
        out.append(buf, res.ptr);
        return;
    }
    auto res = std::to_chars(buf, buf + sizeof buf, d);
    out.append(buf, res.ptr);
}

void write(std::string& out, const Value& v) {
    switch (v.type()) {
        case Value::Type::Null: out += "null"; break;
        case Value::Type::Bool: out += v.asBool() ? "true" : "false"; break;
        case Value::Type::Number: writeNumber(out, v.asNumber()); break;
        case Value::Type::String: writeString(out, v.asString()); break;
        case Value::Type::Array: {
            out.push_back('[');
            bool first = true;
            for (const auto& item : v.asArray()) {
                if (!first) out.push_back(',');
                first = false;
                write(out, item);
            }
            out.push_back(']');
            break;
        }
        case Value::Type::Object: {
            out.push_back('{');
            bool first = true;
            for (const auto& [k, item] : v.asObject()) {
                if (!first) out.push_back(',');
                first = false;
                writeString(out, k);
                out.push_back(':');
                write(out, item);
            }
            out.push_back('}');
            break;
        }
    }
}

}  // namespace

std::optional<Value> parse(std::string_view text, const Limits& limits, ParseError* error) {
    Parser p(text, limits);
    return p.run(error);
}

std::string serialize(const Value& v) {
    std::string out;
    write(out, v);
    return out;
}

}  // namespace lx::json
