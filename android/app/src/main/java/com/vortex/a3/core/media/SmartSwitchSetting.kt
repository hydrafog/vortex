package com.vortex.a3.core.media

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SmartSwitchSetting {
    private const val PREFS = "vortex_ui_settings"
    private const val K_ENABLED = "smart_switch_enabled"
    private const val K_TS = "smart_switch_changed_at"

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(true)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var changedAt: Long = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(K_ENABLED, true)
        changedAt = p.getLong(K_TS, 0L)
    }

    fun isEnabled(): Boolean = _enabled.value
    fun changedAt(): Long = changedAt

    fun setLocal(enabled: Boolean) {
        val now = System.currentTimeMillis() / 1000L
        val ts = maxOf(now, changedAt + 1)
        persist(enabled, ts)
    }

    fun applyFromPeer(enabled: Boolean, ts: Long): Boolean {
        if (ts <= changedAt) return false
        persist(enabled, ts)
        return true
    }

    private fun persist(enabled: Boolean, ts: Long) {
        changedAt = ts
        _enabled.value = enabled
        prefs?.edit()
            ?.putBoolean(K_ENABLED, enabled)
            ?.putLong(K_TS, ts)
            ?.apply()
    }
}
