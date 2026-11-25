// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.net.ssl.SSLSockets
import com.lxlauncher.bridge.AdbKeys
import com.lxlauncher.bridge.AdbPairing
import com.lxlauncher.bridge.Spake2
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Principal
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The phone's side of wireless debugging, on the loopback address and for tests only. As a pairing server it also
 * answers the real `adb pair` on a computer (scripts/ui_test_bridge_pairing.sh), which puts LX's key exchange against
 * BoringSSL's. [fakeAdbd] answers [com.lxlauncher.bridge.AdbLink] the way adbd does.
 */
internal object BridgePeer {
    private const val ADB_DEVICE_GUID = 1
    private const val PEER_INFO_BYTES = 8192
    private const val CNXN = 0x4e584e43
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val STLS = 0x534c5453

    private fun upgrade(plain: Socket, keys: AdbKeys): SSLSocket {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("peer")
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "peer"
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "peer"
            override fun getCertificateChain(alias: String?) = arrayOf(keys.certificate)
            override fun getPrivateKey(alias: String?) = keys.privateKey
        }
        val trustAny = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustAny), null) }
        val tls = context.socketFactory.createSocket(plain, null, plain.port, true) as SSLSocket
        tls.useClientMode = false
        tls.needClientAuth = true
        tls.enabledProtocols = arrayOf("TLSv1.3")
        tls.startHandshake()
        return tls
    }

    fun listen(port: Int = 0) = ServerSocket(port, 1, InetAddress.getLoopbackAddress())

    /** One pairing as the phone runs it. Returns what the client sent as its key, or throws. */
    fun pairingServer(server: ServerSocket, code: String, keys: AdbKeys): String {
        upgrade(server.accept(), keys).use { tls ->
            val exported = SSLSockets.exportKeyingMaterial(tls, "adb-label\u0000", null, 64)!!
            val spake = Spake2(code.toByteArray() + exported, "adb pair server\u0000".toByteArray(), "adb pair client\u0000".toByteArray(), client = false)
            val input = DataInputStream(tls.inputStream)
            val output = DataOutputStream(tls.outputStream)
            val theirs = readPacket(input)
            writePacket(output, 0, spake.message)
            val cipher = AdbPairing.PairingCipher(spake.key(theirs)!!)
            val info = cipher.open(readPacket(input))
            val mine = ByteArray(PEER_INFO_BYTES)
            mine[0] = ADB_DEVICE_GUID.toByte()
            "adb-lx-test-peer".toByteArray().copyInto(mine, 1)
            writePacket(output, 1, cipher.seal(mine))
            return String(info, 1, info.size - 1).trimEnd('\u0000')
        }
    }

    private fun readPacket(input: DataInputStream): ByteArray {
        val header = ByteArray(6)
        input.readFully(header)
        return ByteArray(ByteBuffer.wrap(header).getInt(2)).also(input::readFully)
    }

    private fun writePacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    /** One connection as adbd serves it over TLS: answers `shell:<command>` with [printed]. Returns the command. */
    fun fakeAdbd(server: ServerSocket, keys: AdbKeys, printed: String): String {
        val plain = server.accept()
        val first = DataInputStream(plain.getInputStream())
        check(readMessage(first).first == CNXN) { "no CNXN" }
        writeMessage(plain.getOutputStream(), STLS, 0x01000000, 0, ByteArray(0))
        check(readMessage(first).first == STLS) { "no STLS answer" }
        upgrade(plain, keys).use { tls ->
            check(tls.session.peerCertificates.first().publicKey == keys.publicKey) { "the client showed another key" }
            val input = DataInputStream(tls.inputStream)
            val output = tls.outputStream
            writeMessage(output, CNXN, 0x01000001, 1024 * 1024, "device::\u0000".toByteArray())
            val (command, payload) = readMessage(input)
            check(command == OPEN) { "no OPEN" }
            writeMessage(output, OKAY, 7, 1, ByteArray(0))
            writeMessage(output, WRTE, 7, 1, printed.toByteArray())
            check(readMessage(input).first == OKAY) { "no OKAY for the output" }
            writeMessage(output, CLSE, 7, 1, ByteArray(0))
            check(readMessage(input).first == CLSE) { "no CLSE" }
            return String(payload).trimEnd('\u0000')
        }
    }

    private fun readMessage(input: DataInputStream): Pair<Int, ByteArray> {
        val header = ByteArray(24)
        input.readFully(header)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.getInt(0)
        check(fields.getInt(20) == command.inv()) { "bad magic" }
        return command to ByteArray(fields.getInt(12)).also(input::readFully)
    }

    private fun writeMessage(output: java.io.OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(arg0).putInt(arg1)
            .putInt(payload.size).putInt(payload.sumOf { it.toInt() and 0xff }).putInt(command.inv())
        output.write(header.array())
        output.write(payload)
        output.flush()
    }
}
