// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#include "lx/render.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace lx::render {

namespace {

struct Rgb {
    float r, g, b;
};

Rgb lerp(Rgb a, Rgb b, float t) {
    return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t};
}

// Palette tokens from the design system.
constexpr Rgb kBackground{5, 8, 22};      // #050816
constexpr Rgb kDeepBlue{7, 20, 38};       // #071426
constexpr Rgb kSurface{11, 17, 32};       // #0B1120
constexpr Rgb kCyan{0, 217, 255};         // #00D9FF
constexpr Rgb kElectric{35, 139, 255};    // #238BFF
constexpr Rgb kMagenta{255, 47, 207};     // #FF2FCF
constexpr Rgb kPurple{146, 86, 255};      // #9256FF
constexpr Rgb kAmber{255, 186, 110};

class Rng {
public:
    explicit Rng(uint32_t seed) : s_(seed ? seed : 0x9E3779B9u) {}
    uint32_t next() {
        s_ ^= s_ << 13;
        s_ ^= s_ >> 17;
        s_ ^= s_ << 5;
        return s_;
    }
    float unit() { return static_cast<float>(next() >> 8) / 16777216.0f; }
    float range(float lo, float hi) { return lo + (hi - lo) * unit(); }

private:
    uint32_t s_;
};

class Canvas {
public:
    explicit Canvas(const Image& img) : img_(img) {}

    void set(int x, int y, Rgb c) {
        uint8_t* p = at(x, y);
        p[0] = clamp8(c.r);
        p[1] = clamp8(c.g);
        p[2] = clamp8(c.b);
        p[3] = 255;
    }

    void blend(int x, int y, Rgb c, float alpha) {
        if (x < 0 || y < 0 || x >= img_.width || y >= img_.height || alpha <= 0) return;
        uint8_t* p = at(x, y);
        alpha = std::min(alpha, 1.0f);
        p[0] = clamp8(p[0] + (c.r - p[0]) * alpha);
        p[1] = clamp8(p[1] + (c.g - p[1]) * alpha);
        p[2] = clamp8(p[2] + (c.b - p[2]) * alpha);
    }

    void rect(float x0, float y0, float x1, float y1, Rgb c, float alpha) {
        int ix0 = std::max(0, static_cast<int>(std::floor(x0)));
        int iy0 = std::max(0, static_cast<int>(std::floor(y0)));
        int ix1 = std::min(img_.width, static_cast<int>(std::ceil(x1)));
        int iy1 = std::min(img_.height, static_cast<int>(std::ceil(y1)));
        for (int y = iy0; y < iy1; ++y)
            for (int x = ix0; x < ix1; ++x) blend(x, y, c, alpha);
    }

    Rgb get(int x, int y) const {
        const uint8_t* p = img_.pixels + static_cast<size_t>(y) * img_.stride + static_cast<size_t>(x) * 4;
        return {static_cast<float>(p[0]), static_cast<float>(p[1]), static_cast<float>(p[2])};
    }

    int w() const { return img_.width; }
    int h() const { return img_.height; }

private:
    uint8_t* at(int x, int y) {
        return img_.pixels + static_cast<size_t>(y) * img_.stride + static_cast<size_t>(x) * 4;
    }
    static uint8_t clamp8(float v) {
        return static_cast<uint8_t>(std::clamp(v, 0.0f, 255.0f) + 0.5f);
    }
    Image img_;
};

void sky(Canvas& c, float hazeStrength) {
    const int w = c.w(), h = c.h();
    for (int y = 0; y < h; ++y) {
        float t = static_cast<float>(y) / static_cast<float>(h);
        Rgb col;
        if (t < 0.5f) col = lerp(kBackground, kDeepBlue, t / 0.5f);
        else if (t < 0.8f) col = lerp(kDeepBlue, lerp(kDeepBlue, kMagenta, 0.22f * hazeStrength), (t - 0.5f) / 0.3f);
        else col = lerp(lerp(kDeepBlue, kMagenta, 0.22f * hazeStrength), kBackground, (t - 0.8f) / 0.2f);
        for (int x = 0; x < w; ++x) c.set(x, y, col);
    }
}

void stars(Canvas& c, Rng& rng) {
    const int count = c.w() * c.h() / 2500;
    for (int i = 0; i < count; ++i) {
        int x = static_cast<int>(rng.unit() * static_cast<float>(c.w()));
        int y = static_cast<int>(rng.unit() * static_cast<float>(c.h()) * 0.45f);
        c.blend(x, y, lerp(kCyan, Rgb{255, 255, 255}, 0.6f), rng.range(0.15f, 0.55f));
    }
}

