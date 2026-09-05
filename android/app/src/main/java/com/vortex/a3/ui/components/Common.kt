package com.vortex.a3.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import com.vortex.a3.ui.AdvertiseState
import com.vortex.a3.ui.str

val CardCorner = RoundedCornerShape(16.dp)

val CardHeight = 180.dp

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

@Composable
fun CardHeader(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    statusDot: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        StatusDot(color = statusDot)
    }
}

@Composable
fun StatusDot(color: Color) {
    val isOnline = color == MaterialTheme.colorScheme.primary
    if (!isOnline) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )
    val density = LocalDensity.current.density
    Canvas(modifier = Modifier.size(22.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = (4f + 9f * progress) * density
        val ringAlpha = (0.55f * (1f - progress)).coerceAtLeast(0f)
        drawCircle(
            color = color.copy(alpha = ringAlpha),
            radius = ringRadius,
            center = center,
            style = Stroke(width = 2f * density),
        )
        drawCircle(color = color, radius = 4f * density, center = center)
    }
}

@Composable
fun BatteryRow(pct: Int?, charging: Boolean = false) {
    val icon = SolarIcons.batteryIconFor(pct, charging)
    val tint = when {
        charging -> Color(0xFF69B7FF)
        pct == null -> MaterialTheme.colorScheme.onSurfaceVariant
        pct <= 15 -> MaterialTheme.colorScheme.error
        pct <= 30 -> Color(0xFFFBBF24)
        else -> MaterialTheme.colorScheme.primary
    }
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
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun VortexDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
fun PairedRow(label: String, short: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SolarIcons.Laptop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.Medium, style = MaterialTheme.typography.bodyMedium)
                Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
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
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
        )
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("⚠ $text", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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
                ) { Text(dismissLabel, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
