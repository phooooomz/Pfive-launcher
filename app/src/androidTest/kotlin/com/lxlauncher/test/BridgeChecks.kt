// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.view.accessibility.AccessibilityManager
import com.lxlauncher.bridge.AdbKeys
import com.lxlauncher.bridge.AdbLink
import com.lxlauncher.bridge.AdbPairing
import com.lxlauncher.bridge.LxBridge
import com.lxlauncher.bridge.SetupLabels
import com.lxlauncher.bridge.Spake2
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

/**
 * The parts of LX Bridge's pairing that run without a phone's adbd: both ends of the key exchange against each
 * other, the cipher, and the key formats adbd reads. scripts/ui_test_bridge_pairing.sh pairs with the real client.
 */
internal fun bridgeChecks(ctx: Context, check: (String, () -> String?) -> Unit) {
    val client = "adb pair client\u0000".toByteArray()
    val server = "adb pair server\u0000".toByteArray()

    check("both ends of the pairing exchange reach the same key with the same code") {
        val code = "123456".toByteArray() + ByteArray(64) { it.toByte() }
        val alice = Spake2(code, client, server)
        val bob = Spake2(code, server, client, client = false)
        val mine = alice.key(bob.message)
        val theirs = bob.key(alice.message)
        when {
            mine == null || theirs == null -> "a real message did not decode"
            mine.size != 64 -> "key of ${mine.size} bytes"
            !mine.contentEquals(theirs) -> "the two ends got different keys"
            alice.message.contentEquals(Spake2(code, client, server).message) -> "two runs sent the same message"
            else -> null
        }
    }

    check("a wrong pairing code gives a different key") {
        val alice = Spake2("123456".toByteArray(), client, server)
        val bob = Spake2("123457".toByteArray(), server, client, client = false)
        if (alice.key(bob.message).contentEquals(bob.key(alice.message))) "a wrong code reached the same key" else null
    }

    check("a pairing message that is no point on the curve is turned down") {
        val alice = Spake2("123456".toByteArray(), client, server)
        val offCurve = ByteArray(32).also { it[0] = 2 }
        when {
            alice.key(ByteArray(31)) != null -> "a short message was taken"
            alice.key(offCurve) != null -> "a point off the curve was taken"
            else -> null
        }
    }

    check("pairing messages open only with the right key, in order, unaltered") {
        val key = ByteArray(64) { 7 }
        val mine = AdbPairing.PairingCipher(key)
        val theirs = AdbPairing.PairingCipher(key)
        val first = mine.seal("first".toByteArray())
        val second = mine.seal("second".toByteArray())
        val tampered = second.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val refused = { block: () -> Unit -> runCatching(block).exceptionOrNull() is AEADBadTagException }
        when {
            first.size != "first".length + 16 -> "sealed size ${first.size}"
            !refused { AdbPairing.PairingCipher(ByteArray(64) { 8 }).open(first) } -> "another key opened it"
            String(theirs.open(first)) != "first" -> "first message did not open"
            !refused { theirs.open(first) } -> "the same message opened twice"
            !refused { AdbPairing.PairingCipher(key).open(tampered) } -> "an altered message opened"
            else -> null
        }
    }

    check("LX's adb key has the format adbd reads, and stays the same") {
        val keys = AdbKeys.get(ctx)
        val text = keys.adbPublicKey()
        val parts = text.split(" ")
        val raw = Base64.decode(parts[0], Base64.DEFAULT)
        val fields = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val words = fields.getInt()
        val n0inv = fields.getInt()
        val n = ByteArray(256).also { fields.get(it) }
        val modulus = BigInteger(1, n.reversedArray())
        val rr = BigInteger(1, ByteArray(256).also { fields.get(it) }.reversedArray())
        val exponent = fields.getInt()
        val info = AdbPairing.peerInfo(keys)
        val two32 = BigInteger.ONE.shiftLeft(32)
        when {
            raw.size != 524 -> "public key of ${raw.size} bytes"
            words != 64 -> "$words words"
            modulus != keys.publicKey.modulus -> "modulus differs"
            exponent != 65537 -> "exponent $exponent"
            modulus.multiply(BigInteger.valueOf(n0inv.toLong() and 0xffffffffL)).add(BigInteger.ONE).mod(two32).signum() != 0 -> "n0inv is wrong"
            rr != BigInteger.ONE.shiftLeft(4096).mod(modulus) -> "rr is wrong"
            parts.size != 2 -> "no name after the key"
            info.size != 8192 || info[0].toInt() != 0 -> "peer info of ${info.size} bytes, type ${info[0]}"
            String(info, 1, text.length) != text || info[text.length + 1].toInt() != 0 -> "peer info does not hold the key"
            !AdbKeys.get(ctx).publicKey.modulus.equals(modulus) -> "the stored key changed between reads"
            else -> null
        }
    }

    check("LX pairs with a phone's pairing service and hands over its key") {
        val keys = AdbKeys.get(ctx)
        BridgePeer.listen().use { server ->
            var taken: Result<String>? = null
            val peer = thread { taken = runCatching { BridgePeer.pairingServer(server, "482915", keys) } }
            val paired = AdbPairing.pair(server.localPort, "482915", keys)
            peer.join()
            when {
                !paired -> "the right code was turned down"
                taken?.getOrNull() != keys.adbPublicKey() -> "the peer got ${taken?.exceptionOrNull() ?: taken?.getOrNull()?.take(40)}"
                else -> null
            }
        }
    }

    check("a wrong code fails the pairing without an error for the user to read") {
        val keys = AdbKeys.get(ctx)
        BridgePeer.listen().use { server ->
            val peer = thread { runCatching { BridgePeer.pairingServer(server, "482915", keys) } }
            val paired = AdbPairing.pair(server.localPort, "111111", keys)
            server.close()
            peer.join()
            if (paired) "a wrong code paired" else null
        }
    }

    check("the adb link upgrades to TLS with LX's key and reads a shell command's output") {
        val keys = AdbKeys.get(ctx)
        BridgePeer.listen().use { server ->
            var opened: Result<String>? = null
            val peer = thread { opened = runCatching { BridgePeer.fakeAdbd(server, keys, "Success\n") } }
            val printed = AdbLink(server.localPort, keys).use { it.shell("pm grant x y") }
            peer.join()
            when {
                opened?.getOrNull() != "shell:pm grant x y" -> "adbd saw ${opened?.exceptionOrNull() ?: opened?.getOrNull()}"
                printed != "Success\n" -> "printed [$printed]"
                else -> null
            }
        }
    }

    check("the words of the automatic setup come from this phone's own Settings and System UI") {
        val missing = SetupLabels(ctx).missing()
        if (missing.isEmpty()) null else "missing: $missing"
    }

    check("the automatic setup can read wireless debugging's state without any extra right") {
        Settings.Global.getInt(ctx.contentResolver, LxBridge.WIRELESS_DEBUGGING, -1)
        null
    }

    check("the automatic setup is the only accessibility service, off by default and kept to Settings") {
        val manager = ctx.getSystemService(AccessibilityManager::class.java)
        val ours = manager.installedAccessibilityServiceList.filter { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
        val info = ours.singleOrNull()
        when {
            info == null -> "${ours.size} services"
            info.packageNames?.toSet() != setOf("com.android.settings", "com.android.systemui") -> "sees ${info.packageNames?.toList()}"
            else -> null
        }
    }

    check("LX's TLS certificate carries its adb key and verifies") {
        val keys = AdbKeys.get(ctx)
        val certificate = keys.certificate
        certificate.verify(keys.publicKey)
        when {
            certificate.publicKey != keys.publicKey -> "the certificate holds another key"
            certificate.sigAlgName != "SHA256withRSA" -> certificate.sigAlgName
            certificate.notAfter.before(java.util.Date()) -> "the certificate has expired"
            else -> null
        }
    }
}
