package com.vortex.a3.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vortex.a3.ui.icons.SolarIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerDeviceCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    name: String,
    caption: String,
    battery: Int?,
    charging: Boolean = false,
    statusDotColor: Color,
    onLongPress: () -> Unit,
    locked: Boolean? = null,
    onToggleLock: (() -> Unit)? = null,
    onViewScreen: (() -> Unit)? = null,
    onSuspend: (() -> Unit)? = null,
    onShutdown: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "peer_press_scale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .height(CardHeight)
            .fillMaxWidth()
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape = CardCorner,
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                onLongClick = onLongPress,
                onClick = {},
            )
            .padding(16.dp),
    ) {
        Column {
            CardHeader(
                icon = icon,
                iconTint = MaterialTheme.colorScheme.primary,
                iconBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                statusDot = statusDotColor,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            // NOTE: Battery gets its own full-width line so narrow half-width
            // NOTE: cards never squeeze it against the action buttons.
            BatteryRow(battery, charging = charging)
            if (onViewScreen != null || (locked != null && onToggleLock != null) || onSuspend != null || onShutdown != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onViewScreen != null) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = SolarIcons.Cast,
                                contentDescription = "View laptop screen (experimental)",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onViewScreen)
                                    .padding(10.dp)
                                    .size(20.dp),
                            )
                            Box(
                                Modifier
                                    .padding(top = 8.dp, end = 8.dp)
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(Color(0xFFF0B43C)),
                            )
                        }
                    }
                    if (locked != null && onToggleLock != null) {
                        Icon(
                            imageVector = SolarIcons.lockIconFor(locked),
                            contentDescription = if (locked) "Unlock laptop" else "Lock laptop",
                            tint = if (locked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onToggleLock)
                                .padding(10.dp)
                                .size(20.dp),
                        )
                    }
                    if (onSuspend != null) {
                        Icon(
                            imageVector = SolarIcons.Suspend,
                            contentDescription = "Suspend laptop",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onSuspend)
                                .padding(10.dp)
                                .size(20.dp),
                        )
                    }
                    if (onShutdown != null) {
                        Icon(
                            imageVector = SolarIcons.Power,
                            contentDescription = "Shut down laptop",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onShutdown)
                                .padding(10.dp)
                                .size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