struct Layer {
    Rgb body;
    float minH, maxH;   // fraction of image height
    float minW, maxW;   // fraction of image width
    float litChance;
    float litAlpha;
    float signChance;
};

void glowRect(Canvas& c, float x0, float y0, float x1, float y1, Rgb col, float unit) {
    c.rect(x0 - 3 * unit, y0 - 3 * unit, x1 + 3 * unit, y1 + 3 * unit, col, 0.05f);
    c.rect(x0 - 2 * unit, y0 - 2 * unit, x1 + 2 * unit, y1 + 2 * unit, col, 0.08f);
    c.rect(x0 - unit, y0 - unit, x1 + unit, y1 + unit, col, 0.14f);
    c.rect(x0, y0, x1, y1, col, 0.9f);
}

void buildings(Canvas& c, Rng& rng, const Layer& L, float ground, bool antennas) {
    const float W = static_cast<float>(c.w()), H = static_cast<float>(c.h());
    const float unit = std::max(1.0f, W / 360.0f);  // roughly 1 dp at phone width
    float x = -rng.range(0, L.maxW * W);
    while (x < W) {
        float bw = rng.range(L.minW, L.maxW) * W;
        float bh = rng.range(L.minH, L.maxH) * H;
        float top = ground - bh;
        c.rect(x, top, x + bw, ground, L.body, 1.0f);
        // Lit windows on a regular grid.
        float cell = 3.2f * unit;
        for (float wy = top + cell; wy < ground - cell; wy += cell * 1.6f) {
            for (float wx = x + cell * 0.8f; wx < x + bw - cell; wx += cell * 1.4f) {
                if (rng.unit() > L.litChance) continue;
                float pick = rng.unit();
                Rgb col = pick < 0.5f ? kCyan : pick < 0.75f ? kElectric : pick < 0.93f ? kMagenta : kAmber;
                c.rect(wx, wy, wx + cell * 0.7f, wy + cell * 0.55f, col, L.litAlpha * rng.range(0.5f, 1.0f));
            }
        }
        if (antennas && bh > 0.4f * H && rng.unit() < 0.5f) {
            float ax = x + bw * rng.range(0.3f, 0.7f);
            float ah = rng.range(0.03f, 0.07f) * H;
            c.rect(ax, top - ah, ax + unit, top, L.body, 1.0f);
            glowRect(c, ax - unit, top - ah - unit, ax + 2 * unit, top - ah + 2 * unit, kMagenta, unit);
        }
        if (rng.unit() < L.signChance) {
            float sw = rng.range(2.5f, 4.5f) * unit;
            float sh = rng.range(0.05f, 0.11f) * H;
            float sx = rng.unit() < 0.5f ? x + 2 * unit : x + bw - sw - 2 * unit;
            float sy = top + rng.range(0.1f, 0.4f) * bh;
            if (sy + sh < ground) glowRect(c, sx, sy, sx + sw, sy + sh, rng.unit() < 0.6f ? kMagenta : kCyan, unit);
        }
        x += bw + rng.range(0.0f, 0.02f) * W;
    }
}

// A wet street: rows below the ground line mirror the city, darkened.
void reflection(Canvas& c, int ground) {
    for (int y = ground; y < c.h(); ++y) {
        int src = ground - (y - ground) - 1;
        if (src < 0) break;
        float fade = 0.28f * (1.0f - static_cast<float>(y - ground) / static_cast<float>(c.h() - ground + 1));
        for (int x = 0; x < c.w(); ++x) {
            Rgb s = c.get(x, src);
            Rgb base{kBackground.r, kBackground.g, kBackground.b};
            c.set(x, y, lerp(base, s, fade));
        }
    }
}

void rain(Canvas& c, Rng& rng) {
    const float W = static_cast<float>(c.w()), H = static_cast<float>(c.h());
    const float unit = std::max(1.0f, W / 360.0f);
    const int drops = c.w() / 3;
    for (int i = 0; i < drops; ++i) {
        float x = rng.unit() * W;
        float y = rng.unit() * H;
        float len = rng.range(6, 16) * unit;
        for (float t = 0; t < len; t += 1.0f) {
            c.blend(static_cast<int>(x - t * 0.18f), static_cast<int>(y + t), kElectric, 0.10f);
        }
    }
}

