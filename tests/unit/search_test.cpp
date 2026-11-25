// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include "lx/search.hpp"

using namespace lx;

TEST(Search, OrdersByMatchQuality) {
    EXPECT_GT(matchScore("maps", "Maps"), matchScore("maps", "Maps Go"));
    EXPECT_GT(matchScore("ma", "Maps"), matchScore("ma", "Google Maps"));
    EXPECT_GT(matchScore("maps", "Google Maps"), matchScore("aps", "Google Maps"));
    EXPECT_GT(matchScore("aps", "Google Maps"), matchScore("gm", "Google Maps"));
    EXPECT_GT(matchScore("gm", "Google Maps"), 0);
    EXPECT_EQ(matchScore("xyz", "Google Maps"), 0);
    EXPECT_EQ(matchScore("", "Maps"), 0);
}

TEST(Search, CaseInsensitiveAscii) {
    EXPECT_EQ(matchScore("CAMERA", "camera"), matchScore("camera", "Camera"));
}

TEST(Search, RankKeepsInputOrderForTies) {
    std::vector<std::string> apps{"Clock", "Calendar", "Calculator", "Contacts", "Chrome"};
    auto r = rankMatches("ca", apps, 10);
    ASSERT_EQ(r.size(), 3u);  // Contacts matches as a subsequence
    // Both are prefix matches; the shorter label wins.
    EXPECT_EQ(apps[static_cast<size_t>(r[0])], "Calendar");
    EXPECT_EQ(apps[static_cast<size_t>(r[1])], "Calculator");
    EXPECT_EQ(apps[static_cast<size_t>(r[2])], "Contacts");
    EXPECT_EQ(rankMatches("c", apps, 2).size(), 2u);
}
