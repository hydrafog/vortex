package com.vortex.a3.core.notif

import android.content.Context

object MirroredKeysStore {
    private const val PREFS = "vortex_notif_mirrored"
    private const val FIELD = "keys"
    private const val MAX = 300

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Set<String> =
        try {
            prefs(ctx).getStringSet(FIELD, emptySet())?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }

    fun save(ctx: Context, keys: Set<String>) {
        val bounded = if (keys.size > MAX) keys.take(MAX).toSet() else keys
        try {
            prefs(ctx).edit().putStringSet(FIELD, bounded).apply()
        } catch (_: Throwable) {
        }
    }
}
