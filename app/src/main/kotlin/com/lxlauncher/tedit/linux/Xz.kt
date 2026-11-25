// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.linux

import java.io.IOException
import java.io.InputStream

/** XZ Embedded, built into liblxnative (app/src/main/cpp/xz_jni.cpp). */
internal object XzNative {
    init {
        System.loadLibrary("lxnative")
    }

    const val OK = 0
    const val STREAM_END = 1
    const val UNSUPPORTED_CHECK = 2

    @JvmStatic external fun open(): Long
    @JvmStatic external fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int, out: ByteArray, outOff: Int, outLen: Int, io: IntArray): Int
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun close(handle: Long)
}

/**
 * Decompresses an .xz stream as it is read. Concatenated streams are read one after another, and the zero padding
 * xz allows between them is skipped. A damaged archive ends in an IOException rather than in short output.
 */
class XzInputStream(private val source: InputStream) : InputStream() {
    private var handle = XzNative.open().also { if (it == 0L) throw IOException("xz: no memory for the decoder") }
    private val inBuf = ByteArray(64 * 1024)
    private var inPos = 0
    private var inLen = 0
    private val io = IntArray(2)
    private var ended = false
    private var sourceDone = false
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (handle == 0L) throw IOException("xz: stream closed")
        while (true) {
            if (ended) {
                // After a stream ends only padding or another stream may follow.
                if (!nextStream()) return -1
            }
            if (inPos == inLen && !sourceDone) {
                inLen = source.read(inBuf, 0, inBuf.size)
                inPos = 0
                if (inLen < 0) { inLen = 0; sourceDone = true }
            }
            val r = XzNative.decode(handle, inBuf, inPos, inLen - inPos, b, off, len, io)
            inPos += io[0]
            val produced = io[1]
            when (r) {
                XzNative.OK, XzNative.UNSUPPORTED_CHECK -> {
                    if (produced > 0) return produced
                    if (sourceDone && inPos == inLen) throw IOException("xz: archive ends in the middle of a stream")
                }
                XzNative.STREAM_END -> {
                    ended = true
                    if (produced > 0) return produced
                }
                else -> throw IOException("xz: damaged archive (code $r)")
            }
        }
    }

    /** Skips the padding after a finished stream; true when another stream starts. */
    private fun nextStream(): Boolean {
        while (true) {
            while (inPos < inLen && inBuf[inPos] == 0.toByte()) inPos++
            if (inPos < inLen) {
                XzNative.reset(handle)
                ended = false
                return true
            }
            if (sourceDone) return false
            inLen = source.read(inBuf, 0, inBuf.size)
            inPos = 0
            if (inLen < 0) { inLen = 0; sourceDone = true }
        }
    }

    override fun close() {
        if (handle != 0L) {
            XzNative.close(handle)
            handle = 0L
        }
        source.close()
    }
}
