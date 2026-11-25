// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include <gtest/gtest.h>

#include <vector>

#include "lx/persistence.hpp"
#include "lx/render.hpp"

using namespace lx::render;

namespace {
struct Buffer {
    Buffer(int w, int h) : data(static_cast<size_t>(w) * h * 4), img{data.data(), w, h, w * 4} {}
    std::vector<uint8_t> data;
    Image img;
    std::string_view bytes() const {
        return {reinterpret_cast<const char*>(data.data()), data.size()};
    }
};
}  // namespace

TEST(Render, RejectsInvalidImages) {
    Image none;
    EXPECT_FALSE(renderWallpaper(none, "skyline", 1));
    Buffer b(4, 4);
    Image badStride = b.img;
    badStride.stride = 8;
    EXPECT_FALSE(renderWallpaper(badStride, "skyline", 1));
    EXPECT_FALSE(renderWallpaper(b.img, "unknown", 1));
}

TEST(Render, WallpaperIsDeterministicAndOpaque) {
    Buffer a(120, 240), b(120, 240), c(120, 240);
    ASSERT_TRUE(renderWallpaper(a.img, "skyline", 7));
    ASSERT_TRUE(renderWallpaper(b.img, "skyline", 7));
    ASSERT_TRUE(renderWallpaper(c.img, "skyline", 8));
    EXPECT_EQ(lx::crc32(a.bytes()), lx::crc32(b.bytes()));
    EXPECT_NE(lx::crc32(a.bytes()), lx::crc32(c.bytes()));
    for (size_t i = 3; i < a.data.size(); i += 4) ASSERT_EQ(a.data[i], 255);
}

TEST(Render, EveryStyleRenders) {
    for (const char* style : {"skyline", "rain", "deep"}) {
        Buffer b(64, 128);
        EXPECT_TRUE(renderWallpaper(b.img, style, 3)) << style;
    }
}

TEST(Render, BlurSmoothsAndKeepsFlatImagesFlat) {
    Buffer flat(32, 32);
    fill(flat.img, 0x336699);
    ASSERT_TRUE(boxBlur(flat.img, 4, 3));
    EXPECT_EQ(flat.data[0], 0x33);
    EXPECT_EQ(flat.data[1], 0x66);
    EXPECT_EQ(flat.data[2], 0x99);

    Buffer dot(33, 33);
    fill(dot.img, 0x000000);
    size_t centre = (16 * 33 + 16) * 4;
    dot.data[centre] = 255;
    ASSERT_TRUE(boxBlur(dot.img, 2, 1));
    EXPECT_LT(dot.data[centre], 255);
    EXPECT_GT(dot.data[centre + 4], 0);  // spread to the neighbour
}

TEST(Render, BlurHandlesRadiusLargerThanImage) {
    Buffer b(3, 2);
    fill(b.img, 0xFFFFFF);
    EXPECT_TRUE(boxBlur(b.img, 64, 3));
    EXPECT_EQ(b.data[0], 255);
}
