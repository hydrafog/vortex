package com.vortex.a3.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.vortex.a3.core.appstate.AppState
import com.vortex.a3.core.appstate.EarbudsInfo
import com.vortex.a3.core.earbuds.BluetoothDeviceRow
import com.vortex.a3.core.earbuds.EarbudsSwitchHolder
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.clipboard.ClipboardAccess
import com.vortex.a3.core.clipboard.ClipboardSyncSetting
import com.vortex.a3.core.media.SmartSwitchSetting
import com.vortex.a3.core.notif.NotificationMirrorSetting
import com.vortex.a3.service.VortexService
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import com.vortex.a3.core.storage.TrustedPeer
import com.vortex.a3.ui.components.SasApprovalDialog
import com.vortex.a3.ui.screens.HomeScreen
import com.vortex.a3.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainUiState(
    val advertise: StateFlow<AdvertiseState>,
    val identity: StateFlow<IdentityRecord?>,
    val handshake: StateFlow<PairingOrchestrator.HandshakeOutcome?>,
    val reconnect: StateFlow<ReconnectOrchestrator.ReconnectOutcome?>,
    val peers: StateFlow<List<TrustedPeer>>,
    val peerStates: StateFlow<Map<String, AppState>>,
    val peerLastSeen: StateFlow<Map<String, Long>>,
    val nowTick: StateFlow<Long>,
    val localEarbuds: StateFlow<EarbudsInfo?>,
    val hasSavedEarbuds: StateFlow<Boolean>,
    val picker: StateFlow<PickerState>,
    val pendingApproval: StateFlow<PairingOrchestrator.HandshakeOutcome?>,
    val autostartHintDismissed: StateFlow<Boolean>,
    val showNotifAccessDialog: MutableStateFlow<Boolean>,
    val showAutostartDialog: MutableStateFlow<Boolean>,
    val bluetoothOff: StateFlow<Boolean>,
)

class VortexActions(
    val onForgetPeer: (TrustedPeer) -> Unit,
    val onOpenAutostart: () -> Unit,
    val onDismissAutostartHint: () -> Unit,
    val onRequestBatteryWhitelist: () -> Unit,
    val onOpenEarbudsPicker: () -> Unit,
    val onPickEarbud: (BluetoothDeviceRow) -> Unit,
    val onRescanEarbuds: () -> Unit,
    val onClosePicker: () -> Unit,
    val onRemoveSavedEarbuds: () -> Unit,
    val onToggleLaptopLock: (Boolean) -> Unit,
    val onSuspendLaptop: () -> Unit,
    val onShutdownLaptop: () -> Unit,
    val onApprove: (PairingOrchestrator.HandshakeOutcome) -> Unit,
    val onReject: (PairingOrchestrator.HandshakeOutcome) -> Unit,
    val onOpenNotificationAccess: () -> Unit,
    val onOpenScreenControl: () -> Unit,
    val onEnableBluetooth: () -> Unit,
    val isAggressiveOem: Boolean,
    val isIgnoringBatteryOptimizations: () -> Boolean,
)

