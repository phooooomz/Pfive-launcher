// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Entry path rules, file name numbering and the text encodings archives use.
#include <array>
#include <string>
#include <vector>

#include "archive_internal.hpp"

namespace lx::archive {

namespace {

// Length of the well-formed UTF-8 sequence starting at s[i], 0 when there is none.
size_t utf8SequenceLength(std::string_view s, size_t i) {
    const auto c = static_cast<uint8_t>(s[i]);
    if (c < 0x80) return 1;
    size_t need = 0;
    uint8_t lo = 0x80;
    uint8_t hi = 0xBF;
    if (c >= 0xC2 && c <= 0xDF) {
        need = 1;
    } else if (c == 0xE0) {
        need = 2;
        lo = 0xA0;  // overlong below U+0800
    } else if ((c >= 0xE1 && c <= 0xEC) || c == 0xEE || c == 0xEF) {
        need = 2;
    } else if (c == 0xED) {
        need = 2;
        hi = 0x9F;  // U+D800..U+DFFF are surrogates
    } else if (c == 0xF0) {
        need = 3;
        lo = 0x90;  // overlong below U+10000
    } else if (c >= 0xF1 && c <= 0xF3) {
        need = 3;
    } else if (c == 0xF4) {
        need = 3;
        hi = 0x8F;  // above U+10FFFF
    } else {
        return 0;
    }
    if (s.size() - i <= need) return 0;
    const auto first = static_cast<uint8_t>(s[i + 1]);
    if (first < lo || first > hi) return 0;
    for (size_t k = 2; k <= need; ++k) {
        const auto b = static_cast<uint8_t>(s[i + k]);
        if (b < 0x80 || b > 0xBF) return 0;
    }
    return need + 1;
}

uint32_t decodeSequence(std::string_view s, size_t i, size_t len) {
    const auto c = static_cast<uint8_t>(s[i]);
    if (len == 1) return c;
    uint32_t cp = c & (len == 2 ? 0x1Fu : len == 3 ? 0x0Fu : 0x07u);
    for (size_t k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<uint8_t>(s[i + k]) & 0x3Fu);
    return cp;
}

void appendUtf8(std::string& out, uint32_t cp) {
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

// Code points for CP437 bytes 0x80..0xFF; the lower half is ASCII.
constexpr std::array<uint16_t, 128> kCp437High = {
    0x00C7, 0x00FC, 0x00E9, 0x00E2, 0x00E4, 0x00E0, 0x00E5, 0x00E7, 0x00EA, 0x00EB, 0x00E8, 0x00EF, 0x00EE,
    0x00EC, 0x00C4, 0x00C5, 0x00C9, 0x00E6, 0x00C6, 0x00F4, 0x00F6, 0x00F2, 0x00FB, 0x00F9, 0x00FF, 0x00D6,
    0x00DC, 0x00A2, 0x00A3, 0x00A5, 0x20A7, 0x0192, 0x00E1, 0x00ED, 0x00F3, 0x00FA, 0x00F1, 0x00D1, 0x00AA,
    0x00BA, 0x00BF, 0x2310, 0x00AC, 0x00BD, 0x00BC, 0x00A1, 0x00AB, 0x00BB, 0x2591, 0x2592, 0x2593, 0x2502,
    0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255D, 0x255C, 0x255B, 0x2510, 0x2514,
    0x2534, 0x252C, 0x251C, 0x2500, 0x253C, 0x255E, 0x255F, 0x255A, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550,
    0x256C, 0x2567, 0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256B, 0x256A, 0x2518, 0x250C,
    0x2588, 0x2584, 0x258C, 0x2590, 0x2580, 0x03B1, 0x00DF, 0x0393, 0x03C0, 0x03A3, 0x03C3, 0x00B5, 0x03C4,
    0x03A6, 0x0398, 0x03A9, 0x03B4, 0x221E, 0x03C6, 0x03B5, 0x2229, 0x2261, 0x00B1, 0x2265, 0x2264, 0x2320,
    0x2321, 0x00F7, 0x2248, 0x00B0, 0x2219, 0x00B7, 0x221A, 0x207F, 0x00B2, 0x25A0, 0x00A0,
};

// ASCII controls, and the UTF-8 forms of the C1 controls (C2 80..C2 9F), of
// U+2028..U+202E (E2 80 A8..AE: line and paragraph separators, bidi
// embeddings and overrides) and of the bidi isolates U+2066..U+2069 (E2 81 A6..A9).
bool hasControl(std::string_view s) {
    const auto at = [&](size_t i) { return static_cast<uint8_t>(s[i]); };
    for (size_t i = 0; i < s.size(); ++i) {
        const uint8_t c = at(i);
        if (c < 0x20 || c == 0x7F) return true;
        if (c == 0xC2 && i + 1 < s.size() && at(i + 1) >= 0x80 && at(i + 1) <= 0x9F) return true;
        if (c == 0xE2 && i + 2 < s.size()) {
            if (at(i + 1) == 0x80 && at(i + 2) >= 0xA8 && at(i + 2) <= 0xAE) return true;
            if (at(i + 1) == 0x81 && at(i + 2) >= 0xA6 && at(i + 2) <= 0xA9) return true;
        }
    }
    return false;
}

// "C:" or "c:foo": a Windows drive, absolute or drive-relative.
bool isDrive(std::string_view first) {
    if (first.size() < 2 || first[1] != ':') return false;
    const char c = first[0];
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

bool fatReserved(std::string_view component) {
    for (char c : component) {
        if (c == '"' || c == '*' || c == ':' || c == '<' || c == '>' || c == '?' || c == '|') return true;
    }
    return component.back() == '.' || component.back() == ' ';
}

// Cuts s to at most maxBytes without splitting a UTF-8 sequence.
std::string_view cutUtf8(std::string_view s, size_t maxBytes) {
    if (s.size() <= maxBytes) return s;
    size_t end = maxBytes;
    while (end > 0 && (static_cast<uint8_t>(s[end]) & 0xC0) == 0x80) --end;
    return s.substr(0, end);
}

}  // namespace

bool isValidUtf8(std::string_view s) {
    for (size_t i = 0; i < s.size();) {
        const size_t len = utf8SequenceLength(s, i);
        if (len == 0) return false;
        i += len;
    }
    return true;
}

std::string cp437ToUtf8(std::string_view s) {
    std::string out;
    out.reserve(s.size() * 2);
    for (char ch : s) {
        const auto c = static_cast<uint8_t>(ch);
        appendUtf8(out, c < 0x80 ? c : kCp437High[c - 0x80]);
    }
    return out;
}

std::u16string utf8ToUtf16(std::string_view s) {
    std::u16string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const size_t len = utf8SequenceLength(s, i);
        if (len == 0) {
            out.push_back(u'�');
            ++i;
            continue;
        }
        const uint32_t cp = decodeSequence(s, i, len);
        if (cp >= 0x10000) {
            out.push_back(static_cast<char16_t>(0xD800 + ((cp - 0x10000) >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + ((cp - 0x10000) & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    return out;
}

std::string utf16ToUtf8(std::u16string_view s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); ++i) {
        uint32_t c = s[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.size() && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (static_cast<uint32_t>(s[i + 1]) - 0xDC00);
            ++i;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        appendUtf8(out, c);
    }
    return out;
}

PathVerdict checkPath(std::string_view raw, bool fat, std::string* normalized) {
    if (normalized) normalized->clear();
    if (raw.empty()) return PathVerdict::Empty;
    if (hasControl(raw)) return PathVerdict::Control;
    if (!isValidUtf8(raw)) return PathVerdict::BadUtf8;
    std::string p(raw);
    for (char& c : p)
        if (c == '\\') c = '/';
    if (p.front() == '/') return PathVerdict::Absolute;

    std::vector<std::string_view> parts;
    std::string_view rest(p);
    while (!rest.empty()) {
        const size_t slash = rest.find('/');
        const std::string_view part = rest.substr(0, slash);
        if (!part.empty() && part != ".") parts.push_back(part);
        if (slash == std::string_view::npos) break;
        rest.remove_prefix(slash + 1);
    }
    if (parts.empty()) return PathVerdict::Empty;
    if (isDrive(parts.front())) return PathVerdict::Drive;
    for (std::string_view part : parts)
        if (part == "..") return PathVerdict::DotDot;
    if (fat) {
        for (std::string_view part : parts)
            if (fatReserved(part)) return PathVerdict::Reserved;
    }
    std::string out;
    out.reserve(p.size());
    for (std::string_view part : parts) {
        if (part.size() > kMaxComponentBytes) return PathVerdict::ComponentTooLong;
        if (!out.empty()) out.push_back('/');
        out.append(part);
    }
    if (out.size() > kMaxPathBytes) return PathVerdict::TooLong;
    if (normalized) *normalized = std::move(out);
    return PathVerdict::Ok;
}

PathVerdict checkLinkTarget(std::string_view entryPath, std::string_view target, bool hardLink,
                            std::string* resolved) {
    if (resolved) resolved->clear();
    if (hardLink) return checkPath(target, false, resolved);
    if (target.empty()) return PathVerdict::Empty;
    if (hasControl(target)) return PathVerdict::Control;
    if (!isValidUtf8(target)) return PathVerdict::BadUtf8;
    std::string t(target);
    for (char& c : t)
        if (c == '\\') c = '/';
    if (t.front() == '/') return PathVerdict::Absolute;

    // Start in the link's folder, whose components are real folders: the
    // reader refuses entries below a symlink.
    std::vector<std::string_view> parts;
    const size_t lastSlash = entryPath.rfind('/');
    std::string_view folder = lastSlash == std::string_view::npos ? std::string_view() : entryPath.substr(0, lastSlash);
    while (!folder.empty()) {
        const size_t slash = folder.find('/');
        const std::string_view part = folder.substr(0, slash);
        if (!part.empty()) parts.push_back(part);
        if (slash == std::string_view::npos) break;
        folder.remove_prefix(slash + 1);
    }

    bool first = true;
    bool descended = false;
    std::string_view rest(t);
    while (!rest.empty()) {
        const size_t slash = rest.find('/');
        const std::string_view part = rest.substr(0, slash);
        rest = slash == std::string_view::npos ? std::string_view() : rest.substr(slash + 1);
        if (part.empty() || part == ".") continue;
        if (first && isDrive(part)) return PathVerdict::Drive;
        first = false;
        if (part == "..") {
            if (descended || parts.empty()) return PathVerdict::DotDot;
            parts.pop_back();
            continue;
        }
        if (part.size() > kMaxComponentBytes) return PathVerdict::ComponentTooLong;
        descended = true;
        parts.push_back(part);
    }
    std::string out;
    for (std::string_view part : parts) {
        if (!out.empty()) out.push_back('/');
        out.append(part);
    }
    if (out.size() > kMaxPathBytes) return PathVerdict::TooLong;
    if (resolved) *resolved = std::move(out);
    return PathVerdict::Ok;
}

PathVerdict checkName(std::string_view name, bool fat) {
    if (name.empty()) return PathVerdict::Empty;
    if (hasControl(name)) return PathVerdict::Control;
    if (!isValidUtf8(name)) return PathVerdict::BadUtf8;
    if (name == "." || name == "..") return PathVerdict::Reserved;
    if (name.find_first_of("/\\") != std::string_view::npos) return PathVerdict::Reserved;
    if (isDrive(name)) return PathVerdict::Drive;
    if (fat && fatReserved(name)) return PathVerdict::Reserved;
    if (name.size() > kMaxComponentBytes) return PathVerdict::ComponentTooLong;
    return PathVerdict::Ok;
}

std::string numberedName(std::string_view name, int n) {
    if (n < 2) return std::string(name);
    std::string lower(name);
    for (char& c : lower)
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    std::string_view ext;
    for (std::string_view dbl : {".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst"}) {
        if (lower.size() > dbl.size() && std::string_view(lower).substr(lower.size() - dbl.size()) == dbl) {
            ext = name.substr(name.size() - dbl.size());
            break;
        }
    }
    if (ext.empty()) {
        // A dot at the start marks a hidden file, not an extension. Long tails
        // or tails with spaces ("Mr. Smith notes") are part of the name.
        constexpr size_t kMaxExtension = 16;
        const size_t dot = name.rfind('.');
        if (dot != std::string_view::npos && dot > 0 && dot + 1 < name.size() &&
            name.size() - dot <= kMaxExtension && name.find(' ', dot) == std::string_view::npos) {
            ext = name.substr(dot);
        }
    }
    const std::string suffix = " (" + std::to_string(n) + ")";
    std::string_view stem = name.substr(0, name.size() - ext.size());
    const size_t room = kMaxComponentBytes > suffix.size() + ext.size() ? kMaxComponentBytes - suffix.size() - ext.size() : 0;
    stem = cutUtf8(stem, room);
    std::string out;
    out.reserve(stem.size() + suffix.size() + ext.size());
    out.append(stem).append(suffix).append(ext);
    return out;
}

}  // namespace lx::archive
