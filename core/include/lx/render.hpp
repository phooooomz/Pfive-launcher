// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// CPU-side image work: the built-in wallpapers and the blur used for glass
// panels. Panels sample a pre-blurred copy of the wallpaper, so no blur runs
// per frame.
#pragma once

#include <cstdint>
#include <string_view>

namespace lx::render {

// RGBA_8888, the layout of an Android ARGB_8888 bitmap in memory.
struct Image {
    uint8_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    int stride = 0;  // bytes per row
};

bool valid(const Image& img);

// Styles: "skyline", "rain", "deep". Returns false for unknown styles or an
// invalid image. Output depends only on style, size and seed.
bool renderWallpaper(const Image& img, std::string_view style, uint32_t seed);

// Separable box blur; three passes approximate a Gaussian. radius is clamped
// to [1, 64].
bool boxBlur(const Image& img, int radius, int passes);

// Fills with one opaque colour (0xRRGGBB).
bool fill(const Image& img, uint32_t rgb);

}  // namespace lx::render
