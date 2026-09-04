package com.vortex.a3.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build

object ClipboardReader {
    fun isSensitive(clip: ClipData?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return clip?.description?.extras
            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }
}
