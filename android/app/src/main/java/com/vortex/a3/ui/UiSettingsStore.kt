package com.vortex.a3.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val code: String) {
    Dark("dark"), Light("light"), Oled("oled");
    companion object {
        fun fromCode(c: String?): ThemeMode = when (c) {
            "light" -> Light
            "oled" -> Oled
            else -> Dark
        }
    }
}

class UiSettingsStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("vortex_ui_settings", Context.MODE_PRIVATE)

    private val _locale = MutableStateFlow(VortexLocale.English)
    val locale: StateFlow<VortexLocale> = _locale.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.Dark)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _accent = MutableStateFlow(AccentColor.Vortex)
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    private val _calendarBackend = MutableStateFlow("local")
    val calendarBackend: StateFlow<String> = _calendarBackend.asStateFlow()

    fun load() {
        val code = prefs.getString("locale", null)
        _locale.value = when {
            code != null -> VortexLocale.fromCode(code)
            else -> {
                val sys = java.util.Locale.getDefault().language.lowercase()
                when {
                    sys.startsWith("uz") -> VortexLocale.Uzbek
                    sys.startsWith("ru") -> VortexLocale.Russian
                    else -> VortexLocale.English
                }
            }
        }
        _theme.value = ThemeMode.fromCode(prefs.getString("theme", null))
        val savedAccent = prefs.getString("accent", null)
        _accent.value = when (savedAccent) {
            null, "cyan", "vortex" -> AccentColor.Vortex
            else -> AccentColor.fromCode(savedAccent)
        }
        _calendarBackend.value = prefs.getString("calendarBackend", "local")?.takeIf {
            it == "local" || it == "ricelin"
        } ?: "local"
    }

    fun setLocale(loc: VortexLocale) {
        prefs.edit()
            .putString("locale", loc.code)
            .putLong("locale_changed_at", nowSec())
            .apply()
        _locale.value = loc
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit()
            .putString("theme", mode.code)
            .putLong("theme_changed_at", nowSec())
            .apply()
        _theme.value = mode
    }

    fun setAccent(acc: AccentColor) {
        prefs.edit()
            .putString("accent", acc.code)
            .putLong("accent_changed_at", nowSec())
            .apply()
        _accent.value = acc
    }

    fun setCalendarBackend(backend: String) {
        val clean = if (backend == "ricelin") "ricelin" else "local"
        prefs.edit()
            .putString("calendarBackend", clean)
            .putLong("calendarBackend_changed_at", nowSec())
            .apply()
        _calendarBackend.value = clean
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L
}
