package com.vortex.a3.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vortex.a3.ui.icons.SolarIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.ui.unit.dp
import com.vortex.a3.R
import com.vortex.a3.core.appstate.AppState
import com.vortex.a3.ui.components.AppHeader
import com.vortex.a3.ui.components.VortexLogo
import com.vortex.a3.core.appstate.EarbudsInfo
import com.vortex.a3.core.earbuds.BluetoothDeviceRow
import com.vortex.a3.core.earbuds.SwitchState
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import com.vortex.a3.core.storage.TrustedPeer
import com.vortex.a3.ui.AdvertiseState
import com.vortex.a3.ui.LAPTOP_STALE_MS
import com.vortex.a3.ui.PickerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import com.vortex.a3.ui.components.CalendarCard
import com.vortex.a3.ui.components.CardCorner
import com.vortex.a3.ui.components.EarbudsCard
import com.vortex.a3.ui.components.EarbudsPickerDialog
import com.vortex.a3.ui.components.HintCard
import com.vortex.a3.ui.components.NoteCarousel
import com.vortex.a3.ui.components.PairNewDeviceCard
import com.vortex.a3.ui.components.PeerDeviceCard
import com.vortex.a3.ui.components.ThisDeviceCard
import com.vortex.a3.ui.components.VortexDivider
import com.vortex.a3.ui.components.toHex
import com.vortex.a3.ui.str

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: AdvertiseState,
    @Suppress("UNUSED_PARAMETER") identity: IdentityRecord?,
    @Suppress("UNUSED_PARAMETER") handshake: PairingOrchestrator.HandshakeOutcome?,
    @Suppress("UNUSED_PARAMETER") reconnect: ReconnectOrchestrator.ReconnectOutcome?,
    peers: List<TrustedPeer>,
    peerStates: Map<String, AppState>,
    peerLastSeen: Map<String, Long>,
    now: Long,
    localEarbuds: EarbudsInfo?,
    hasSavedEarbuds: Boolean,
    pickerState: PickerState,
    switchState: SwitchState,
    onForgetPeer: (TrustedPeer) -> Unit,
    onOpenAutostart: () -> Unit,
    onDismissAutostartHint: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenNotes: () -> Unit = {},
    onOpenNote: (com.vortex.a3.core.notes.Note) -> Unit = {},
    onAddNote: () -> Unit = {},
    onOpenEarbudsPicker: () -> Unit,
    onPickEarbud: (BluetoothDeviceRow) -> Unit,
    onRescanEarbuds: () -> Unit,
    onClosePicker: () -> Unit,
    onRemoveSavedEarbuds: () -> Unit,
    onToggleLaptopLock: (Boolean) -> Unit,
    onSuspendLaptop: () -> Unit,
    onShutdownLaptop: () -> Unit,
    showAutostartHint: Boolean,
    showBatteryHint: Boolean,
    showBluetoothOff: Boolean,
    onEnableBluetooth: () -> Unit,
    onStartPairing: () -> Unit = {},
    onRequestSwitchEarbuds: () -> Unit = {},
    calendarBackend: String = "local",
) {
    val peerCount = peers.size
    val primaryPeer = peers.firstOrNull()
    val primaryHex = remember(primaryPeer) { primaryPeer?.peerStaticPub?.toHex() }
    val primaryState = remember(primaryHex, peerStates) {
        primaryHex?.let { peerStates[it] }
    }
    val lastSeen = primaryHex?.let { peerLastSeen[it] } ?: 0L
    val isLaptopOnline = primaryPeer != null && lastSeen > 0L &&
        (now - lastSeen) < LAPTOP_STALE_MS
    var forgetTarget by remember { mutableStateOf<TrustedPeer?>(null) }
    var showScreenKind by remember { mutableStateOf(false) }
    var showSuspendConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }

    data class ActiveEarbuds(val name: String, val battery: Int?, val onLocal: Boolean, val connected: Boolean)
    val peerBuds = primaryState?.earbuds
    val activeEarbuds: ActiveEarbuds? = remember(
        hasSavedEarbuds,
        localEarbuds,
        isLaptopOnline,
        peerBuds,
    ) {
        when {
            hasSavedEarbuds && localEarbuds != null -> {
                val peerHas =
                    isLaptopOnline && peerBuds?.connected == true &&
                        peerBuds.name.equals(localEarbuds.name, ignoreCase = true)
                when {
                    localEarbuds.connected ->
                        ActiveEarbuds(localEarbuds.name, localEarbuds.battery, onLocal = true, connected = true)
                    peerHas ->
                        ActiveEarbuds(localEarbuds.name, peerBuds.battery, onLocal = false, connected = true)
                    else ->
                        ActiveEarbuds(localEarbuds.name, null, onLocal = true, connected = false)
                }
            }
            localEarbuds?.connected == true ->
                ActiveEarbuds(localEarbuds.name, localEarbuds.battery, onLocal = true, connected = true)
            isLaptopOnline && peerBuds?.connected == true ->
                ActiveEarbuds(peerBuds.name, peerBuds.battery, onLocal = false, connected = true)
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        AppHeader(
            title = str("app.title"),
            tagline = str("app.tagline"),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showBluetoothOff) {
                HintCard(
                    text = str("hint.bluetooth_off"),
                    actionLabel = str("hint.bluetooth_off_action"),
                    onAction = onEnableBluetooth,
                )
            }
            if (showBatteryHint && peerCount > 0) {
                HintCard(
                    text = str("hint.battery"),
                    actionLabel = str("hint.battery_action"),
                    onAction = onRequestBatteryWhitelist,
                )
            }
            if (showAutostartHint && peerCount > 0) {
                HintCard(
                    text = str("hint.autostart"),
                    actionLabel = str("hint.autostart_action"),
                    onAction = onOpenAutostart,
                    dismissLabel = str("hint.dismiss"),
                    onDismiss = onDismissAutostartHint,
                )
            }

            ThisDeviceCard()

            val activeCount = (if (isLaptopOnline) 1 else 0) + (if (activeEarbuds?.connected == true) 1 else 0)
            val totalCount = peerCount + (if (hasSavedEarbuds || activeEarbuds != null) 1 else 0) + 1
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PAIRED ENDPOINTS & DEVICES",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FW.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = "$activeCount Active · $totalCount total",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FW.Medium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerDeviceCard(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.84f),
                    icon = SolarIcons.Laptop,
                    name = primaryState?.name?.takeIf { it.isNotBlank() }
                        ?: primaryPeer?.peerName?.takeIf { it.isNotBlank() }
                        ?: str("device.linux"),
                    caption = str(if (isLaptopOnline) "peers.online" else "peers.offline"),
                    battery = primaryState?.battery.takeIf { isLaptopOnline },
                    charging = isLaptopOnline && primaryState?.charging == true,
                    onLongPress = { primaryPeer?.let { forgetTarget = it } },
                    ip = if (isLaptopOnline) primaryState?.wifiIp else null,
                    distro = if (isLaptopOnline) (primaryState?.distro?.takeIf { it.isNotBlank() } ?: "NixOS") else null,
                    locked = primaryState?.locked.takeIf { isLaptopOnline },
                    onToggleLock = {
                        primaryState?.locked?.let { onToggleLaptopLock(it) }
                    },
                    onViewScreen = if (isLaptopOnline) {
                        { showScreenKind = true }
                    } else {
                        null
                    },
                    onSuspend = if (isLaptopOnline) {
                        { showSuspendConfirm = true }
                    } else {
                        null
                    },
                    onShutdown = if (isLaptopOnline) {
                        { showShutdownConfirm = true }
                    } else {
                        null
                    },
                )

                EarbudsCard(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.84f),
                    name = activeEarbuds?.name,
                    battery = activeEarbuds?.battery,
                    connected = activeEarbuds?.connected == true,
                    onLocal = activeEarbuds?.onLocal == true,
                    canRemove = hasSavedEarbuds,
                    switchState = switchState,
                    onOpenPicker = onOpenEarbudsPicker,
                    onRemoveSaved = onRemoveSavedEarbuds,
                    onSwitchRoute = onRequestSwitchEarbuds,
                )
            }

            // NOTE: CALENDAR section sits above NOTES in Hub order
            Text(
                text = str("calendar.title"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FW.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
            val context = androidx.compose.ui.platform.LocalContext.current
            val calendarProvider = remember(calendarBackend) {
                if (calendarBackend == "ricelin") {
                    com.vortex.a3.core.calendar.RicelinFileProvider(
                        java.io.File(context.filesDir, "events.json"),
                    )
                } else {
                    com.vortex.a3.core.calendar.LocalCalendarProvider()
                }
            }
            var selectedDay by remember {
                mutableStateOf(
                    runCatching {
                        java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
                    }.getOrDefault("2026-01-01"),
                )
            }
            CalendarCard(
                selected = selectedDay,
                onSelect = { selectedDay = it },
                provider = calendarProvider,
                onOpenNote = { id ->
                    com.vortex.a3.core.notes.NoteStore.notes.value.firstOrNull { it.id == id }?.let {
                        onOpenNote(it)
                    }
                },
            )

            Text(
                text = "NOTES",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FW.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
            NoteCarousel(
                onOpenNote = onOpenNote,
                onAddNote = onAddNote,
            )
        }
    }

    if (pickerState.open) {
        EarbudsPickerDialog(
            pickerState = pickerState,
            onPick = onPickEarbud,
            onRescan = onRescanEarbuds,
            onClose = onClosePicker,
        )
    }

    if (showScreenKind) {
        AlertDialog(
            onDismissRequest = { showScreenKind = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    str("cast.kind_title"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FW.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScreenKindRow(
                        title = str("cast.kind_extend"),
                        hint = str("cast.kind_extend_hint"),
                    ) {
                        showScreenKind = false
                        com.vortex.a3.core.mirror.LaptopMirror.requestView(extend = true)
                    }
                    ScreenKindRow(
                        title = str("cast.kind_mirror"),
                        hint = str("cast.kind_mirror_hint"),
                    ) {
                        showScreenKind = false
                        com.vortex.a3.core.mirror.LaptopMirror.requestView(extend = false)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScreenKind = false }) {
                    Text(str("switch.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    if (forgetTarget != null) {
        val target = forgetTarget!!
        val displayName = peerStates[target.peerStaticPub.toHex()]?.name
            ?: target.peerName
            ?: str("device.linux")
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(str("peers.forget_title"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold)
            },
            text = {
                Text(
                    str("peers.forget_body", displayName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val t = forgetTarget
                        forgetTarget = null
                        if (t != null) onForgetPeer(t)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(str("peers.forget_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) {
                    Text(str("scan.close"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    if (showShutdownConfirm) {
        val laptopName = primaryState?.name?.takeIf { it.isNotBlank() }
            ?: primaryPeer?.peerName?.takeIf { it.isNotBlank() }
            ?: str("device.linux")
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(str("shutdown.confirm_title"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold)
            },
            text = {
                Text(
                    str("shutdown.confirm_body", laptopName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShutdownConfirm = false
                        onShutdownLaptop()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(str("shutdown.confirm_button")) }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownConfirm = false }) {
                    Text(str("switch.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }

    if (showSuspendConfirm) {
        val laptopName = primaryState?.name?.takeIf { it.isNotBlank() }
            ?: primaryPeer?.peerName?.takeIf { it.isNotBlank() }
            ?: str("device.linux")
        AlertDialog(
            onDismissRequest = { showSuspendConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(str("suspend.confirm_title"), color = MaterialTheme.colorScheme.onSurface, fontWeight = FW.SemiBold)
            },
            text = {
                Text(
                    str("suspend.confirm_body", laptopName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuspendConfirm = false
                        onSuspendLaptop()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(str("suspend.confirm_button")) }
            },
            dismissButton = {
                TextButton(onClick = { showSuspendConfirm = false }) {
                    Text(str("switch.cancel"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}



@Composable
private fun ScreenKindRow(title: String, hint: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FW.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
