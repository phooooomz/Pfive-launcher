// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
// JNI boundary of the archive engine (com.lxlauncher.files.NativeArchive).
// Handles are heap objects behind a jlong; each owns a dup of the caller's fd.
// The dup shares the file offset, which is why the readers use pread on files.
// Strings cross as UTF-16 (never JNI's modified UTF-8), progress callbacks run
// on the calling thread, and allocation failures come back as ERR_IO.
#include <jni.h>

#include <fcntl.h>
#include <unistd.h>

#include <memory>
#include <new>
#include <string>

#include "lx/archive.hpp"

namespace {

namespace ar = lx::archive;

constexpr jint kInfoLength = 7;
constexpr jint kNameLength = 3;
constexpr jint kLimitLength = 5;

// Message of the last failed open (or finish) on this thread, for lastError(0).
thread_local std::string tLastError;

struct Handle {
    enum class Kind { Reader, Writer };
    Kind kind;
    std::unique_ptr<ar::Reader> reader;
    std::unique_ptr<ar::Writer> writer;
    // Argument errors found here rather than in the engine.
    std::string jniError;
};

Handle* readerFrom(jlong h) {
    auto* handle = reinterpret_cast<Handle*>(h);
    return handle && handle->kind == Handle::Kind::Reader ? handle : nullptr;
}

Handle* writerFrom(jlong h) {
    auto* handle = reinterpret_cast<Handle*>(h);
    return handle && handle->kind == Handle::Kind::Writer ? handle : nullptr;
}

jint code(ar::Status s) { return static_cast<jint>(s); }

std::string toUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringLength(s);
    std::u16string units(static_cast<size_t>(len), u'\0');
    env->GetStringRegion(s, 0, len, reinterpret_cast<jchar*>(units.data()));
    return ar::utf16ToUtf8(units);
}

jstring toJava(JNIEnv* env, const std::string& s) {
    const std::u16string units = ar::utf8ToUtf16(s);
    return env->NewString(reinterpret_cast<const jchar*>(units.data()), static_cast<jsize>(units.size()));
}

int dupFd(jint fd) { return fd < 0 ? -1 : fcntl(fd, F_DUPFD_CLOEXEC, 0); }

constexpr const char* kNoOnBytes =
    "NativeArchive.Progress.onBytes(J)Z not found; R8 has to keep it (see proguard-rules.pro)";

// Wraps a Kotlin NativeArchive.Progress. A pending exception counts as cancel.
// False when the object has no onBytes(J)Z, which is what a release build
// without the R8 keep rule looks like: that has to be a clear error, not a
// cancel the user never asked for.
bool progressFor(JNIEnv* env, jobject progress, ar::Progress* out) {
    *out = {};
    if (!progress) return true;
    jclass cls = env->GetObjectClass(progress);
    jmethodID onBytes = cls ? env->GetMethodID(cls, "onBytes", "(J)Z") : nullptr;
    // GetMethodID leaves NoSuchMethodError pending when it fails.
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (cls) env->DeleteLocalRef(cls);
    if (!onBytes) return false;
    *out = [env, progress, onBytes](uint64_t bytes) {
        const jboolean keepGoing = env->CallBooleanMethod(progress, onBytes, static_cast<jlong>(bytes));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keepGoing == JNI_TRUE;
    };
    return true;
}

