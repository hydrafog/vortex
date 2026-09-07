package com.vortex.a3.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vortex.a3.core.earbuds.SwitchState
import com.vortex.a3.ui.str

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EarbudsCard(
    modifier: Modifier = Modifier,
    name: String?,
    battery: Int?,
    connected: Boolean,
    onLocal: Boolean,
    canRemove: Boolean,
    switchState: SwitchState,
    onOpenPicker: () -> Unit,
    onRemoveSaved: () -> Unit,
) {
    if (name == null) {
        EarbudsAddPlaceholder(modifier = modifier, onOpenPicker = onOpenPicker)
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val isSwitching = switchState !is SwitchState.Idle &&
        switchState !is SwitchState.Failed &&
        switchState !is SwitchState.AlmostDone
    val tintColor = MaterialTheme.colorScheme.onPrimaryContainer
    val caption = when {
        !connected -> str("earbuds.not_connected")
        onLocal -> str("earbuds.on_local")
        else -> str("earbuds.on_peer")
    }
    val cardInteraction = remember { MutableInteractionSource() }
    val contentColor = MaterialTheme.colorScheme.onSurface
    val contentVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .height(CardHeight)
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = cardInteraction,
                indication = ripple(bounded = true),
                enabled = !isSwitching,
                onClick = {},
                onLongClick = { if (canRemove) menuOpen = true },
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CardCorner,
            )
            .padding(16.dp),
    ) {
        CardHeader(
            icon = SolarIcons.Headphones,
            iconTint = tintColor,
            iconBg = MaterialTheme.colorScheme.primaryContainer,
            statusDot = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            name.takeIf { it.isNotBlank() } ?: str("device.earbuds"),
            color = contentColor,
            fontWeight = FW.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        val captionText = when {
            isSwitching -> str("switch.in_progress")
            else -> caption
        }
        Text(
            captionText,
            color = contentVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
        Spacer(modifier = Modifier.weight(1f))
        BatteryRow(battery)
    }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(str("earbuds.remove"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold) },
            text = {
                Text(
                    str("earbuds.remove_body"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        menuOpen = false
                        onRemoveSaved()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(str("earbuds.remove")) }
            },
            dismissButton = {
                TextButton(onClick = { menuOpen = false }) {
                    Text(str("switch.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
fun EarbudsAddPlaceholder(
    modifier: Modifier = Modifier,
    onOpenPicker: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .height(CardHeight)
            .clip(CardCorner)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
            ) { onOpenPicker() }
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardCorner)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SolarIcons.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            str("earbuds.add"),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FW.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            str("earbuds.add_hint"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
