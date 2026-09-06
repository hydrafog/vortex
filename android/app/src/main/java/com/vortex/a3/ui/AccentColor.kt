package com.vortex.a3.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

enum class AccentColor(val code: String, val label: String, val hex: Long) {
    System("system", "System", 0x00000000L),
    Vortex("vortex", "Vortex Green", 0xFF2ECC71L),
    Blue("blue", "Blue", 0xFF3584E4L),
    Teal("teal", "Teal", 0xFF21A48CL),
    Green("green", "Green", 0xFF33D17AL),
    Yellow("yellow", "Yellow", 0xFFF6D32DL),
    Orange("orange", "Orange", 0xFFFF7800L),
    Red("red", "Red", 0xFFE01B24L),
    Pink("pink", "Pink", 0xFFF04494L),
    Purple("purple", "Purple", 0xFF9141ACL),
    Slate("slate", "Slate", 0xFF737E8CL);

    val color: Color get() = Color(hex)

    fun resolveDisplayColor(context: Context, isDark: Boolean): Color {
        if (this == System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                if (isDark) dynamicDarkColorScheme(context).primary
                else dynamicLightColorScheme(context).primary
            } catch (_: Exception) {
                Color(0xFF2ECC71)
            }
        }
        return if (this == System) Color(0xFF2ECC71) else color
    }

    companion object {
        fun fromCode(c: String?): AccentColor =
            entries.firstOrNull { it.code == c } ?: System
    }
}
