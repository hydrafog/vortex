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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

val CardCorner = RoundedCornerShape(16.dp)

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
    iconTint: Color,
    iconBg: Color,
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
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
                .background(MaterialTheme.colorScheme.primaryContainer),
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