// Stores value (or null) at names[index]. False with the exception cleared
// when NewString or the store failed, after which no further JNI call that
// needs a clean state has been made.
bool setName(JNIEnv* env, jobjectArray names, jint index, const std::string* value) {
    jstring s = nullptr;
    if (value) {
        s = toJava(env, *value);
        if (!s || env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    env->SetObjectArrayElement(names, index, s);
    const bool stored = !env->ExceptionCheck();
    if (!stored) env->ExceptionClear();
    if (s) env->DeleteLocalRef(s);
    return stored;
}

}  // namespace

extern "C" {

#define FN(name) JNIEXPORT JNICALL Java_com_lxlauncher_files_NativeArchive_##name

jint FN(detect)(JNIEnv*, jobject, jint fd) { return static_cast<jint>(ar::detect(fd)); }

jlong FN(openReader)(JNIEnv* env, jobject, jint fd, jint format, jlongArray limitsArray) {
    try {
        if (!limitsArray || env->GetArrayLength(limitsArray) < kLimitLength) {
            tLastError = "limits array is too short";
            return 0;
        }
        jlong raw[kLimitLength];
        env->GetLongArrayRegion(limitsArray, 0, kLimitLength, raw);
        auto value = [&](int i) { return raw[i] > 0 ? static_cast<uint64_t>(raw[i]) : uint64_t{0}; };
        ar::Limits limits;
        limits.maxEntries = value(0);
        limits.maxTotalBytes = value(1);
        limits.maxEntryBytes = value(2);
        limits.maxRatioX100 = value(3);
        limits.ratioGraceBytes = value(4);
        const int own = dupFd(fd);
        if (own < 0) {
            tLastError = "cannot duplicate the file descriptor";
            return 0;
        }
        ar::Status status = ar::Status::Ok;
        std::string error;
        auto reader = ar::Reader::open(own, static_cast<ar::Format>(format), limits, &status, &error);
        if (!reader) {
            tLastError = error;
            return 0;
        }
        auto* handle = new Handle{Handle::Kind::Reader, std::move(reader), nullptr, {}};
        return reinterpret_cast<jlong>(handle);
    } catch (const std::bad_alloc&) {
        tLastError = "out of memory";
        return 0;
    }
}

jint FN(next)(JNIEnv* env, jobject, jlong h, jlongArray info, jobjectArray names) {
    Handle* handle = readerFrom(h);
    if (!handle) return code(ar::Status::IoError);
    try {
        handle->jniError.clear();
        if (!info || env->GetArrayLength(info) < kInfoLength || !names || env->GetArrayLength(names) < kNameLength) {
            handle->jniError = "info or names array is too short";
            return code(ar::Status::IoError);
        }
        ar::Entry e;
        const ar::Status s = handle->reader->next(e);
        if (s != ar::Status::Ok) return code(s);
        const jlong values[kInfoLength] = {
            static_cast<jlong>(e.type),
            static_cast<jlong>(e.size),
            static_cast<jlong>(e.compressed),
            static_cast<jlong>(e.mtimeMs),
            static_cast<jlong>(e.mode),
            static_cast<jlong>(e.verdict),
            e.encrypted ? 1 : 0,
        };
        env->SetLongArrayRegion(info, 0, kInfoLength, values);
        const bool filled = !env->ExceptionCheck() && setName(env, names, 0, &e.raw) &&
                            setName(env, names, 1, e.verdict == ar::PathVerdict::Ok ? &e.path : nullptr) &&
                            setName(env, names, 2, e.link.empty() ? nullptr : &e.link);
        if (!filled) {
            env->ExceptionClear();
            handle->jniError = "could not fill the result arrays";
            return code(ar::Status::IoError);
        }
        return code(ar::Status::Ok);
    } catch (const std::bad_alloc&) {
        handle->jniError = "out of memory";
        return code(ar::Status::IoError);
    }
}

jint FN(extract)(JNIEnv* env, jobject, jlong h, jint outFd, jobject progress) {
    Handle* handle = readerFrom(h);
    if (!handle) return code(ar::Status::IoError);
    try {
        handle->jniError.clear();
        ar::Progress onBytes;
        if (!progressFor(env, progress, &onBytes)) {
            handle->jniError = kNoOnBytes;
            return code(ar::Status::IoError);
        }
        return code(handle->reader->extract(outFd < 0 ? -1 : outFd, onBytes));
    } catch (const std::bad_alloc&) {
        handle->jniError = "out of memory";
        return code(ar::Status::IoError);
    }
}

void FN(closeReader)(JNIEnv*, jobject, jlong h) { delete readerFrom(h); }

jlong FN(openWriter)(JNIEnv*, jobject, jint fd, jint format, jint level) {
    try {
        const int own = dupFd(fd);
        if (own < 0) {
            tLastError = "cannot duplicate the file descriptor";
            return 0;
        }
        ar::WriterOptions options;
        options.level = level;
        std::string error;
        auto writer = ar::Writer::open(own, static_cast<ar::Format>(format), options, &error);
        if (!writer) {
            tLastError = error;
            return 0;
        }
        auto* handle = new Handle{Handle::Kind::Writer, nullptr, std::move(writer), {}};
        return reinterpret_cast<jlong>(handle);
    } catch (const std::bad_alloc&) {
        tLastError = "out of memory";
        return 0;
    }
}

jint FN(addDirectory)(JNIEnv* env, jobject, jlong h, jstring path, jlong mtimeMs, jint mode) {
    Handle* handle = writerFrom(h);
    if (!handle) return code(ar::Status::IoError);
    try {
        handle->jniError.clear();
        return code(handle->writer->addDirectory(toUtf8(env, path), mtimeMs, static_cast<uint32_t>(mode)));
    } catch (const std::bad_alloc&) {
        handle->jniError = "out of memory";
        return code(ar::Status::IoError);
    }
}

jint FN(addFile)(JNIEnv* env, jobject, jlong h, jstring path, jint inFd, jlong size, jlong mtimeMs, jint mode,
                 jobject progress) {
    Handle* handle = writerFrom(h);
    if (!handle) return code(ar::Status::IoError);
    try {
        handle->jniError.clear();
        if (size < 0) {
            handle->jniError = "negative size";
            return code(ar::Status::IoError);
        }
        ar::Progress onBytes;
        if (!progressFor(env, progress, &onBytes)) {
            handle->jniError = kNoOnBytes;
            return code(ar::Status::IoError);
        }
        return code(handle->writer->addFile(toUtf8(env, path), inFd, static_cast<uint64_t>(size), mtimeMs,
                                            static_cast<uint32_t>(mode), onBytes));
    } catch (const std::bad_alloc&) {
        handle->jniError = "out of memory";
        return code(ar::Status::IoError);
    }
}

jint FN(finish)(JNIEnv*, jobject, jlong h) {
    Handle* handle = writerFrom(h);
    if (!handle) return code(ar::Status::IoError);
    ar::Status s = ar::Status::IoError;
    try {
        s = handle->writer->finish();
        // The handle is gone after this call, so a failure's message moves to lastError(0).
        if (s != ar::Status::Ok) tLastError = handle->writer->lastError();
    } catch (const std::bad_alloc&) {
        tLastError = "out of memory";
    }
    delete handle;
    return code(s);
}

void FN(abort)(JNIEnv*, jobject, jlong h) { delete writerFrom(h); }

jstring FN(lastError)(JNIEnv* env, jobject, jlong h) {
    try {
        if (h == 0) return toJava(env, tLastError);
        auto* handle = reinterpret_cast<Handle*>(h);
        if (!handle->jniError.empty()) return toJava(env, handle->jniError);
        if (handle->kind == Handle::Kind::Reader) return toJava(env, handle->reader->lastError());
        return toJava(env, handle->writer->lastError());
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
}

jint FN(checkPath)(JNIEnv* env, jobject, jstring raw, jboolean fat, jobjectArray normalized) {
    try {
        std::string clean;
        const ar::PathVerdict v = ar::checkPath(toUtf8(env, raw), fat == JNI_TRUE, &clean);
        // A failed store leaves normalized[0] as it was; the verdict still stands.
        if (normalized && env->GetArrayLength(normalized) > 0)
            setName(env, normalized, 0, v == ar::PathVerdict::Ok ? &clean : nullptr);
        return static_cast<jint>(v);
    } catch (const std::bad_alloc&) {
        return static_cast<jint>(ar::PathVerdict::Empty);
    }
}

jint FN(checkName)(JNIEnv* env, jobject, jstring name, jboolean fat) {
    try {
        return static_cast<jint>(ar::checkName(toUtf8(env, name), fat == JNI_TRUE));
    } catch (const std::bad_alloc&) {
        return static_cast<jint>(ar::PathVerdict::Empty);
    }
}

jstring FN(numberedName)(JNIEnv* env, jobject, jstring name, jint n) {
    try {
        return toJava(env, ar::numberedName(toUtf8(env, name), n));
    } catch (const std::bad_alloc&) {
        return name;
    }
}

}  // extern "C"
