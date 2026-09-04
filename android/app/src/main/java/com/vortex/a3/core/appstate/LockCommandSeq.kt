package com.vortex.a3.core.appstate

import android.content.Context

object LockCommandSeq {
    private const val PREFS = "vortex_lock"
    private const val KEY_SEQ = "seq"

    fun next(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getLong(KEY_SEQ, 0L) + 1L
        p.edit().putLong(KEY_SEQ, n).apply()
        return n
    }
}
