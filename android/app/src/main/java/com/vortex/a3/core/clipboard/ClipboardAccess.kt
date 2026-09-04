package com.vortex.a3.core.clipboard

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

object ClipboardAccess {
    fun isBackgroundReadGranted(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            "android:read_clipboard",
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}
