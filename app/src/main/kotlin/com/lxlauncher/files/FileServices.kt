// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.Context

/** One storage catalogue and one job engine per process, shared by every File Manager window. */
object FileServices {
    /** The same catalogue the job engine uses, so its journal cleanup and the windows agree. */
    fun storage(context: Context): StorageCatalog = AndroidStorage.get(context.applicationContext)

    fun jobs(context: Context): JobEngine {
        FileJobs.init(context.applicationContext)
        return FileJobs
    }
}
