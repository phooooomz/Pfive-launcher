// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/search.hpp"

#include <algorithm>

namespace lx {

namespace {

char lower(char c) { return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c; }

std::string fold(std::string_view s) {
    std::string out(s.size(), '\0');
    std::transform(s.begin(), s.end(), out.begin(), lower);
    return out;
}

bool isWordStart(const std::string& s, size_t i) {
    if (i == 0) return true;
    char p = s[i - 1];
    return p == ' ' || p == '-' || p == '_' || p == '.' || p == '(' || p == '/';
}

}  // namespace

int matchScore(std::string_view query, std::string_view candidate) {
    if (query.empty() || candidate.empty() || query.size() > 256 || candidate.size() > 1024) return 0;
    std::string q = fold(query);
    std::string c = fold(candidate);
    // Shorter candidates rank above longer ones with the same kind of match.
    int lengthBonus = std::max(0, 64 - static_cast<int>(c.size()));
    if (c == q) return 10000;
    if (c.compare(0, q.size(), q) == 0) return 8000 + lengthBonus;
    size_t pos = c.find(q);
    while (pos != std::string::npos) {
        if (isWordStart(c, pos)) return 6000 + lengthBonus;
        pos = c.find(q, pos + 1);
    }
    if (c.find(q) != std::string::npos) return 4000 + lengthBonus;
    // Ordered subsequence, rewarding matches at word starts.
    size_t ci = 0;
    int bonus = 0;
    for (char qc : q) {
        if (qc == ' ') continue;
        while (ci < c.size() && c[ci] != qc) ++ci;
        if (ci == c.size()) return 0;
        if (isWordStart(c, ci)) bonus += 40;
        ++ci;
    }
    return 1000 + std::min(bonus, 900) + lengthBonus / 4;
}

std::vector<int> rankMatches(std::string_view query, const std::vector<std::string>& candidates,
                             size_t limit) {
    std::vector<std::pair<int, int>> scored;
    for (size_t i = 0; i < candidates.size() && i < 100000; ++i) {
        int s = matchScore(query, candidates[i]);
        if (s > 0) scored.emplace_back(s, static_cast<int>(i));
    }
    std::stable_sort(scored.begin(), scored.end(),
                     [](const auto& a, const auto& b) { return a.first > b.first; });
    std::vector<int> out;
    for (size_t i = 0; i < scored.size() && i < limit; ++i) out.push_back(scored[i].second);
    return out;
}

}  // namespace lx
