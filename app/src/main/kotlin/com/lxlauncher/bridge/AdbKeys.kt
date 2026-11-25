// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The key that identifies LX to the phone's adb daemon. adbd wants a plain RSA 2048 key, and TLS signs with it
 * through the platform's stack, so it is an ordinary software key; its private part is stored in LX's private
 * files, sealed with an AES key that stays in the Android keystore.
 */
internal class AdbKeys private constructor(val privateKey: PrivateKey, val publicKey: RSAPublicKey) {
    val certificate: X509Certificate by lazy { selfSigned() }

    /** The key as adbd stores it: Android's mincrypt form in Base64, then a name. */
    fun adbPublicKey(): String {
        val n = publicKey.modulus
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.modInverse(two32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val out = ByteBuffer.allocate(MINCRYPT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(KEY_WORDS).putInt(n0inv)
        words(n).forEach { out.putInt(it) }
        words(rr).forEach { out.putInt(it) }
        out.putInt(publicKey.publicExponent.toInt())
        return Base64.encodeToString(out.array(), Base64.NO_WRAP) + " lx-launcher@lx-bridge"
    }

    private fun words(value: BigInteger): IntArray {
        val mask = BigInteger.valueOf(0xffffffffL)
        return IntArray(KEY_WORDS) { value.shiftRight(32 * it).and(mask).toLong().toInt() }
    }

    /**
     * A TLS context that shows this key and takes any peer, as adb does: adbd's certificate is self-signed and made
     * at each boot, so there is nothing to check it against. Only adbd knows this key, and pairing goes by its code.
     */
    fun tls(): SSLSocketFactory = factory

    /** Made once per key: the certificate is signed and the TLS context built on first use, not for every port tried. */
    private val factory: SSLSocketFactory by lazy { buildTls() }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun buildTls(): SSLSocketFactory {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = privateKey
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), arrayOf(trustAll), null) }.socketFactory
    }

    /** A bare self-signed X.509 certificate, DER by hand: Android has no public API to make one. */
    private fun selfSigned(): X509Certificate {
        val algorithm = der(0x30, der(0x06, Base64.decode(SHA256_RSA_OID, Base64.DEFAULT)) + der(0x05, ByteArray(0)))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0c, "LX Bridge".toByteArray()))))
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray()) + der(0x18, "20500101000000Z".toByteArray()))
        val tbs = der(0x30, der(0xa0, der(0x02, byteArrayOf(2))) + der(0x02, byteArrayOf(1)) + algorithm + name + validity + name + publicKey.encoded)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbs)
        }.sign()
        val cert = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert)) as X509Certificate
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            body.size < 0x80 -> out.write(body.size)
            body.size < 0x100 -> {
                out.write(0x81)
                out.write(body.size)
            }
            else -> {
                out.write(0x82)
                out.write(body.size ushr 8)
                out.write(body.size and 0xff)
            }
        }
        out.write(body)
        return out.toByteArray()
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val KEY_WORDS = KEY_BITS / 32
        private const val MINCRYPT_SIZE = 524
        private const val ALIAS = "lx-bridge"
        private const val SEAL_ALIAS = "lx-bridge-seal"
        private const val KEY_FILE = "lx-bridge-key"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        /** 1.2.840.113549.1.1.11, sha256WithRSAEncryption. */
        private const val SHA256_RSA_OID = "KoZIhvcNAQEL"

        @Volatile private var loaded: AdbKeys? = null

        /**
         * The stored key, made on first use. Kept for the process: each port LX tries used to open the key store,
         * unseal the key and build it again. A new key is written to a temporary file and renamed into place, so a
         * write cut short never leaves a damaged key (which meant pairing again); a key that cannot be read is kept
         * aside as lx-bridge-key.unreadable instead of being overwritten.
         */
        fun get(context: Context): AdbKeys {
            loaded?.let { return it }
            synchronized(this) {
                loaded?.let { return it }
                val file = File(context.filesDir, KEY_FILE)
                val stored = if (file.exists()) runCatching { unseal(file.readBytes()) }.getOrNull() else null
                val keys = if (stored != null) fromPkcs8(stored) else {
                    if (file.exists()) file.renameTo(File(context.filesDir, "$KEY_FILE.unreadable"))
                    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
                    val tmp = File(context.filesDir, "$KEY_FILE.tmp")
                    java.io.FileOutputStream(tmp).use { out ->
                        out.write(seal(pair.private.encoded))
                        out.fd.sync()
                    }
                    if (!tmp.renameTo(file)) throw java.io.IOException("could not store the LX Bridge key")
                    AdbKeys(pair.private, pair.public as RSAPublicKey)
                }
                loaded = keys
                return keys
            }
        }

        private fun fromPkcs8(bytes: ByteArray): AdbKeys {
            val factory = KeyFactory.getInstance("RSA")
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(bytes))
            val crt = private as java.security.interfaces.RSAPrivateCrtKey
            return AdbKeys(private, factory.generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey)
        }

        private fun sealKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(SEAL_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(SEAL_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }

        private fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sealKey()) }
            return cipher.iv + cipher.doFinal(plain)
        }

        private fun unseal(data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, sealKey(), GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_BYTES))
            return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        }
    }
}
