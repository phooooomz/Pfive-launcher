// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.net.ssl.SSLSockets
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/**
 * Android's wireless debugging pairing, the client's half: the same exchange `adb pair` runs. The six digit
 * code the phone shows proves to both sides who is at the other end, and LX's public key is handed over
 * encrypted with what comes of it. Android then accepts LX's key without another question.
 */
internal object AdbPairing {
    private const val VERSION = 1
    private const val TYPE_SPAKE2 = 0
    private const val TYPE_PEER_INFO = 1
    private const val HEADER_BYTES = 6
    private const val MAX_PAYLOAD = 16384
    private const val PEER_INFO_BYTES = 8192
    private const val KEY_RSA_PUBLIC = 0
    private const val EXPORT_BYTES = 64
    private const val EXPORT_LABEL = "adb-label\u0000"
    private const val AES_KEY_BYTES = 16
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val HASH_BYTES = 32
    private const val TIMEOUT_MS = 10_000
    private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()

    /**
     * True when the phone took LX's key, false when [code] was wrong. IOException when the talk broke off, or
     * on Android 13 and older, where LX Bridge does not run (EmbedClient.MIN_SDK) and the TLS export it needs
     * (API 31) may be missing.
     */
    fun pair(port: Int, code: String, keys: AdbKeys): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) throw IOException("LX Bridge needs Android 14")
        val plain = Socket()
        val socket = try {
            plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            keys.tls().createSocket(plain, "127.0.0.1", port, true) as SSLSocket
        } catch (e: Throwable) {
            // The TLS socket owns the plain one once made; until then a failure closes it here.
            plain.close()
            throw e
        }
        socket.use {
            socket.useClientMode = true
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            val exported = SSLSockets.exportKeyingMaterial(socket, EXPORT_LABEL, null, EXPORT_BYTES) ?: throw IOException("no keying material")
            val spake = Spake2(code.toByteArray() + exported, CLIENT_NAME, SERVER_NAME)
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            write(output, TYPE_SPAKE2, spake.message)
            val shared = spake.key(read(input, TYPE_SPAKE2)) ?: return false
            val cipher = PairingCipher(shared)
            write(output, TYPE_PEER_INFO, cipher.seal(peerInfo(keys)))
            return try {
                // A wrong code gives each side a different key, so the phone's answer does not open.
                cipher.open(read(input, TYPE_PEER_INFO))
                true
            } catch (e: AEADBadTagException) {
                false
            } catch (e: IOException) {
                // The phone hung up on a message it could not open.
                false
            }
        }
    }

    fun peerInfo(keys: AdbKeys): ByteArray {
        val key = keys.adbPublicKey().toByteArray()
        val info = ByteArray(PEER_INFO_BYTES)
        info[0] = KEY_RSA_PUBLIC.toByte()
        key.copyInto(info, 1)
        return info
    }

    private fun write(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(VERSION)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun read(input: DataInputStream, type: Int): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        val fields = ByteBuffer.wrap(header)
        val version = fields.get().toInt()
        val got = fields.get().toInt()
        val size = fields.getInt()
        if (version != VERSION || got != type || size !in 1..MAX_PAYLOAD) throw IOException("unexpected pairing packet $version/$got/$size")
        return ByteArray(size).also(input::readFully)
    }

    /** AES-128-GCM with HKDF's key, one running nonce for each direction. */
    class PairingCipher(shared: ByteArray) {
        private val key = SecretKeySpec(hkdf(shared), "AES")
        private var sent = 0L
        private var received = 0L

        fun seal(plain: ByteArray) = run(Cipher.ENCRYPT_MODE, sent++, plain)

        fun open(sealed: ByteArray) = run(Cipher.DECRYPT_MODE, received++, sealed)

        private fun run(mode: Int, sequence: Long, data: ByteArray): ByteArray {
            val nonce = ByteBuffer.allocate(GCM_NONCE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            return cipher.doFinal(data)
        }

        private fun hkdf(input: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(HASH_BYTES), "HmacSHA256"))
            val pseudoRandom = mac.doFinal(input)
            mac.init(SecretKeySpec(pseudoRandom, "HmacSHA256"))
            mac.update(HKDF_INFO.toByteArray())
            return mac.doFinal(byteArrayOf(1)).copyOf(AES_KEY_BYTES)
        }
    }
}
