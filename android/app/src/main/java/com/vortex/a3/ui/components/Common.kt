package com.vortex.a3.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import com.vortex.a3.ui.AdvertiseState
import com.vortex.a3.ui.str

val CardCorner = RoundedCornerShape(16.dp)

val CardHeight = 208.dp

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
    // NOTE: Flat status signal. Solid dot only, no pulse or glow.
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SolarIcons.Laptop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Warning: $text", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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
