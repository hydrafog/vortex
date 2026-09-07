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

fun buildVortexColorScheme(
    themeMode: ThemeMode,
    accent: AccentColor,
    context: Context,
): ColorScheme {
    val isDark = themeMode == ThemeMode.Dark

    if (accent == AccentColor.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return try {
            val scheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (isDark) {
                val canvas = Color(0xFF141416)
                scheme.copy(
                    background = canvas,
                    surface = Color(0xFF1C1C1F),
                    surfaceVariant = Color(0xFF242428),
                    surfaceDim = Color(0xFF101013),
                    surfaceBright = Color(0xFF2E2E34),
                    surfaceContainerLowest = Color(0xFF161619),
                    surfaceContainerLow = Color(0xFF1A1A1E),
                    surfaceContainer = Color(0xFF1C1C1F),
                    surfaceContainerHigh = Color(0xFF232329),
                    surfaceContainerHighest = Color(0xFF2A2A30),
                    outline = Color(0xFF2E2E33),
                    outlineVariant = Color(0xFF242429),
                    onBackground = Color(0xFFF4F4F5),
                    onSurface = Color(0xFFF4F4F5),
                    onSurfaceVariant = Color(0xFFA1A1AA),
                    primaryContainer = softContainer(scheme.primary, canvas),
                    onPrimaryContainer = scheme.primary,
                )
            } else {
                val canvas = Color(0xFFFAFAFA)
                scheme.copy(
                    background = canvas,
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFF4F4F5),
                    surfaceDim = Color(0xFFE0E0E5),
                    surfaceBright = Color(0xFFFFFFFF),
                    surfaceContainerLowest = Color(0xFFFFFFFF),
                    surfaceContainerLow = Color(0xFFF6F6F8),
                    surfaceContainer = Color(0xFFF1F1F4),
                    surfaceContainerHigh = Color(0xFFECECF0),
                    surfaceContainerHighest = Color(0xFFE7E7EB),
                    outline = Color(0xFFE4E4E7),
                    outlineVariant = Color(0xFFE7E7EB),
                    onBackground = Color(0xFF18181B),
                    onSurface = Color(0xFF18181B),
                    onSurfaceVariant = Color(0xFF71717A),
                    primaryContainer = softContainer(scheme.primary, canvas),
                    onPrimaryContainer = scheme.primary,
                )
            }
        } catch (_: Exception) {
            if (isDark) VortexDarkColors else VortexLightColors
        }
    }

    val primary = if (accent == AccentColor.System) BrandEmerald else accent.color
    val onPrimary = onAccentFor(primary)

    return if (isDark) {
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
            surfaceContainerLowest = Color(0xFF161619),
            surfaceContainerLow = Color(0xFF1A1A1E),
            surfaceContainer = canvasSurface,
            surfaceContainerHigh = Color(0xFF232329),
            surfaceContainerHighest = Color(0xFF2A2A30),
            outline = Color(0xFF2E2E33),
            outlineVariant = Color(0xFF242429),
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = softContainer(primary, canvas),
            onPrimaryContainer = primary,
            secondary = primary,
            onSecondary = onPrimary,
            error = BrandRed,
            onError = onPrimary,
            tertiary = Color(0xFFFBBF24),
        )
    } else {
        val canvas = Color(0xFFFAFAFA)
        val canvasSurface = Color(0xFFFFFFFF)
        lightColorScheme(
            background = canvas,
            onBackground = Color(0xFF18181B),
            surface = canvasSurface,
            onSurface = Color(0xFF18181B),
            surfaceVariant = Color(0xFFF4F4F5),
            onSurfaceVariant = Color(0xFF71717A),
            surfaceDim = Color(0xFFE0E0E5),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF6F6F8),
            surfaceContainer = Color(0xFFF1F1F4),
            surfaceContainerHigh = Color(0xFFECECF0),
            surfaceContainerHighest = Color(0xFFE7E7EB),
            outline = Color(0xFFE4E4E7),
            outlineVariant = Color(0xFFE7E7EB),
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = softContainer(primary, canvas),
            onPrimaryContainer = primary,
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
    surfaceDim = Color(0xFF101013),
    surfaceBright = Color(0xFF2E2E34),
    surfaceContainerLowest = Color(0xFF161619),
    surfaceContainerLow = Color(0xFF1A1A1E),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF232329),
    surfaceContainerHighest = Color(0xFF2A2A30),
    outline = Color(0xFF27272A),
    outlineVariant = Color(0xFF242429),
    primary = BrandEmerald,
    onPrimary = BrandOnEmerald,
    primaryContainer = softContainer(BrandEmerald, Color(0xFF18181B)),
    onPrimaryContainer = BrandEmerald,
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
    surfaceDim = Color(0xFFE0E0E5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F6F8),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFECECF0),
    surfaceContainerHighest = Color(0xFFE7E7EB),
    outline = Color(0xFFE4E4E7),
    outlineVariant = Color(0xFFE7E7EB),
    primary = BrandEmerald,
    onPrimary = BrandOnEmerald,
    primaryContainer = softContainer(BrandEmerald, Color(0xFFFAFAFA)),
    onPrimaryContainer = BrandEmerald,
    secondary = BrandEmerald,
    onSecondary = BrandOnEmerald,
    error = BrandRed,
    onError = BrandOnEmerald,
    tertiary = Color(0xFFD97706),
)
