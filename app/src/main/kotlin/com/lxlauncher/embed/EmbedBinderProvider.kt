// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * Where lxdb, LX's daemon, hands over its binder. The manifest guards it with INTERACT_ACROSS_USERS_FULL,
 * which only the system and the shell hold, and call("attach") answers root and the shell only, with the
 * token of the daemon LX is waiting for (LxBridge.attach). It holds no data:
 * everything else answers nothing, and openFile keeps ContentProvider's refusal.
 */
class EmbedBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (method != ShellHelper.METHOD_ATTACH || extras == null || !ShellHelper.trustedCaller(uid)) return null
        // lxdb comes back to a new LX process, which then learns of it here.
        val client = EmbedClient.get(context ?: return null)
        return try {
            client.helperAttach(uid, extras.getString(ShellHelper.KEY_TOKEN), extras.getBinder(ShellHelper.KEY_SERVICE),
                extras.getString(ShellHelper.KEY_APK))
        } catch (e: RuntimeException) {
            // A Bundle LX cannot read throws while it unparcels.
            Log.w(TAG, "attach from uid $uid refused", e)
            null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "LxEmbed"
    }
}
