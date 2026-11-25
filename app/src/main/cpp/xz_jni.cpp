// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
//
// Streaming .xz decoding for Tedit's Linux setup, on top of XZ Embedded (third_party/xz-embedded). Kotlin feeds
// compressed bytes and takes decompressed ones (com.lxlauncher.tedit.linux.XzInputStream); nothing here touches files.
#include <jni.h>

#include <cstdint>
#include <new>

extern "C" {
#include "xz.h"
}

namespace {

// The rootfs archives are made with xz -9 (64 MiB dictionary); the decoder takes what a stream asks for up to this.
constexpr uint32_t kDictMax = 1u << 26;

struct XzState {
    xz_dec* dec = nullptr;
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_lxlauncher_tedit_linux_XzNative_open(JNIEnv*, jclass) {
    static const bool tables = [] {
        xz_crc32_init();
        xz_crc64_init();
        return true;
    }();
    (void)tables;
    auto* s = new (std::nothrow) XzState();
    if (s == nullptr) return 0;
    s->dec = xz_dec_init(XZ_DYNALLOC, kDictMax);
    if (s->dec == nullptr) {
        delete s;
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

// Decodes from in[inOff, inOff+inLen) into out[outOff, outOff+outLen). io[0] receives the bytes taken from in, io[1]
// the bytes written to out. Returns the xz_ret value (XZ_OK = 0, XZ_STREAM_END = 1, the rest are errors).
extern "C" JNIEXPORT jint JNICALL Java_com_lxlauncher_tedit_linux_XzNative_decode(JNIEnv* env, jclass, jlong handle,
        jbyteArray in, jint inOff, jint inLen, jbyteArray out, jint outOff, jint outLen, jintArray io) {
    auto* s = reinterpret_cast<XzState*>(handle);
    if (s == nullptr || s->dec == nullptr) return XZ_OPTIONS_ERROR;
    jbyte* inBytes = env->GetByteArrayElements(in, nullptr);
    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    if (inBytes == nullptr || outBytes == nullptr) {
        if (inBytes != nullptr) env->ReleaseByteArrayElements(in, inBytes, JNI_ABORT);
        if (outBytes != nullptr) env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
        return XZ_MEM_ERROR;
    }
    xz_buf b{};
    b.in = reinterpret_cast<const uint8_t*>(inBytes) + inOff;
    b.in_pos = 0;
    b.in_size = static_cast<size_t>(inLen);
    b.out = reinterpret_cast<uint8_t*>(outBytes) + outOff;
    b.out_pos = 0;
    b.out_size = static_cast<size_t>(outLen);
    xz_ret r = xz_dec_run(s->dec, &b);
    env->ReleaseByteArrayElements(in, inBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBytes, 0);
    jint used[2] = {static_cast<jint>(b.in_pos), static_cast<jint>(b.out_pos)};
    env->SetIntArrayRegion(io, 0, 2, used);
    return static_cast<jint>(r);
}

// Starts a new stream with the same decoder, for archives made of concatenated .xz streams.
extern "C" JNIEXPORT void JNICALL Java_com_lxlauncher_tedit_linux_XzNative_reset(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<XzState*>(handle);
    if (s != nullptr && s->dec != nullptr) xz_dec_reset(s->dec);
}

extern "C" JNIEXPORT void JNICALL Java_com_lxlauncher_tedit_linux_XzNative_close(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<XzState*>(handle);
    if (s == nullptr) return;
    if (s->dec != nullptr) xz_dec_end(s->dec);
    delete s;
}
