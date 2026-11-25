// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// JNI boundary. Every entry point validates its arguments, converts Java
// strings to real UTF-8 (JNI's "modified UTF-8" mangles characters outside the
// BMP), and holds the engine mutex for the duration of the call. No Java
// references are kept beyond a call, so there is nothing to leak.
#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "lx/engine.hpp"
#include "lx/persistence.hpp"
#include "lx/render.hpp"
#include "lx/search.hpp"

namespace {

constexpr const char* kTag = "LxNative";

std::string toUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (!chars) return {};
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::string& s) {
    std::vector<jchar> out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        auto b0 = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (b0 < 0x80) {
            cp = b0;
        } else if ((b0 & 0xE0) == 0xC0 && i + 1 < s.size()) {
            cp = ((b0 & 0x1F) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3F);
            len = 2;
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < s.size()) {
            cp = ((b0 & 0x0F) << 12) | ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(s[i + 2]) & 0x3F);
            len = 3;
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < s.size()) {
            cp = ((b0 & 0x07) << 18) | ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(s[i + 2]) & 0x3F) << 6) | (static_cast<unsigned char>(s[i + 3]) & 0x3F);
            len = 4;
        }
        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(cp > 0xFFFF ? 0xFFFD : cp));
        }
        i += len;
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

std::vector<std::string> toStrings(JNIEnv* env, jobjectArray arr, size_t maxItems) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n && static_cast<size_t>(i) < maxItems; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(toUtf8(env, s));
        env->DeleteLocalRef(s);
        // Out of memory in the VM: no further JNI calls while its exception is pending.
        if (env->ExceptionCheck()) break;
    }
    return out;
}

jfloatArray rectArray(JNIEnv* env, const lx::Rect& r) {
    jfloatArray a = env->NewFloatArray(4);
    if (!a) return nullptr;
    float v[4] = {r.x, r.y, r.w, r.h};
    env->SetFloatArrayRegion(a, 0, 4, v);
    return a;
}

jintArray intArray(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) env->SetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}

lx::Engine* engineOf(jlong handle) { return reinterpret_cast<lx::Engine*>(handle); }

#define LX_ENGINE(fallback)                       \
    lx::Engine* engine = engineOf(handle);        \
    if (!engine) return fallback;                 \
    std::lock_guard<std::mutex> lock(engine->mutex())

std::optional<lx::PinList> pinList(jint l) {
    switch (l) {
        case 0: return lx::PinList::Dock;
        case 1: return lx::PinList::Taskbar;
        case 2: return lx::PinList::Favorites;
        case 3: return lx::PinList::Hidden;
        case 4: return lx::PinList::Recent;
        default: return std::nullopt;
    }
}

std::string reportJson(const lx::LoadReport& r) {
    lx::json::Value o{lx::json::Object{}};
    o.set("ok", r.ok);
    o.set("fresh", r.fresh);
    o.set("usedBackup", r.usedBackup);
    o.set("mainCorrupt", r.mainCorrupt);
    o.set("schemaVersion", r.schemaVersion);
    o.set("skipped", static_cast<double>(r.skipped));
    o.set("error", r.error);
    return lx::json::serialize(o);
}

lx::DefaultsSpec defaultsFrom(JNIEnv* env, jobjectArray names, jstring welcome, jboolean landscape) {
    lx::DefaultsSpec spec;
    spec.workspaceNames = toStrings(env, names, lx::Desktop::kMaxWorkspaces);
    spec.welcomeTitle = toUtf8(env, welcome);
    spec.landscape = landscape;
    return spec;
}

