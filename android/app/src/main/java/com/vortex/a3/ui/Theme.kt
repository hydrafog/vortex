package com.vortex.a3.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// NOTE: Flat design tokens. Depth comes from solid lightness steps on the
// NOTE: surface-container ladder, never from translucency or shadows.

// NOTE: Tinted container derived opaque: preserves rich accent vibrancy
// NOTE: matching switch active states throughout cards and interactive surfaces.
private fun softContainer(accent: Color, canvas: Color): Color = lerp(accent, canvas, 0.35f)

// NOTE: Dynamic accent contrast. Bright accents get near-black text,
// NOTE: dark or saturated accents get white (WCAG relative luminance).
fun onAccentFor(accent: Color): Color {
    val r = accent.red.toDouble()
    val g = accent.green.toDouble()
    val b = accent.blue.toDouble()
    fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    val luminance = 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    return if (luminance > 0.35) Color(0xFF1C1B1F) else Color.White
}

private val BrandEmerald = Color(0xFF2ECC71)
private val BrandOnEmerald = Color(0xFFFFFFFF)
private val BrandRed = Color(0xFFEF4444)

private fun buildScheme(
    themeMode: ThemeMode,
    primary: Color,
): ColorScheme {
    val onPrimary = onAccentFor(primary)

    return when (themeMode) {
        ThemeMode.Oled -> {
            val canvas = Color(0xFF000000)
            val canvasSurface = Color(0xFF101013)
            darkColorScheme(
                background = canvas,
                onBackground = Color(0xFFF4F4F5),
                surface = canvasSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = Color(0xFF1A1A1E),
                onSurfaceVariant = Color(0xFFA1A1AA),
                surfaceDim = Color(0xFF000000),
                surfaceBright = Color(0xFF2A2A30),
                surfaceContainerLowest = Color(0xFF0A0A0C),
                surfaceContainerLow = Color(0xFF101013),
                surfaceContainer = Color(0xFF141417),
                surfaceContainerHigh = Color(0xFF1A1A1E),
                surfaceContainerHighest = Color(0xFF222226),
                outline = Color(0xFF27272A),
                outlineVariant = Color(0xFF1E1E22),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, canvas),
                onPrimaryContainer = onAccentFor(softContainer(primary, canvas)),
                secondary = primary,
                onSecondary = onPrimary,
                error = BrandRed,
                onError = BrandOnEmerald,
                tertiary = Color(0xFFFBBF24),
            )
        }
        ThemeMode.Dark -> {
            val canvas = Color(0xFF141416)
            val canvasSurface = Color(0xFF1C1C1F)
            darkColorScheme(
                background = canvas,
                onBackground = Color(0xFFF4F4F5),
                surface = canvasSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = Color(0xFF242428),
                onSurfaceVariant = Color(0xFFA1A1AA),
                surfaceDim = Color(0xFF101013),
                surfaceBright = Color(0xFF2E2E34),
                surfaceContainerLowest = Color(0xFF121214),
                surfaceContainerLow = Color(0xFF18181B),
                surfaceContainer = canvasSurface,
                surfaceContainerHigh = Color(0xFF232328),
                surfaceContainerHighest = Color(0xFF2B2B30),
                outline = Color(0xFF2E2E33),
                outlineVariant = Color(0xFF242429),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, canvas),
                onPrimaryContainer = onAccentFor(softContainer(primary, canvas)),
                secondary = primary,
                onSecondary = onPrimary,
                error = BrandRed,
                onError = BrandOnEmerald,
                tertiary = Color(0xFFFBBF24),
            )
        }
        ThemeMode.Light -> {
            val canvas = Color(0xFFF8F9FA)
            val canvasSurface = Color(0xFFFFFFFF)
            lightColorScheme(
                background = canvas,
                onBackground = Color(0xFF18181B),
                surface = canvasSurface,
                onSurface = Color(0xFF18181B),
                surfaceVariant = Color(0xFFEBEDF0),
                onSurfaceVariant = Color(0xFF52525B),
                surfaceDim = Color(0xFFE0E2E7),
                surfaceBright = Color(0xFFFFFFFF),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF2F3F5),
                surfaceContainer = Color(0xFFECEEF2),
                surfaceContainerHigh = Color(0xFFE2E4E8),
                surfaceContainerHighest = Color(0xFFD8DBE0),
                outline = Color(0xFFE4E4E7),
                outlineVariant = Color(0xFFE7E7EB),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, Color(0xFFFFFFFF)),
                onPrimaryContainer = onAccentFor(softContainer(primary, Color(0xFFFFFFFF))),
                secondary = primary,
                onSecondary = onPrimary,
                error = BrandRed,
                onError = Color.White,
                tertiary = Color(0xFFD97706),
            )
        }
    }
}

fun buildVortexColorScheme(
    themeMode: ThemeMode,
    accent: AccentColor,
    context: Context,
): ColorScheme {
    val isDark = themeMode != ThemeMode.Light

    if (accent == AccentColor.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            buildScheme(themeMode, dynamicScheme.primary)
        } catch (_: Exception) {
            buildScheme(themeMode, BrandEmerald)
        }
    }

    val primary = if (accent == AccentColor.System) BrandEmerald else accent.color
    return buildScheme(themeMode, primary)
}

internal val VortexOledColors = buildScheme(ThemeMode.Oled, BrandEmerald)
internal val VortexDarkColors = buildScheme(ThemeMode.Dark, BrandEmerald)
internal val VortexLightColors = buildScheme(ThemeMode.Light, BrandEmerald)
