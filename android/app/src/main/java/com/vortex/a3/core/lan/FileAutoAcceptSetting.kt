package com.vortex.a3.core.lan

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FileAutoAcceptSetting {
    private const val PREFS = "vortex_ui_settings"
    private const val KEY = "file_auto_accept"

    private var prefs: SharedPreferences? = null
    private val _enabled = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs?.edit()?.putBoolean(KEY, enabled)?.apply()
    }
}
