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

// NOTE: Tinted soft container derived opaque: accent pulled toward the
// NOTE: canvas so icon tiles and selected pills stay solid per accent.
private fun softContainer(accent: Color, canvas: Color): Color = lerp(accent, canvas, 0.82f)

// NOTE: Tinted surface helper: pulls neutral lightness levels toward the
// NOTE: active accent to achieve chromatic harmony across all backgrounds.
private fun tintedSurface(base: Color, accent: Color, amount: Float): Color = lerp(base, accent, amount)

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
    val isLight = themeMode == ThemeMode.Light
    val isOled = themeMode == ThemeMode.Oled
    val onPrimary = onAccentFor(primary)

    return when {
        isOled -> {
            val canvas = tintedSurface(Color(0xFF000000), primary, 0.08f)
            val canvasSurface = tintedSurface(Color(0xFF101013), primary, 0.14f)
            darkColorScheme(
                background = canvas,
                onBackground = Color(0xFFF4F4F5),
                surface = canvasSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = tintedSurface(Color(0xFF161619), primary, 0.20f),
                onSurfaceVariant = Color(0xFFA8A8B0),
                surfaceDim = tintedSurface(Color(0xFF0A0A0D), primary, 0.10f),
                surfaceBright = tintedSurface(Color(0xFF222228), primary, 0.20f),
                surfaceContainerLowest = tintedSurface(Color(0xFF0C0C0E), primary, 0.10f),
                surfaceContainerLow = tintedSurface(Color(0xFF101013), primary, 0.14f),
                surfaceContainer = canvasSurface,
                surfaceContainerHigh = tintedSurface(Color(0xFF161619), primary, 0.20f),
                surfaceContainerHighest = tintedSurface(Color(0xFF1C1C20), primary, 0.24f),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, canvas),
                onPrimaryContainer = primary,
                secondary = primary,
                onSecondary = onPrimary,
                error = BrandRed,
                onError = BrandOnEmerald,
                tertiary = Color(0xFFFBBF24),
            )
        }
        themeMode == ThemeMode.Dark -> {
            val canvas = tintedSurface(Color(0xFF141416), primary, 0.14f)
            val canvasSurface = tintedSurface(Color(0xFF1C1C1F), primary, 0.18f)
            val surfaceVariant = tintedSurface(Color(0xFF242428), primary, 0.24f)
            darkColorScheme(
                background = canvas,
                onBackground = Color(0xFFF4F4F5),
                surface = canvasSurface,
                onSurface = Color(0xFFF4F4F5),
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = Color(0xFFA1A1AA),
                surfaceDim = tintedSurface(Color(0xFF101013), primary, 0.12f),
                surfaceBright = tintedSurface(Color(0xFF2E2E34), primary, 0.22f),
                surfaceContainerLowest = tintedSurface(Color(0xFF161619), primary, 0.12f),
                surfaceContainerLow = tintedSurface(Color(0xFF1A1A1E), primary, 0.15f),
                surfaceContainer = canvasSurface,
                surfaceContainerHigh = tintedSurface(Color(0xFF232329), primary, 0.22f),
                surfaceContainerHighest = tintedSurface(Color(0xFF2A2A30), primary, 0.26f),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, canvas),
                onPrimaryContainer = primary,
                secondary = primary,
                onSecondary = onPrimary,
                error = BrandRed,
                onError = BrandOnEmerald,
                tertiary = Color(0xFFFBBF24),
            )
        }
        else -> {
            val canvas = tintedSurface(Color(0xFFFFFFFF), primary, 0.14f)
            val canvasSurface = tintedSurface(Color(0xFFEFF0F3), primary, 0.18f)
            val surfaceVariant = tintedSurface(Color(0xFFC3C3CE), primary, 0.24f)
            lightColorScheme(
                background = canvas,
                onBackground = Color(0xFF18181B),
                surface = canvasSurface,
                onSurface = Color(0xFF18181B),
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = Color(0xFF52525B),
                surfaceDim = tintedSurface(Color(0xFFE2E3E8), primary, 0.16f),
                surfaceBright = tintedSurface(Color(0xFFFFFFFF), primary, 0.10f),
                surfaceContainerLowest = tintedSurface(Color(0xFFFFFFFF), primary, 0.10f),
                surfaceContainerLow = canvasSurface,
                surfaceContainer = canvasSurface,
                surfaceContainerHigh = surfaceVariant,
                surfaceContainerHighest = tintedSurface(Color(0xFFB8B8C4), primary, 0.26f),
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = softContainer(primary, canvas),
                onPrimaryContainer = primary,
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
