package com.vortex.a3.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vortex.a3.ui.icons.SolarDuotoneIcon
import com.vortex.a3.ui.icons.SolarIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerDeviceCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    name: String,
    caption: String,
    battery: Int?,
    charging: Boolean = false,
    onLongPress: () -> Unit,
    ip: String? = null,
    distro: String? = null,
    locked: Boolean? = null,
    onToggleLock: (() -> Unit)? = null,
    onViewScreen: (() -> Unit)? = null,
    onSuspend: (() -> Unit)? = null,
    onShutdown: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pillowCard()
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onLongClick = onLongPress,
                onClick = {},
            )
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                CardHeader(
                    icon = icon,
                    iconBg = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FW.SemiBold,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                        )
                        Text(
                            text = caption,
                            color = if (caption.equals("Connected", ignoreCase = true) || caption.contains("online", ignoreCase = true)) {
                                Color(0xFF33D17A)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!ip.isNullOrBlank()) {
                            Text(
                                text = ip,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        BatteryRow(battery, charging = charging)
                        if (!distro.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = distro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (onViewScreen != null || (locked != null && onToggleLock != null) || onShutdown != null) {
                Column {
                    Text(
                        text = "QUICK ACTIONS",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FW.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onViewScreen != null) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(onClick = onViewScreen),
                                contentAlignment = Alignment.Center,
                            ) {
                                SolarDuotoneIcon(
                                    icon = SolarIcons.Cast,
                                    contentDescription = "View laptop screen",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (locked != null && onToggleLock != null) {
                            val isLocked = locked == true
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(onClick = onToggleLock),
                                contentAlignment = Alignment.Center,
                            ) {
                                SolarDuotoneIcon(
                                    icon = SolarIcons.lockIconFor(locked),
                                    contentDescription = if (isLocked) "Unlock laptop" else "Lock laptop",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (onShutdown != null) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable(onClick = onShutdown),
                                contentAlignment = Alignment.Center,
                            ) {
                                SolarDuotoneIcon(
                                    icon = SolarIcons.Power,
                                    contentDescription = "Shut down laptop",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
