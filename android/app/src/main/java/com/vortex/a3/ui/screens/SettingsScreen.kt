package com.vortex.a3.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vortex.a3.ui.AccentColor
import com.vortex.a3.ui.ThemeMode
import com.vortex.a3.ui.VortexLocale
import com.vortex.a3.ui.components.AppHeader
import com.vortex.a3.ui.components.VortexDivider
import com.vortex.a3.ui.icons.SolarIcons
import com.vortex.a3.ui.onAccentFor
import com.vortex.a3.ui.str

@Composable
fun SettingsScreen(
    current: VortexLocale,
    onSelect: (VortexLocale) -> Unit,
    currentTheme: ThemeMode,
    onSelectTheme: (ThemeMode) -> Unit,
    currentAccent: AccentColor? = null,
    onSelectAccent: ((AccentColor) -> Unit)? = null,
    smartSwitchOn: Boolean,
    onSmartSwitchChange: (Boolean) -> Unit,
    notifMirrorOn: Boolean,
    onNotifMirrorChange: (Boolean) -> Unit,
    peerNotifShowOn: Boolean,
    onPeerNotifShowChange: (Boolean) -> Unit,
    clipboardSyncOn: Boolean,
    onClipboardSyncChange: (Boolean) -> Unit,
    clipboardAutoGranted: Boolean,
    fileAutoAcceptOn: Boolean,
    onFileAutoAcceptChange: (Boolean) -> Unit,
    screenControlOn: Boolean,
    onScreenControlClick: () -> Unit,
    onBack: () -> Unit,
    calendarBackend: String = "local",
    onSelectBackend: ((String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = str("settings.title"),
            tagline = "Preferences and device configuration",
            showLogo = false,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            SectionLabel(str("settings.sec_appearance"))
            SectionCard {
                PickerRow(icon = SolarIcons.Language, label = str("settings.language")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (loc in VortexLocale.entries) {
                            SegmentedButton(
                                label = loc.label,
                                selected = loc == current,
                                onClick = { onSelect(loc) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                RowDivider()
                PickerRow(
                    icon = when (currentTheme) {
                        ThemeMode.Light -> SolarIcons.LightMode
                        else -> SolarIcons.DarkMode
                    },
                    label = str("settings.theme"),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegmentedButton(
                            label = str("settings.theme_dark"),
                            selected = currentTheme == ThemeMode.Dark,
                            onClick = { onSelectTheme(ThemeMode.Dark) },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentedButton(
                            label = str("settings.theme_light"),
                            selected = currentTheme == ThemeMode.Light,
                            onClick = { onSelectTheme(ThemeMode.Light) },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentedButton(
                            label = "OLED",
                            selected = currentTheme == ThemeMode.Oled,
                            onClick = { onSelectTheme(ThemeMode.Oled) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                RowDivider()
                PickerRow(
                    icon = SolarIcons.TouchApp,
                    label = str("settings.accent_color").ifBlank { "Accent color" },
                ) {
                    val context = LocalContext.current
                    val isDark = currentTheme != ThemeMode.Light
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(AccentColor.entries) { acc ->
                            val isSelected = acc == currentAccent
                            val displayColor = acc.resolveDisplayColor(context, isDark)
                            val contentColor = onAccentFor(displayColor)
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(displayColor)
                                    .clickable { onSelectAccent?.invoke(acc) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = SolarIcons.Check,
                                        contentDescription = acc.label,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SectionLabel(str("settings.sec_audio"))
            SectionCard {
                ToggleRow(
                    icon = SolarIcons.Headset,
                    title = str("settings.smart_switch"),
                    hint = str("settings.smart_switch_hint"),
                    checked = smartSwitchOn,
                    onCheckedChange = onSmartSwitchChange,
                )
            }

            SectionLabel(str("settings.sec_notifications"))
            SectionCard {
                ToggleRow(
                    icon = SolarIcons.Notifications,
                    title = str("settings.notif_mirror"),
                    hint = str("settings.notif_mirror_hint"),
                    checked = notifMirrorOn,
                    onCheckedChange = onNotifMirrorChange,
                )
                RowDivider()
                ToggleRow(
                    icon = SolarIcons.NotificationsActive,
                    title = str("settings.peer_notif"),
                    hint = str("settings.peer_notif_hint"),
                    checked = peerNotifShowOn,
                    onCheckedChange = onPeerNotifShowChange,
                )
            }

            SectionLabel(str("settings.sec_sharing"))
            SectionCard {
                ToggleRow(
                    icon = SolarIcons.ContentPaste,
                    title = str("settings.clipboard_sync"),
                    hint = if (clipboardAutoGranted) str("settings.clipboard_auto_on")
                    else str("settings.clipboard_sync_hint"),
                    checked = clipboardSyncOn,
                    onCheckedChange = onClipboardSyncChange,
                )
                RowDivider()
                ToggleRow(
                    icon = SolarIcons.FileDownload,
                    title = str("settings.file_auto_accept"),
                    hint = str("settings.file_auto_accept_hint"),
                    checked = fileAutoAcceptOn,
                    onCheckedChange = onFileAutoAcceptChange,
                )
            }
            if (clipboardSyncOn && !clipboardAutoGranted) {
                Spacer(Modifier.height(10.dp))
                AdbHintCard(
                    title = str("settings.clipboard_adb_title"),
                    body = str("settings.clipboard_adb_body"),
                    command = "adb shell appops set com.vortex.a3 READ_CLIPBOARD allow",
                )
            }

            SectionLabel(str("settings.sec_device"))
            SectionCard {
                ActionRow(
                    icon = SolarIcons.TouchApp,
                    title = "Screen control",
                    hint = if (screenControlOn) "On. The laptop can control this phone while mirroring"
                    else "Off. Tap to enable in Accessibility",
                    status = if (screenControlOn) "On" else "Off",
                    onClick = onScreenControlClick,
                )
            }

            SectionLabel(str("calendar.provider"))
            SectionCard {
                PickerRow(icon = SolarIcons.StickyNote2, label = str("calendar.provider")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegmentedButton(
                            label = "local",
                            selected = calendarBackend == "local",
                            onClick = { onSelectBackend?.invoke("local") },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentedButton(
                            label = "ricelin",
                            selected = calendarBackend == "ricelin",
                            onClick = { onSelectBackend?.invoke("ricelin") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FW.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
private fun RowDivider() {
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (!hint.isNullOrEmpty()) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, hint: String?, status: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FW.Medium, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AdbHintCard(title: String, body: String, command: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            command,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SegmentedButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                Icon(imageVector = leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(label, color = fg, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FW.SemiBold else FW.Normal)
        }
    }
}
