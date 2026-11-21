// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Randomised robustness tests. They run on every test pass with fixed seeds so
// failures reproduce; LX_FUZZ_ITERATIONS raises the iteration count.
#include <gtest/gtest.h>

#include <cstdlib>
#include <random>
#include <string>

#include "lx/engine.hpp"
#include "lx/json.hpp"
#include "lx/persistence.hpp"
#include "lx/validate.hpp"

using namespace lx;

namespace {

int iterations(int fallback) {
    const char* env = std::getenv("LX_FUZZ_ITERATIONS");
    return env ? std::max(1, std::atoi(env)) : fallback;
}

std::string mutate(std::string s, std::mt19937& rng) {
    std::uniform_int_distribution<int> op(0, 3);
    std::uniform_int_distribution<int> byte(0, 255);
    int edits = 1 + static_cast<int>(rng() % 8);
    for (int i = 0; i < edits && !s.empty(); ++i) {
        size_t pos = rng() % s.size();
        switch (op(rng)) {
            case 0: s[pos] = static_cast<char>(byte(rng)); break;
            case 1: s.erase(pos, 1 + rng() % 4); break;
            case 2: s.insert(pos, 1, "{}[]\",:0e-\\u"[rng() % 13]); break;
            default: s.insert(pos, s.substr(rng() % s.size(), rng() % 16)); break;
        }
    }
    return s;
}

}  // namespace

TEST(Fuzz, JsonParserSurvivesRandomBytes) {
    std::mt19937 rng(1234);
    for (int i = 0; i < iterations(20000); ++i) {
        std::string s(rng() % 64, '\0');
        for (auto& c : s) c = static_cast<char>(rng());
        auto v = json::parse(s);
        if (v) {
            // Anything accepted must serialise back to parseable JSON.
            ASSERT_TRUE(json::parse(json::serialize(*v)));
        }
    }
}

TEST(Fuzz, ImportSurvivesMutatedLayouts) {
    Engine seed;
    seed.resetToDefaults({{"A", "B", "C", "D"}, "Welcome", false});
    seed.desktop().addShortcut(seed.desktop().active(), "com.example.mail/com.example.mail.Main#0");
    seed.windows().open("files", "Files", seed.desktop().active());
    const std::string doc = seed.exportDocument();
    std::mt19937 rng(99);
    for (int i = 0; i < iterations(3000); ++i) {
        Engine e;
        std::string before = e.exportDocument();
        LoadReport r = e.importDocument(mutate(doc, rng));
        if (!r.ok) {
            ASSERT_EQ(e.exportDocument(), before);
            continue;
        }
        // Whatever survived must be internally consistent.
        ASSERT_FALSE(e.desktop().workspaces().empty());
        ASSERT_TRUE(e.desktop().workspace(e.desktop().active()));
        for (const auto& w : e.windows().all()) ASSERT_TRUE(e.desktop().workspace(w.workspaceId));
        for (const auto& s : e.desktop().shortcuts()) {
            ASSERT_TRUE(validate::isAppKey(s.appKey));
            ASSERT_GE(s.cell.col, 0);
            ASSERT_LT(s.cell.col, e.desktop().cols());
        }
        for (const auto& w : e.desktop().widgets()) {
            WidgetRect r = e.desktop().widgetRect(w);
            ASSERT_GE(r.x, 0.0f);
            ASSERT_LE(r.x + r.w, 1.0001f);
        }
        ASSERT_TRUE(json::parse(e.snapshot()));
    }
}

TEST(Fuzz, EnvelopeRejectsMutations) {
    const std::string wrapped = wrapEnvelope("{\"format\":\"lx-launcher-layout\",\"version\":1}");
    std::mt19937 rng(7);
    int accepted = 0;
    for (int i = 0; i < iterations(5000); ++i) {
        std::string m = mutate(wrapped, rng);
        if (m == wrapped) continue;
        if (unwrapEnvelope(m)) ++accepted;
    }
    // CRC32 lets about one in 4 billion through; any hit here is a bug.
    EXPECT_EQ(accepted, 0);
}

TEST(Fuzz, WindowOperationsKeepInvariants) {
    WindowManager wm;
    wm.setViewport({0, 80, 720, 1300}, {0, 0, 720, 1600});
    std::mt19937 rng(42);
    std::uniform_real_distribution<float> d(-3000, 3000);
    std::vector<uint32_t> ids;
    for (int i = 0; i < iterations(20000); ++i) {
        int op = static_cast<int>(rng() % 9);
        uint32_t id = ids.empty() ? 0 : ids[rng() % ids.size()];
        switch (op) {
            case 0: if (uint32_t n = wm.open("notes", "n", 1 + rng() % 3)) ids.push_back(n); break;
            case 1: wm.close(id); break;
            case 2: wm.move(id, d(rng), d(rng)); break;
            case 3: wm.resize(id, 1 + rng() % 15, d(rng), d(rng)); break;
            case 4: wm.setState(id, static_cast<WindowState>(rng() % 6)); break;
            case 5: wm.focus(id); break;
            case 6: wm.tile(1 + rng() % 3); break;
            case 7: wm.setViewport({0, 80, 300 + d(rng) / 10 + 300, 900 + d(rng) / 10}, {0, 0, 1000, 2000}); break;
            default: wm.moveToWorkspace(id, 1 + rng() % 3); break;
        }
        for (const auto& w : wm.all()) {
            ASSERT_GT(w.bounds.w, 0);
            ASSERT_GT(w.bounds.h, 0);
            ASSERT_TRUE(finite(w.bounds));
            if (w.state == WindowState::Floating) {
                const Rect& vp = wm.viewport();
                ASSERT_GE(w.bounds.y, vp.y - 0.5f);
                ASSERT_GE(w.bounds.x, vp.x - 0.5f);
                ASSERT_LE(w.bounds.right(), vp.right() + 0.5f);
            }
        }
    }
}