void deep(Canvas& c) {
    const float cx = static_cast<float>(c.w()) * 0.5f, cy = static_cast<float>(c.h()) * 0.38f;
    const float maxD = std::sqrt(cx * cx + cy * cy) * 1.4f;
    for (int y = 0; y < c.h(); ++y) {
        for (int x = 0; x < c.w(); ++x) {
            float dx = static_cast<float>(x) - cx, dy = static_cast<float>(y) - cy;
            float t = std::min(1.0f, std::sqrt(dx * dx + dy * dy) / maxD);
            c.set(x, y, lerp(kSurface, kBackground, t));
        }
    }
}

}  // namespace

bool valid(const Image& img) {
    return img.pixels && img.width > 0 && img.height > 0 && img.width <= 8192 && img.height <= 8192 &&
           img.stride >= img.width * 4;
}

bool fill(const Image& img, uint32_t rgb) {
    if (!valid(img)) return false;
    Canvas c(img);
    Rgb col{static_cast<float>((rgb >> 16) & 0xFF), static_cast<float>((rgb >> 8) & 0xFF),
            static_cast<float>(rgb & 0xFF)};
    for (int y = 0; y < c.h(); ++y)
        for (int x = 0; x < c.w(); ++x) c.set(x, y, col);
    return true;
}

bool renderWallpaper(const Image& img, std::string_view style, uint32_t seed) {
    if (!valid(img)) return false;
    Canvas c(img);
    Rng rng(seed);
    if (style == "deep") {
        deep(c);
        return true;
    }
    bool isRain = style == "rain";
    if (style != "skyline" && !isRain) return false;
    sky(c, isRain ? 1.6f : 1.0f);
    stars(c, rng);
    const float H = static_cast<float>(c.h());
    // Portrait screens get a lower horizon so panels sit over the sky.
    const float ground = H * (c.h() > c.w() ? 0.86f : 0.8f);
    buildings(c, rng, {kSurface, 0.18f, 0.42f, 0.04f, 0.09f, 0.10f, 0.45f, 0.0f}, ground, false);
    buildings(c, rng, {Rgb{9, 15, 30}, 0.25f, 0.56f, 0.06f, 0.13f, 0.16f, 0.75f, 0.25f}, ground, true);
    buildings(c, rng, {kBackground, 0.1f, 0.3f, 0.08f, 0.16f, 0.07f, 0.85f, 0.35f}, ground, false);
    // Low purple haze over the street level.
    for (int y = static_cast<int>(ground - H * 0.08f); y < static_cast<int>(ground); ++y) {
        float t = (static_cast<float>(y) - (ground - H * 0.08f)) / (H * 0.08f);
        for (int x = 0; x < c.w(); ++x) c.blend(x, y, kPurple, 0.06f * t);
    }
    reflection(c, static_cast<int>(ground));
    if (isRain) rain(c, rng);
    return true;
}

bool boxBlur(const Image& img, int radius, int passes) {
    if (!valid(img)) return false;
    radius = std::clamp(radius, 1, 64);
    passes = std::clamp(passes, 1, 4);
    const int w = img.width, h = img.height;
    std::vector<uint32_t> line(static_cast<size_t>(std::max(w, h)) * 4);
    auto blurLine = [&](uint8_t* base, int count, size_t step) {
        for (int i = 0; i < count; ++i)
            for (int ch = 0; ch < 4; ++ch) line[static_cast<size_t>(i) * 4 + ch] = base[i * step + ch];
        const int window = radius * 2 + 1;
        for (int ch = 0; ch < 4; ++ch) {
            uint32_t sum = 0;
            for (int k = -radius; k <= radius; ++k) {
                int idx = std::clamp(k, 0, count - 1);
                sum += line[static_cast<size_t>(idx) * 4 + ch];
            }
            for (int i = 0; i < count; ++i) {
                base[i * step + ch] = static_cast<uint8_t>(sum / window);
                int add = std::min(i + radius + 1, count - 1);
                int sub = std::max(i - radius, 0);
                sum += line[static_cast<size_t>(add) * 4 + ch];
                sum -= line[static_cast<size_t>(sub) * 4 + ch];
            }
        }
    };
    for (int p = 0; p < passes; ++p) {
        for (int y = 0; y < h; ++y) blurLine(img.pixels + static_cast<size_t>(y) * img.stride, w, 4);
        for (int x = 0; x < w; ++x) blurLine(img.pixels + static_cast<size_t>(x) * 4, h, static_cast<size_t>(img.stride));
    }
    return true;
}

}  // namespace lx::render
