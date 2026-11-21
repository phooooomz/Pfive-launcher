// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import com.lxlauncher.BuildConfig
import java.security.SecureRandom

/** What LX and lxdb, its daemon, agree on: how it starts, and who may hand LX a binder. */
internal object ShellHelper {
    const val METHOD_ATTACH = "attach"
    const val KEY_TOKEN = "token"
    const val KEY_SERVICE = "service"
    const val KEY_LX = "lx"
    const val KEY_APK = "apk"
    private const val ROOT_UID = 0
    /** Process.SHELL_UID, public only from Android 10. */
    private const val SHELL_UID = 2000
    private const val TOKEN_BYTES = 16
    private const val LINKER = "/system/bin/linker64"
    private const val LIBRARY = "liblxdb.so"

    private val random = SecureRandom()

    /** The authority of [EmbedBinderProvider], as the manifest builds it. */
    fun authority() = BuildConfig.APPLICATION_ID + ".embed"

    /** adbd runs the daemon as the shell; a debug build of Android runs it as root. */
    fun trustedCaller(uid: Int) = uid == ROOT_UID || uid == SHELL_UID

    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isToken(s: String) = s.length == TOKEN_BYTES * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Where the system linker finds the daemon's code: the copy Android unpacked into LX's native library folder
     * ([nativeDir]), or else the library inside the APK.
     *
     * Tedit's Linux runtime runs programs from that folder, so LX asks Android to unpack its libraries there, and with
     * that request the build stores every library compressed in the APK. The linker cannot open a compressed library
     * in place ("unable to open file .../base.apk!/lib/..."): from 12 November 2026 every start of lxdb failed that
     * way, and only a daemon left running from an older LX kept app windows working on the test phone.
     */
    fun library(apk: String, abi: String, nativeDir: String?): String {
        val unpacked = nativeDir?.let { java.io.File(it, LIBRARY) }
        return if (unpacked != null && unpacked.isFile) unpacked.path else "$apk!/lib/$abi/$LIBRARY"
    }

    /**
     * The shell line that starts the daemon from [library] (see [library]); nothing is copied anywhere. [apk] goes along
     * so the daemon can name the LX it was started for. The daemon leaves the line behind by itself, so it goes on after
     * adb hangs up.
     */
    fun command(apk: String, library: String, token: String, userId: Int): String {
        require('\'' !in apk && '\'' !in library) { "unexpected APK or library path" }
        return "$LINKER '$library' daemon $token $userId ${BuildConfig.APPLICATION_ID} '$apk'"
    }
}