template <typename F>
jboolean withBitmap(JNIEnv* env, jobject bitmap, F&& f) {
    if (!bitmap) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return JNI_FALSE;
    lx::render::Image img{static_cast<uint8_t*>(pixels), static_cast<int>(info.width),
                          static_cast<int>(info.height), static_cast<int>(info.stride)};
    bool ok = f(img);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

extern "C" {

#define FN(name) JNIEXPORT JNICALL Java_com_lxlauncher_core_NativeCore_##name

// ---- lifecycle and persistence ----

jlong FN(create)(JNIEnv*, jobject) {
    auto* e = new (std::nothrow) lx::Engine();
    return reinterpret_cast<jlong>(e);
}

void FN(destroy)(JNIEnv*, jobject, jlong handle) {
    delete engineOf(handle);
}

jstring FN(load)(JNIEnv* env, jobject, jlong handle, jstring path, jobjectArray names, jstring welcome,
                 jboolean landscape) {
    LX_ENGINE(nullptr);
    lx::Store store(toUtf8(env, path));
    auto report = engine->loadFrom(store, defaultsFrom(env, names, welcome, landscape));
    if (!report.error.empty()) __android_log_print(ANDROID_LOG_WARN, kTag, "load: %s", report.error.c_str());
    return toJava(env, reportJson(report));
}

jboolean FN(save)(JNIEnv* env, jobject, jlong handle, jstring path) {
    lx::Engine* engine = engineOf(handle);
    if (!engine) return JNI_FALSE;
    // The engine lock is held only while the layout is turned into text. Writing it (fsync, read back, two renames,
    // fsync of the folder) happens outside, so a window drag on the UI thread no longer waits for the disk. Saves
    // take one at a time, each exporting and writing before the next, so an older layout never lands last.
    static std::mutex saving;
    std::lock_guard<std::mutex> one(saving);
    std::string document;
    {
        std::lock_guard<std::mutex> lock(engine->mutex());
        document = engine->exportDocument();
    }
    lx::Store store(toUtf8(env, path));
    return store.save(document) ? JNI_TRUE : JNI_FALSE;
}

jstring FN(exportDocument)(JNIEnv* env, jobject, jlong handle) {
    LX_ENGINE(nullptr);
    return toJava(env, engine->exportDocument());
}

jstring FN(importDocument)(JNIEnv* env, jobject, jlong handle, jstring text) {
    LX_ENGINE(nullptr);
    return toJava(env, reportJson(engine->importDocument(toUtf8(env, text))));
}

void FN(resetDefaults)(JNIEnv* env, jobject, jlong handle, jobjectArray names, jstring welcome,
                       jboolean landscape, jboolean keepSettings) {
    LX_ENGINE();
    auto spec = defaultsFrom(env, names, welcome, landscape);
    if (keepSettings) engine->resetLayout(spec);
    else engine->resetToDefaults(spec);
}

jstring FN(snapshot)(JNIEnv* env, jobject, jlong handle) {
    LX_ENGINE(nullptr);
    return toJava(env, engine->snapshot());
}

jobjectArray FN(diagnostics)(JNIEnv* env, jobject, jlong handle) {
    LX_ENGINE(nullptr);
    auto lines = engine->diagnostics();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(lines.size()), stringClass, nullptr);
    for (size_t i = 0; arr && i < lines.size(); ++i) {
        jstring s = toJava(env, lines[i]);
        if (!s) return nullptr;  // out of memory; the VM's exception goes to the caller
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

jboolean FN(saveText)(JNIEnv* env, jobject, jstring path, jstring text) {
    std::string t = toUtf8(env, text);
    if (t.size() > 512 * 1024) return JNI_FALSE;
    return lx::Store(toUtf8(env, path)).save(t) ? JNI_TRUE : JNI_FALSE;
}

jstring FN(loadText)(JNIEnv* env, jobject, jstring path) {
    auto r = lx::Store(toUtf8(env, path)).load();
    if (r.source == lx::LoadSource::None) return nullptr;
    return toJava(env, r.payload);
}

// ---- settings ----

jboolean FN(setSetting)(JNIEnv* env, jobject, jlong handle, jstring key, jstring value) {
    LX_ENGINE(JNI_FALSE);
    return engine->setSetting(toUtf8(env, key), toUtf8(env, value)) ? JNI_TRUE : JNI_FALSE;
}

jstring FN(getSetting)(JNIEnv* env, jobject, jlong handle, jstring key) {
    LX_ENGINE(nullptr);
    return toJava(env, engine->settings().get(toUtf8(env, key)));
}

jboolean FN(applyPreset)(JNIEnv* env, jobject, jlong handle, jstring name) {
    LX_ENGINE(JNI_FALSE);
    return engine->applyPreset(toUtf8(env, name)) ? JNI_TRUE : JNI_FALSE;
}

// ---- workspaces ----

jint FN(addWorkspace)(JNIEnv* env, jobject, jlong handle, jstring name) {
    LX_ENGINE(0);
    return static_cast<jint>(engine->desktop().addWorkspace(toUtf8(env, name)));
}

jboolean FN(renameWorkspace)(JNIEnv* env, jobject, jlong handle, jint id, jstring name) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().renameWorkspace(static_cast<uint32_t>(id), toUtf8(env, name));
}

jintArray FN(removeWorkspace)(JNIEnv* env, jobject, jlong handle, jint id, jint moveTo) {
    LX_ENGINE(nullptr);
    auto released = engine->removeWorkspace(static_cast<uint32_t>(id), static_cast<uint32_t>(moveTo));
    if (!released) return nullptr;
    return intArray(env, *released);
}

jboolean FN(setActiveWorkspace)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().setActive(static_cast<uint32_t>(id));
}

jint FN(neighbourWorkspace)(JNIEnv*, jobject, jlong handle, jint direction) {
    LX_ENGINE(0);
    return static_cast<jint>(engine->desktop().neighbour(direction));
}

jboolean FN(setWallpaper)(JNIEnv* env, jobject, jlong handle, jint id, jstring ref) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().setWallpaper(static_cast<uint32_t>(id), toUtf8(env, ref));
}

