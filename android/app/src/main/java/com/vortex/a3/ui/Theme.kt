package com.vortex.a3.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val BrandEmerald = Color(0xFF2ECC71)
private val BrandOnEmerald = Color(0xFFFFFFFF)
private val BrandRed = Color(0xFFEF4444)

fun buildVortexColorScheme(
    themeMode: ThemeMode,
    accent: AccentColor,
    context: Context,
): ColorScheme {
    val isDark = themeMode == ThemeMode.Dark

    if (accent == AccentColor.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } catch (_: Exception) {
            if (isDark) VortexDarkColors else VortexLightColors
        }
    }

    val primary = if (accent == AccentColor.System) BrandEmerald else accent.color
    val onPrimary = Color.White

    return if (isDark) {
        darkColorScheme(
            background = Color(0xFF18181B),
            onBackground = Color(0xFFFAFAFA),
            surface = Color(0xFF1C1C1F),
            onSurface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFF1C1C1F),
            onSurfaceVariant = Color(0xFF8A8A8E),
            outline = Color(0xFF27272A),
            primary = primary,
            onPrimary = onPrimary,
            secondary = primary,
            onSecondary = onPrimary,
            error = BrandRed,
            onError = onPrimary,
            tertiary = Color(0xFFFBBF24),
        )
    } else {
        lightColorScheme(
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF18181B),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF18181B),
            surfaceVariant = Color(0xFFFFFFFF),
            onSurfaceVariant = Color(0xFF67676E),
            outline = Color(0xFFE4E4E7),
            primary = primary,
            onPrimary = onPrimary,
            secondary = primary,
            onSecondary = onPrimary,
            error = BrandRed,
            onError = onPrimary,
            tertiary = Color(0xFFD97706),
        )
    }
}

internal val VortexDarkColors = darkColorScheme(
    background = Color(0xFF18181B),
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF1C1C1F),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFF8A8A8E),
    outline = Color(0xFF27272A),
    primary = BrandEmerald,
    onPrimary = BrandOnEmerald,
    secondary = BrandEmerald,
    onSecondary = BrandOnEmerald,
    error = BrandRed,
    onError = BrandOnEmerald,
    tertiary = Color(0xFFFBBF24),
)

internal val VortexLightColors = lightColorScheme(
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF67676E),
    outline = Color(0xFFE4E4E7),
    primary = BrandEmerald,
    onPrimary = BrandOnEmerald,
    secondary = BrandEmerald,
    onSecondary = BrandOnEmerald,
    error = BrandRed,
    onError = BrandOnEmerald,
    tertiary = Color(0xFFD97706),
)
