// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The client side of SPAKE2 over edwards25519, byte for byte what BoringSSL does, because Android's wireless
 * debugging pairing runs on BoringSSL. Android offers no curve arithmetic to apps, so this does it with
 * BigInteger. It runs once, for a pairing, so speed does not matter. The secret scalars here live for a
 * single pairing over an encrypted local connection, so it does not need to run in constant time.
 */
internal class Spake2(password: ByteArray, private val myName: ByteArray, private val theirName: ByteArray, private val client: Boolean = true,
                      random: SecureRandom = SecureRandom()) {
    private val passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
    private val privateKey: BigInteger
    private val passwordScalar: BigInteger

    /** The message to send: this side's public point, masked with the password. */
    val message: ByteArray

    init {
        val bytes = ByteArray(64).also(random::nextBytes)
        // Multiplied by the cofactor, which clears it when working on the peer's point.
        privateKey = littleEndian(bytes).mod(ORDER).shiftLeft(3)
        passwordScalar = cofactorFree(littleEndian(passwordHash).mod(ORDER))
        message = encode(add(multiply(BASE, privateKey), multiply(if (client) POINT_M else POINT_N, passwordScalar)))
    }

    /** The 64 byte key from the peer's [message], or null when it is not a point on the curve. */
    fun key(theirMessage: ByteArray): ByteArray? {
        val masked = decode(theirMessage) ?: return null
        val peer = add(masked, negate(multiply(if (client) POINT_N else POINT_M, passwordScalar)))
        val shared = encode(multiply(peer, privateKey))
        val sha = MessageDigest.getInstance("SHA-512")
        // Both sides hash the client's parts first.
        val parts = if (client) listOf(myName, theirName, message, theirMessage) else listOf(theirName, myName, theirMessage, message)
        for (part in parts + listOf(shared, passwordHash)) {
            sha.update(lengthPrefix(part.size))
            sha.update(part)
        }
        return sha.digest()
    }

    /** A point in extended coordinates: x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    companion object {
        private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        /** The order of the prime subgroup: 2^252 + 27742317777372353535851937790883648493. */
        private val ORDER = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val TWO = BigInteger.valueOf(2)
        private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1 = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        private val BASE = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!
        /** BoringSSL's fixed points, from its spake25519 source. */
        private val POINT_M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        private val POINT_N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

        /**
         * The password scalar as BoringSSL keeps it: reduced, then raised by multiples of the group order to a
         * multiple of eight, which leaves it the same scalar on the prime subgroup but clears the cofactor.
         */
        private fun cofactorFree(scalar: BigInteger): BigInteger {
            var s = scalar
            for (bit in 0..2) if (s.testBit(bit)) s = s.add(ORDER.shiftLeft(bit))
            return s
        }

        private fun lengthPrefix(length: Int) = ByteArray(8) { i -> if (i < 4) (length ushr (8 * i)).toByte() else 0 }

        private fun negate(p: Point) = Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

        private fun add(p: Point, q: Point): Point {
            val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
            val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
            val c = TWO.multiply(p.t).multiply(q.t).multiply(D).mod(P)
            val d = TWO.multiply(p.z).multiply(q.z).mod(P)
            val e = b.subtract(a)
            val f = d.subtract(c)
            val g = d.add(c)
            val h = b.add(a)
            return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
        }

        private fun multiply(point: Point, scalar: BigInteger): Point {
            var result = IDENTITY
            for (i in scalar.bitLength() - 1 downTo 0) {
                result = add(result, result)
                if (scalar.testBit(i)) result = add(result, point)
            }
            return result
        }

        private fun encode(p: Point): ByteArray {
            val inverse = p.z.modInverse(P)
            val x = p.x.multiply(inverse).mod(P)
            val y = p.y.multiply(inverse).mod(P)
            val big = y.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            val out = ByteArray(32)
            for (i in big.indices) out[big.size - 1 - i] = big[i]
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        /** RFC 8032 point decoding. Null when the bytes are no point on the curve. */
        private fun decode(bytes: ByteArray): Point? {
            if (bytes.size != 32) return null
            val sign = (bytes[31].toInt() and 0x80) != 0
            val y = littleEndian(bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
            if (y >= P) return null
            val yy = y.multiply(y).mod(P)
            val u = yy.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
            val xx = u.multiply(v.modInverse(P)).mod(P)
            var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).mod(P) != xx) x = x.multiply(SQRT_M1).mod(P)
            if (x.multiply(x).mod(P) != xx) return null
            if (x.signum() == 0 && sign) return null
            if (x.testBit(0) != sign) x = P.subtract(x)
            return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
        }
    }
}