// ---- shortcuts and folders ----

jint FN(addShortcut)(JNIEnv* env, jobject, jlong handle, jint ws, jstring appKey, jint col, jint row) {
    LX_ENGINE(0);
    std::optional<lx::Cell> cell;
    if (col >= 0 && row >= 0) cell = lx::Cell{col, row};
    return static_cast<jint>(engine->desktop().addShortcut(static_cast<uint32_t>(ws), toUtf8(env, appKey), cell));
}

jboolean FN(moveItem)(JNIEnv*, jobject, jlong handle, jint id, jint col, jint row) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().moveItem(static_cast<uint32_t>(id), {col, row});
}

jboolean FN(moveItemToWorkspace)(JNIEnv*, jobject, jlong handle, jint id, jint ws) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().moveItemToWorkspace(static_cast<uint32_t>(id), static_cast<uint32_t>(ws));
}

jboolean FN(removeItem)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().removeItem(static_cast<uint32_t>(id));
}

jint FN(createFolder)(JNIEnv* env, jobject, jlong handle, jint a, jint b, jstring name) {
    LX_ENGINE(0);
    return static_cast<jint>(
        engine->desktop().createFolder(static_cast<uint32_t>(a), static_cast<uint32_t>(b), toUtf8(env, name)));
}

jboolean FN(addToFolder)(JNIEnv*, jobject, jlong handle, jint folder, jint shortcut) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().addToFolder(static_cast<uint32_t>(folder), static_cast<uint32_t>(shortcut));
}

jint FN(removeFromFolder)(JNIEnv* env, jobject, jlong handle, jint folder, jstring appKey) {
    LX_ENGINE(0);
    return static_cast<jint>(engine->desktop().removeFromFolder(static_cast<uint32_t>(folder), toUtf8(env, appKey)));
}

jboolean FN(renameFolder)(JNIEnv* env, jobject, jlong handle, jint folder, jstring name) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().renameFolder(static_cast<uint32_t>(folder), toUtf8(env, name));
}

jint FN(removePackage)(JNIEnv* env, jobject, jlong handle, jstring pkg, jlong userSerial) {
    LX_ENGINE(0);
    return static_cast<jint>(engine->desktop().removePackage(toUtf8(env, pkg), static_cast<int64_t>(userSerial)));
}

// ---- widgets ----

jint FN(addWidget)(JNIEnv* env, jobject, jlong handle, jint ws, jstring kind, jint appWidgetId, jfloat x,
                   jfloat y, jfloat w, jfloat h) {
    LX_ENGINE(0);
    return static_cast<jint>(
        engine->desktop().addWidget(static_cast<uint32_t>(ws), toUtf8(env, kind), appWidgetId, x, y, w, h));
}

