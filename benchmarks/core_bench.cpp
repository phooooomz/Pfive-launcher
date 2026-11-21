// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// Micro benchmarks for the hot paths of the native core. Prints median wall
// time per operation; no external benchmark library needed.
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include "lx/engine.hpp"
#include "lx/render.hpp"
#include "lx/search.hpp"

namespace {

template <typename F>
double medianMicros(int runs, F&& f) {
    std::vector<double> t;
    for (int i = 0; i < runs; ++i) {
        auto a = std::chrono::steady_clock::now();
        f();
        auto b = std::chrono::steady_clock::now();
        t.push_back(std::chrono::duration<double, std::micro>(b - a).count());
    }
    std::sort(t.begin(), t.end());
    return t[t.size() / 2];
}

}  // namespace

int main() {
    using namespace lx;
    Engine e;
    e.resetToDefaults({{"1", "2", "3", "4"}, "Welcome", false});
    e.setSetting("desktop.gridCols", "8");
    e.setSetting("desktop.gridRows", "8");
    for (int i = 0; i < 60; ++i)
        e.desktop().addShortcut(e.desktop().active(), "com.example.app" + std::to_string(i) + "/x.Main#0");
    for (int i = 0; i < 10; ++i) e.windows().open("notes", "Notes", e.desktop().active());

    std::string doc = e.exportDocument();
    std::printf("layout document: %zu bytes\n", doc.size());
    std::printf("export:      %8.1f us\n", medianMicros(200, [&] { (void)e.exportDocument(); }));
    std::printf("snapshot:    %8.1f us\n", medianMicros(200, [&] { (void)e.snapshot(); }));
    std::printf("import:      %8.1f us\n", medianMicros(200, [&] {
                    Engine x;
                    (void)x.importDocument(doc);
                }));
    uint32_t w = e.windows().all().front().id;
    std::printf("window move: %8.3f us\n", medianMicros(5000, [&] { e.windows().move(w, 1, 1); }));

    std::vector<std::string> labels;
    for (int i = 0; i < 400; ++i) labels.push_back("Application number " + std::to_string(i));
    std::printf("search 400:  %8.1f us\n", medianMicros(200, [&] { (void)rankMatches("app 3", labels, 30); }));

    std::vector<uint8_t> px(720 * 1600 * 4);
    render::Image img{px.data(), 720, 1600, 720 * 4};
    std::printf("wallpaper 720x1600: %8.1f ms\n",
                medianMicros(5, [&] { render::renderWallpaper(img, "skyline", 7); }) / 1000.0);
    std::vector<uint8_t> small(180 * 400 * 4);
    render::Image simg{small.data(), 180, 400, 180 * 4};
    render::renderWallpaper(simg, "skyline", 7);
    std::printf("blur 180x400 r6x3:  %8.1f ms\n",
                medianMicros(20, [&] { render::boxBlur(simg, 6, 3); }) / 1000.0);
    return 0;
}
