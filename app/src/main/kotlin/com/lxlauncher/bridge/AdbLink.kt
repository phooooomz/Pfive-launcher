// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/**
 * A connection to this phone's own adb daemon, for one thing: running a shell command. It only ever opens the
 * loopback address, so nothing of it leaves the phone. Over wireless debugging adbd asks for TLS at once and
 * knows LX by its key, which pairing gave it. Close it when done: adbd then sees nobody connected.
 */
internal class AdbLink(port: Int, keys: AdbKeys) : Closeable {
    private var socket: Socket
    private var input: DataInputStream
    private var output: OutputStream

    init {
        val plain = Socket()
        plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MS)
        plain.soTimeout = TIMEOUT_MS
        socket = plain
        input = DataInputStream(plain.getInputStream())
        output = plain.getOutputStream()
        try {
            send(CNXN, VERSION, MAX_PAYLOAD, HOST_BANNER.toByteArray())
            var answer = read()
            if (answer.command == STLS) {
                send(STLS, STLS_VERSION, 0, EMPTY)
                val tls = keys.tls().createSocket(plain, "127.0.0.1", port, true) as SSLSocket
                tls.useClientMode = true
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.startHandshake()
                socket = tls
                input = DataInputStream(tls.inputStream)
                output = tls.outputStream
                answer = read()
            }
            if (answer.command != CNXN) throw IOException("adbd answered ${answer.command.toString(HEX)}")
        } catch (e: Throwable) {
            // Whatever went wrong (I/O, the key store, TLS), the socket is not left open.
            close()
            throw e
        }
    }

    /**
     * Runs [command] through the phone's shell and returns what it printed, once it has ended. The commands LX runs
     * print a few lines; anything that prints past [MAX_OUTPUT] or runs past [MAX_SHELL_MS] is not adbd as LX knows
     * it (another app can announce a port on the loopback address) and ends with an IOException instead of filling
     * memory or holding LX Bridge's thread.
     */
    fun shell(command: String): String {
        send(OPEN, LOCAL_ID, 0, ("shell:$command\u0000").toByteArray())
        val printed = ByteArrayOutputStream()
        val deadline = SystemClock.uptimeMillis() + MAX_SHELL_MS
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) throw IOException("shell took longer than $MAX_SHELL_MS ms")
            val m = read()
            when (m.command) {
                OKAY -> {}
                WRTE -> {
                    if (printed.size() + m.payload.size > MAX_OUTPUT) throw IOException("shell printed more than $MAX_OUTPUT bytes")
                    printed.write(m.payload)
                    send(OKAY, LOCAL_ID, m.arg0, EMPTY)
                }
                CLSE -> {
                    send(CLSE, LOCAL_ID, m.arg0, EMPTY)
                    return printed.toString(Charsets.UTF_8.name())
                }
                else -> throw IOException("adbd answered ${m.command.toString(HEX)}")
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private class Message(val command: Int, val arg0: Int, val payload: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum(payload)).putInt(command.inv())
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    private fun read(): Message {
        val bytes = ByteArray(HEADER_BYTES)
        input.readFully(bytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.getInt()
        val arg0 = header.getInt()
        header.getInt()
        val size = header.getInt()
        header.getInt()
        val magic = header.getInt()
        if (magic != command.inv() || size !in 0..MAX_PAYLOAD) throw IOException("bad adb message")
        val payload = ByteArray(size)
        input.readFully(payload)
        return Message(command, arg0, payload)
    }

    private companion object {
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 1024 * 1024
        const val HEADER_BYTES = 24
        const val LOCAL_ID = 1
        const val TIMEOUT_MS = 10_000
        /** All a shell command may print: the commands LX runs print a few lines. */
        const val MAX_OUTPUT = 256 * 1024
        /** How long a shell command may take in all, apart from the time limit of each read. */
        const val MAX_SHELL_MS = 30_000L
        const val HEX = 16
        const val BYTE_MASK = 0xff
        const val HOST_BANNER = "host::\u0000"
        val EMPTY = ByteArray(0)

        fun checksum(payload: ByteArray) = payload.sumOf { it.toInt() and BYTE_MASK }
    }
}
