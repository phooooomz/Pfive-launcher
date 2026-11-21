// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.system.ErrnoException
import android.system.OsConstants
import com.lxlauncher.files.FsException.Reason
import java.io.FileNotFoundException
import java.io.IOException

/** Turns errno values, I/O errors and provider exceptions into [FsException] reasons. */
internal object FsErrors {
    fun errno(errno: Int): Reason = when (errno) {
        OsConstants.ENOENT, OsConstants.ENOTDIR -> Reason.NOT_FOUND
        OsConstants.EEXIST, OsConstants.ENOTEMPTY -> Reason.EXISTS
        OsConstants.EACCES, OsConstants.EPERM -> Reason.NO_ACCESS
        OsConstants.EROFS -> Reason.READ_ONLY
        OsConstants.ENOSPC, OsConstants.EDQUOT, OsConstants.EFBIG -> Reason.NO_SPACE
        OsConstants.ENAMETOOLONG, OsConstants.EINVAL, OsConstants.EILSEQ -> Reason.BAD_NAME
        OsConstants.ENODEV, OsConstants.ENXIO, OsConstants.ENOTCONN -> Reason.GONE
        OsConstants.ELOOP -> Reason.TOO_DEEP
        else -> Reason.IO
    }

    fun of(e: ErrnoException, path: String): FsException = FsException(errno(e.errno), path, e)

    /**
     * Maps anything a backend or a stream threw. An errno found in the cause chain wins; the
     * message check covers streams that only keep the text, such as "ENOSPC (No space left on device)".
     */
    fun of(t: Throwable, path: String): FsException {
        if (t is FsException) return t
        var c: Throwable? = t
        while (c != null) {
            if (c is ErrnoException) return FsException(errno(c.errno), path, t)
            c = c.cause
        }
        val msg = t.message.orEmpty()
        val reason = when {
            "ENOSPC" in msg || "No space left" in msg -> Reason.NO_SPACE
            "EROFS" in msg || "Read-only file system" in msg -> Reason.READ_ONLY
            t is SecurityException || "EACCES" in msg || "Permission denied" in msg -> Reason.NO_ACCESS
            t is FileNotFoundException || "ENOENT" in msg -> Reason.NOT_FOUND
            t is UnsupportedOperationException -> Reason.UNSUPPORTED
            else -> Reason.IO
        }
        return FsException(reason, path, t)
    }

    fun io(message: String, path: String): FsException = FsException(Reason.IO, path, IOException(message))
}
