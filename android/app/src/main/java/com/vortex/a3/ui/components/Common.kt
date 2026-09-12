package com.vortex.a3.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.icons.SolarDuotoneIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.border
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import com.vortex.a3.ui.AdvertiseState
import com.vortex.a3.ui.str

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

val CardCorner = RoundedCornerShape(16.dp)

// NOTE: Perceived relative luminance calculation for theme-adaptive pillow shading.
private fun Color.perceivedLuminance(): Float {
    val r = red.toDouble()
    val g = green.toDouble()
    val b = blue.toDouble()
    fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    return (0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)).toFloat()
}

// NOTE: Mathematical 1:1 port of hyprland pillow.glsl color dodge + multiplicative shadow + soft light curve.
private fun pillowTransform(src: Color, delta: Float): Color {
    val r = src.red
    val g = src.green
    val b = src.blue
    val lum = 0.299f * r + 0.587f * g + 0.114f * b

    val strength = 0.20f

    val darkR: Float
    val darkG: Float
    val darkB: Float
    if (delta >= 0f) {
        val w = delta * 2.0f * strength
        val denom = maxOf(1.0f - w, 0.001f)
        darkR = (r + 0.015f * w) / denom
        darkG = (g + 0.015f * w) / denom
        darkB = (b + 0.015f * w) / denom
    } else {
        val w = (-delta) * 2.0f * strength * 0.75f
        darkR = r * (1.0f - w)
        darkG = g * (1.0f - w)
        darkB = b * (1.0f - w)
    }

    val sLight = 0.16f
    val m = 0.5f + delta * sLight
    val softR = (1.0f - 2.0f * m) * r * r + 2.0f * m * r
    val softG = (1.0f - 2.0f * m) * g * g + 2.0f * m * g
    val softB = (1.0f - 2.0f * m) * b * b + 2.0f * m * b

    val lumWeight = ((lum - 0.35f) / (0.75f - 0.35f)).coerceIn(0f, 1f)
    val smoothWeight = lumWeight * lumWeight * (3f - 2f * lumWeight)

    val finalR = (darkR * (1f - smoothWeight) + softR * smoothWeight).coerceIn(0f, 1f)
    val finalG = (darkG * (1f - smoothWeight) + softG * smoothWeight).coerceIn(0f, 1f)
    val finalB = (darkB * (1f - smoothWeight) + softB * smoothWeight).coerceIn(0f, 1f)

    return Color(finalR, finalG, finalB, src.alpha)
}

// NOTE: Shader-free 3D pillow shading reproducing hyprland pillow.glsl cushion lighting.
fun Modifier.pillowCard(
    shape: Shape = CardCorner,
    backgroundColor: Color? = null,
): Modifier = composed {
    val base = backgroundColor ?: MaterialTheme.colorScheme.surface
    val highlight = pillowTransform(base, 0.5f)
    val shadow = pillowTransform(base, -0.5f)

    this
        .clip(shape)
        .drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to highlight,
                    0.45f to base,
                    0.55f to base,
                    1.0f to shadow,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = shape,
        )
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Composable
fun AppHeader(
    title: String,
    tagline: String? = null,
    modifier: Modifier = Modifier,
    showLogo: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogo) {
            VortexLogo(
                size = 36.dp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FW.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!tagline.isNullOrBlank()) {
                Text(
                    text = tagline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun CardHeader(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBg: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(iconBg),
        contentAlignment = Alignment.Center,
    ) {
        SolarDuotoneIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun BatteryRow(pct: Int?, charging: Boolean = false) {
    if (pct == null && !charging) return
    val icon = SolarIcons.batteryIconFor(pct, charging)
    val tint = Color(0xFF33D17A)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = if (pct != null) "$pct%" else "—",
            color = tint,
            fontWeight = FW.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun SurfaceCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pillowCard(shape = RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun VortexDivider() {
}

@Composable
fun PairedRow(label: String, short: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            SolarDuotoneIcon(
                icon = SolarIcons.Laptop,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(short, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun WaitingForLinuxRow(state: AdvertiseState) {
    val caption = when (state) {
        is AdvertiseState.Error -> str("pair.error", state.reason)
        AdvertiseState.Starting -> str("pair.caption_starting")
        else -> str("discover.discoverable_hint")
    }
    val isError = state is AdvertiseState.Error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                str("discover.discoverable"),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FW.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun HintCard(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.wrapContentSize(),
            ) { Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            if (onDismiss != null && dismissLabel != null) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.wrapContentSize(),
                ) { Text(dismissLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
