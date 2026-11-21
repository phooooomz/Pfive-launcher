// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
#pragma once

#include <algorithm>
#include <cmath>

namespace lx {

struct Rect {
    float x = 0, y = 0, w = 0, h = 0;

    float right() const { return x + w; }
    float bottom() const { return y + h; }
    bool empty() const { return !(w > 0 && h > 0); }
    bool contains(float px, float py) const {
        return px >= x && py >= y && px < right() && py < bottom();
    }
    bool operator==(const Rect& o) const {
        return x == o.x && y == o.y && w == o.w && h == o.h;
    }
};

inline bool finite(float v) { return std::isfinite(v); }
inline bool finite(const Rect& r) {
    return finite(r.x) && finite(r.y) && finite(r.w) && finite(r.h);
}

inline float clampf(float v, float lo, float hi) {
    if (!(v == v)) return lo;  // NaN
    if (hi < lo) return lo;
    return std::min(std::max(v, lo), hi);
}

}  // namespace lx