jboolean FN(setWidgetRect)(JNIEnv*, jobject, jlong handle, jint id, jfloat x, jfloat y, jfloat w, jfloat h) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().setWidgetRect(static_cast<uint32_t>(id), x, y, w, h);
}

// Returns the released app widget id, -1 for a native widget, -2 if unknown.
jint FN(removeWidget)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(-2);
    auto r = engine->desktop().removeWidget(static_cast<uint32_t>(id));
    return r ? *r : -2;
}

jboolean FN(setWidgetConfig)(JNIEnv* env, jobject, jlong handle, jint id, jstring key, jstring value) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().setWidgetConfig(static_cast<uint32_t>(id), toUtf8(env, key), toUtf8(env, value));
}

jboolean FN(setWidgetVisible)(JNIEnv*, jobject, jlong handle, jint id, jboolean visible) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().setWidgetVisible(static_cast<uint32_t>(id), visible);
}

jboolean FN(moveWidgetToWorkspace)(JNIEnv*, jobject, jlong handle, jint id, jint ws) {
    LX_ENGINE(JNI_FALSE);
    return engine->desktop().moveWidgetToWorkspace(static_cast<uint32_t>(id), static_cast<uint32_t>(ws));
}

// ---- pins ----

jboolean FN(pin)(JNIEnv* env, jobject, jlong handle, jint list, jstring entry, jint index) {
    LX_ENGINE(JNI_FALSE);
    auto l = pinList(list);
    return l && engine->desktop().pin(*l, toUtf8(env, entry), index);
}

jboolean FN(unpin)(JNIEnv* env, jobject, jlong handle, jint list, jstring entry) {
    LX_ENGINE(JNI_FALSE);
    auto l = pinList(list);
    return l && engine->desktop().unpin(*l, toUtf8(env, entry));
}

jboolean FN(movePin)(JNIEnv* env, jobject, jlong handle, jint list, jstring entry, jint delta) {
    LX_ENGINE(JNI_FALSE);
    auto l = pinList(list);
    return l && engine->desktop().movePin(*l, toUtf8(env, entry), delta);
}

// ---- windows ----

void FN(setViewport)(JNIEnv*, jobject, jlong handle, jfloat ux, jfloat uy, jfloat uw, jfloat uh, jfloat fx,
                     jfloat fy, jfloat fw, jfloat fh, jfloat canvasAspect) {
    LX_ENGINE();
    engine->setViewport({ux, uy, uw, uh}, {fx, fy, fw, fh}, canvasAspect);
}

void FN(setWindowConstraints)(JNIEnv*, jobject, jlong handle, jfloat minW, jfloat minH, jfloat titleH) {
    LX_ENGINE();
    engine->windows().setConstraints({minW, minH, titleH});
}

jint FN(openWindow)(JNIEnv* env, jobject, jlong handle, jstring kind, jstring title, jint ws, jstring state) {
    LX_ENGINE(0);
    auto s = lx::windowStateFromString(toUtf8(env, state)).value_or(lx::WindowState::Floating);
    return static_cast<jint>(engine->openWindow(toUtf8(env, kind), toUtf8(env, title), static_cast<uint32_t>(ws), s));
}

jboolean FN(closeWindow)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->windows().close(static_cast<uint32_t>(id));
}

jboolean FN(focusWindow)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->windows().focus(static_cast<uint32_t>(id));
}

jfloatArray FN(moveWindow)(JNIEnv* env, jobject, jlong handle, jint id, jfloat dx, jfloat dy) {
    LX_ENGINE(nullptr);
    if (!engine->windows().move(static_cast<uint32_t>(id), dx, dy)) return nullptr;
    return rectArray(env, engine->windows().info(static_cast<uint32_t>(id))->bounds);
}