@Composable
fun VortexRoot(
    activity: ComponentActivity,
    settings: UiSettingsStore,
    ui: MainUiState,
    actions: VortexActions,
) {
    val activeLocale = settings.locale.collectAsState().value
    val activeTheme = settings.theme.collectAsState().value
    val activeAccent = settings.accent.collectAsState().value
    val colorScheme = remember(activeTheme, activeAccent, activity) {
        buildVortexColorScheme(activeTheme, activeAccent, activity)
    }
    SideEffect {
        val window = activity.window
        val isLight = activeTheme == ThemeMode.Light
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = isLight
        @Suppress("DEPRECATION")
        window.statusBarColor = colorScheme.background.toArgb()
    }
    CompositionLocalProvider(LocalVortexLocale provides activeLocale) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                var showSettings by remember { mutableStateOf(false) }
                var showNotes by remember { mutableStateOf(false) }
                remember { com.vortex.a3.core.notes.NoteStore.init(activity); 0 }
                remember { SmartSwitchSetting.init(activity); 0 }
                val smartSwitchOn = SmartSwitchSetting.enabled.collectAsState().value
                remember { NotificationMirrorSetting.init(activity); 0 }
                val notifMirrorOn = NotificationMirrorSetting.enabled.collectAsState().value
                val peerNotifShowOn = NotificationMirrorSetting.showPeer.collectAsState().value
                remember { ClipboardSyncSetting.init(activity); 0 }
                val clipboardSyncOn = ClipboardSyncSetting.enabled.collectAsState().value
                remember { com.vortex.a3.core.lan.FileAutoAcceptSetting.init(activity); 0 }
                val fileAutoAcceptOn =
                    com.vortex.a3.core.lan.FileAutoAcceptSetting.enabled.collectAsState().value
                val clipboardAutoGranted = remember(showSettings) {
                    ClipboardAccess.isBackgroundReadGranted(activity)
                }
                val screenControlOn = remember(showSettings) {
                    com.vortex.a3.service.VortexInputService.isEnabled(activity)
                }
                if (showNotes) {
                    BackHandler { showNotes = false }
                    com.vortex.a3.ui.screens.NotesScreen(onBack = { showNotes = false })
                } else if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        current = activeLocale,
                        onSelect = { settings.setLocale(it) },
                        currentTheme = activeTheme,
                        onSelectTheme = { settings.setTheme(it) },
                        currentAccent = activeAccent,
                        onSelectAccent = { settings.setAccent(it) },
                        smartSwitchOn = smartSwitchOn,
                        onSmartSwitchChange = {
                            SmartSwitchSetting.setLocal(it)
                            VortexService.requestStatePush()
                        },
                        notifMirrorOn = notifMirrorOn,
                        onNotifMirrorChange = { NotificationMirrorSetting.setEnabled(it) },
                        peerNotifShowOn = peerNotifShowOn,
                        onPeerNotifShowChange = { NotificationMirrorSetting.setShowPeer(it) },
                        clipboardSyncOn = clipboardSyncOn,
                        onClipboardSyncChange = { ClipboardSyncSetting.setEnabled(it) },
                        clipboardAutoGranted = clipboardAutoGranted,
                        fileAutoAcceptOn = fileAutoAcceptOn,
                        onFileAutoAcceptChange = {
                            com.vortex.a3.core.lan.FileAutoAcceptSetting.setEnabled(it)
                        },
                        screenControlOn = screenControlOn,
                        onScreenControlClick = actions.onOpenScreenControl,
                        onBack = { showSettings = false },
                    )
                } else {
                    HomeScreen(
                        state = ui.advertise.collectAsState().value,
                        identity = ui.identity.collectAsState().value,
                        handshake = ui.handshake.collectAsState().value,
                        reconnect = ui.reconnect.collectAsState().value,
                        peers = ui.peers.collectAsState().value,
                        peerStates = ui.peerStates.collectAsState().value,
                        peerLastSeen = ui.peerLastSeen.collectAsState().value,
                        now = ui.nowTick.collectAsState().value,
                        localEarbuds = ui.localEarbuds.collectAsState().value,
                        hasSavedEarbuds = ui.hasSavedEarbuds.collectAsState().value,
                        pickerState = ui.picker.collectAsState().value,
                        switchState = EarbudsSwitchHolder.state.collectAsState().value,
                        onForgetPeer = actions.onForgetPeer,
                        onOpenAutostart = actions.onOpenAutostart,
                        onDismissAutostartHint = actions.onDismissAutostartHint,
                        onRequestBatteryWhitelist = actions.onRequestBatteryWhitelist,
                        onOpenSettings = { showSettings = true },
                        onOpenNotes = { showNotes = true },
                        onOpenEarbudsPicker = actions.onOpenEarbudsPicker,
                        onPickEarbud = actions.onPickEarbud,
                        onRescanEarbuds = actions.onRescanEarbuds,
                        onClosePicker = actions.onClosePicker,
                        onRemoveSavedEarbuds = actions.onRemoveSavedEarbuds,
                        onToggleLaptopLock = actions.onToggleLaptopLock,
                        onSuspendLaptop = actions.onSuspendLaptop,
                        onShutdownLaptop = actions.onShutdownLaptop,
                        showAutostartHint = actions.isAggressiveOem &&
                            !ui.autostartHintDismissed.collectAsState().value,
                        showBatteryHint = !actions.isIgnoringBatteryOptimizations(),
                        showBluetoothOff = ui.bluetoothOff.collectAsState().value,
                        onEnableBluetooth = actions.onEnableBluetooth,
                    )
                    val pending = ui.pendingApproval.collectAsState().value
                    if (pending != null) {
                        SasApprovalDialog(
                            outcome = pending,
                            onApprove = { actions.onApprove(pending) },
                            onReject = { actions.onReject(pending) },
                        )
                    }
                    val askNotif = ui.showNotifAccessDialog.collectAsState().value
                    if (askNotif) {
                        AlertDialog(
                            onDismissRequest = { ui.showNotifAccessDialog.value = false },
                            title = { Text(str("hint.notif_access_action")) },
                            text = { Text(str("hint.notif_access")) },
                            confirmButton = {
                                TextButton(onClick = {
                                    ui.showNotifAccessDialog.value = false
                                    actions.onOpenNotificationAccess()
                                }) { Text(str("hint.notif_access_action")) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    ui.showNotifAccessDialog.value = false
                                }) { Text(str("common.later")) }
                            },
                        )
                    }
                    val askAutostart = ui.showAutostartDialog.collectAsState().value
                    if (askAutostart) {
                        AlertDialog(
                            onDismissRequest = { ui.showAutostartDialog.value = false },
                            title = { Text(str("hint.autostart_action")) },
                            text = { Text(str("hint.autostart")) },
                            confirmButton = {
                                TextButton(onClick = {
                                    ui.showAutostartDialog.value = false
                                    actions.onOpenAutostart()
                                }) { Text(str("hint.autostart_action")) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    ui.showAutostartDialog.value = false
                                }) { Text(str("common.later")) }
                            },
                        )
                    }
                }
            }
        }
    }
}
