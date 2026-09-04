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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
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
import androidx.compose.ui.draw.shadow
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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "card-press",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .height(CardHeight)
            .shadow(elevation = 6.dp, shape = CardCorner, clip = false)
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                onClick = {},
                onLongClick = onLongPress,
            )
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CardCorner)
            .padding(16.dp),
    ) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                BatteryRow(battery, charging = charging)
            }
            if (onViewScreen != null) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Outlined.Cast,
                        contentDescription = "View laptop screen (experimental)",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onViewScreen)
                            .padding(4.dp)
                            .size(20.dp),
                    )
                    Box(
                        Modifier
                            .padding(top = 3.dp, end = 2.dp)
                            .size(7.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color(0xFFF0B43C)),
                    )
                }
                Spacer(modifier = Modifier.size(8.dp))
            }
            if (locked != null && onToggleLock != null) {
                Icon(
                    imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint = if (locked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleLock)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
        }
    }
}