jfloatArray FN(resizeWindow)(JNIEnv* env, jobject, jlong handle, jint id, jint edges, jfloat dx, jfloat dy) {
    LX_ENGINE(nullptr);
    if (!engine->windows().resize(static_cast<uint32_t>(id), static_cast<uint32_t>(edges), dx, dy)) return nullptr;
    return rectArray(env, engine->windows().info(static_cast<uint32_t>(id))->bounds);
}

jboolean FN(setWindowState)(JNIEnv* env, jobject, jlong handle, jint id, jstring state) {
    LX_ENGINE(JNI_FALSE);
    auto s = lx::windowStateFromString(toUtf8(env, state));
    return s && engine->windows().setState(static_cast<uint32_t>(id), *s);
}

jboolean FN(toggleMaximize)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->windows().toggleMaximize(static_cast<uint32_t>(id));
}

jboolean FN(leaveFullscreen)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->windows().leaveFullscreen(static_cast<uint32_t>(id));
}

jboolean FN(restoreWindow)(JNIEnv*, jobject, jlong handle, jint id) {
    LX_ENGINE(JNI_FALSE);
    return engine->windows().restore(static_cast<uint32_t>(id));
}

jboolean FN(moveWindowToWorkspace)(JNIEnv*, jobject, jlong handle, jint id, jint ws) {
    LX_ENGINE(JNI_FALSE);
    return engine->moveWindowToWorkspace(static_cast<uint32_t>(id), static_cast<uint32_t>(ws));
}

void FN(tileWindows)(JNIEnv*, jobject, jlong handle, jint ws) {
    LX_ENGINE();
    engine->windows().tile(static_cast<uint32_t>(ws));
}

void FN(minimizeAll)(JNIEnv*, jobject, jlong handle, jint ws) {
    LX_ENGINE();
    engine->windows().minimizeAll(static_cast<uint32_t>(ws));
}

jstring FN(snapTarget)(JNIEnv* env, jobject, jlong handle, jfloat x, jfloat y, jfloat threshold) {
    LX_ENGINE(nullptr);
    auto s = engine->windows().snapTarget(x, y, threshold);
    return toJava(env, s ? lx::toString(*s) : "");
}

jfloatArray FN(windowBounds)(JNIEnv* env, jobject, jlong handle, jint id) {
    LX_ENGINE(nullptr);
    auto info = engine->windows().info(static_cast<uint32_t>(id));
    return info ? rectArray(env, info->bounds) : nullptr;
}

jint FN(findWindow)(JNIEnv* env, jobject, jlong handle, jint ws, jstring kind) {
    LX_ENGINE(0);
    auto id = engine->windows().findByKind(static_cast<uint32_t>(ws), toUtf8(env, kind));
    return id ? static_cast<jint>(*id) : 0;
}

// ---- search and rendering (no engine state) ----

jintArray FN(rank)(JNIEnv* env, jobject, jstring query, jobjectArray labels, jint limit) {
    auto candidates = toStrings(env, labels, 100000);
    if (env->ExceptionCheck()) return nullptr;
    auto r = lx::rankMatches(toUtf8(env, query), candidates, limit > 0 ? static_cast<size_t>(limit) : 0);
    if (env->ExceptionCheck()) return nullptr;
    return intArray(env, r);
}

jboolean FN(renderWallpaper)(JNIEnv* env, jobject, jobject bitmap, jstring style, jint seed) {
    std::string st = toUtf8(env, style);
    return withBitmap(env, bitmap, [&](const lx::render::Image& img) {
        return lx::render::renderWallpaper(img, st, static_cast<uint32_t>(seed));
    });
}

jboolean FN(blurBitmap)(JNIEnv* env, jobject, jobject bitmap, jint radius, jint passes) {
    return withBitmap(env, bitmap, [&](const lx::render::Image& img) {
        return lx::render::boxBlur(img, radius, passes);
    });
}

jboolean FN(fillBitmap)(JNIEnv* env, jobject, jobject bitmap, jint rgb) {
    return withBitmap(env, bitmap, [&](const lx::render::Image& img) {
        return lx::render::fill(img, static_cast<uint32_t>(rgb) & 0xFFFFFF);
    });
}

}  // extern "C"
