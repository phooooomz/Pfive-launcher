// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Ranking for the launcher search box. Case-insensitive for ASCII; other
// characters must match byte for byte.
#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace lx {

// 0 means no match. Higher is better: exact > prefix > word start > substring
// > ordered subsequence of the query characters.
int matchScore(std::string_view query, std::string_view candidate);

// Indices into `candidates` that match, best first; ties keep input order.
// At most `limit` results.
std::vector<int> rankMatches(std::string_view query, const std::vector<std::string>& candidates,
                             size_t limit);

}  // namespace lx
