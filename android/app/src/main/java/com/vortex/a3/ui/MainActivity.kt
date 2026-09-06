package com.vortex.a3.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import com.vortex.a3.BuildConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.provider.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import com.vortex.a3.core.ble.Advertiser
import com.vortex.a3.core.ble.GattServer
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.identity.Platform
import com.vortex.a3.core.lan.LanServer
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import com.vortex.a3.core.storage.EncryptedPrefsIdentityStore
import com.vortex.a3.core.storage.EncryptedPrefsPeerStore
import com.vortex.a3.core.storage.PeerStore
import com.vortex.a3.core.storage.TrustedPeer
import com.vortex.a3.core.storage.loadOrGenerate
import com.vortex.a3.service.VortexService
import com.vortex.a3.core.appstate.AppState
import com.vortex.a3.ui.components.EarbudsCard
import com.vortex.a3.ui.components.PeerDeviceCard
import com.vortex.a3.ui.components.toHex
import com.vortex.a3.ui.screens.HomeScreen
import com.vortex.a3.ui.screens.SettingsScreen
import com.vortex.a3.core.earbuds.EarbudsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {

    internal val advertiser by lazy { Advertiser(applicationContext) }
    internal val gattServer by lazy { GattServer(applicationContext) }
    internal val identityStore by lazy { EncryptedPrefsIdentityStore(applicationContext) }
    internal val peerStore: PeerStore by lazy { EncryptedPrefsPeerStore(applicationContext) }
    internal var pairingOrchestrator: PairingOrchestrator? = null
    internal var lanServer: LanServer? = null

    internal var pairingInstanceId: ByteArray? = null

    internal val state = MutableStateFlow<AdvertiseState>(AdvertiseState.Idle)
    internal val identityState = MutableStateFlow<IdentityRecord?>(null)
    internal val handshakeState = MutableStateFlow<PairingOrchestrator.HandshakeOutcome?>(null)
    internal val reconnectState = MutableStateFlow<ReconnectOrchestrator.ReconnectOutcome?>(null)
    internal val peerCountState = MutableStateFlow(0)
    internal val peerListState = MutableStateFlow<List<TrustedPeer>>(emptyList())

    internal val peerStatesState = MutableStateFlow<Map<String, AppState>>(emptyMap())

    internal val peerLastSeenState = MutableStateFlow<Map<String, Long>>(emptyMap())

    internal val nowTickState = MutableStateFlow(System.currentTimeMillis())

    internal val localEarbudsState =
        MutableStateFlow<com.vortex.a3.core.appstate.EarbudsInfo?>(null)
    internal var earbudsPollJob: kotlinx.coroutines.Job? = null

    internal val earbudsPickerState = MutableStateFlow(PickerState())
    internal var pickerScanJob: kotlinx.coroutines.Job? = null

    internal val savedEarbudsExists = MutableStateFlow(false)

    internal val pickerPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted.values.all { it }
        if (ok) {
            startPickerScan()
        } else {
            earbudsPickerState.value = PickerState(open = true, scanning = false, rows = emptyList())
        }
    }

    internal val settingsPrefs by lazy {
        getSharedPreferences("vortex_ui_settings", MODE_PRIVATE)
    }
    internal val uiSettings by lazy { UiSettingsStore(this) }

    internal val pendingApproval =
        MutableStateFlow<PairingOrchestrator.HandshakeOutcome?>(null)

    internal val showNotifAccessDialog = MutableStateFlow(false)

    internal val showAutostartDialog = MutableStateFlow(false)

    internal val autostartHintDismissed by lazy {
        MutableStateFlow(settingsPrefs.getBoolean("autostart_hint_dismissed", false))
    }

    internal val bluetoothOff = MutableStateFlow(false)

    private var btReceiverRegistered = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            bluetoothOff.value = isBluetoothOff()
        }
    }

    private fun isBluetoothOff(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        return adapter == null || !adapter.isEnabled
    }

    internal val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { bluetoothOff.value = isBluetoothOff() }

    internal val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val denied = granted.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startAdvertising()
        } else {
            state.value = AdvertiseState.Error("permissions denied: ${denied.joinToString()}")
        }
    }

    internal val essentialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {  }

    internal val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        VortexService.startOrRefreshCallFlow(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        uiSettings.load()
        val identity = identityStore.loadOrGenerate(Platform.Android)
        identityState.value = identity
        applyDevHooks()
        refreshPeerList()
        refreshSavedEarbudsFlag()
        com.vortex.a3.core.earbuds.EarbudsSwitchHolder.init(applicationContext, peerStore)
        wirePairingOrchestrator(identity)
        wireReconnectOrchestrator(identity)
        startPairingWindowLanIfUntrusted(identity)
        wireRuntimeObservers()
        val ui = buildUiState()
        val actions = buildActions()
        setContent {
            VortexRoot(activity = this, settings = uiSettings, ui = ui, actions = actions)
        }
        maybeRequestEssentialPermissions()
        selectLaunchMode()
    }

    private fun applyDevHooks() {
        if (!BuildConfig.DEBUG) return
        if (intent.getBooleanExtra("clear_trust", false)) {
            for (peer in peerStore.list()) peerStore.forget(peer.peerStaticPub)
        }
        intent.getStringExtra("remove_bond")?.let { mac ->
            val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            adapter?.let { com.vortex.a3.core.ble.BondCleaner.removeBond(it, mac) }
        }
    }




    private fun wireRuntimeObservers() {
        lifecycleScope.launch {
            VortexService.peerStateBus.collect { (hex, state) ->
                peerStatesState.update { it + (hex to state) }
                peerLastSeenState.update { it + (hex to System.currentTimeMillis()) }
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                nowTickState.value = System.currentTimeMillis()
                delay(3_000)
            }
        }
        lifecycleScope.launch {
            VortexService.revokedByPeerBus.collect {
                refreshPeerList()
                if (peerStore.list().isEmpty()) {
                    state.value = AdvertiseState.Idle
                }
            }
        }
        earbudsPollJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    localEarbudsState.value =
                        com.vortex.a3.core.earbuds.EarbudsDetector
                            .readConnectedEarbuds(applicationContext)
                } catch (t: Throwable) {
                    android.util.Log.e("VortexEarbuds", "poll threw", t)
                }
                delay(4000)
            }
        }
        lifecycleScope.launch {
            var wasActive = false
            com.vortex.a3.core.earbuds.EarbudsSwitchHolder.state.collect { s ->
                val active = s !is com.vortex.a3.core.earbuds.SwitchState.Idle &&
                    s !is com.vortex.a3.core.earbuds.SwitchState.Failed
                if (wasActive && !active) {
                    listOf(50L, 400L, 1000L).forEach { ms ->
                        lifecycleScope.launch {
                            delay(ms)
                            try {
                                localEarbudsState.value =
                                    com.vortex.a3.core.earbuds.EarbudsDetector
                                        .readConnectedEarbuds(applicationContext)
                            } catch (_: Throwable) {  }
                        }
                    }
                    VortexService.requestLanNudge()
                }
                wasActive = active
            }
        }
    }

    private fun buildUiState(): MainUiState = MainUiState(
        advertise = state,
        identity = identityState,
        handshake = handshakeState,
        reconnect = reconnectState,
        peers = peerListState,
        peerStates = peerStatesState,
        peerLastSeen = peerLastSeenState,
        nowTick = nowTickState,
        localEarbuds = localEarbudsState,
        hasSavedEarbuds = savedEarbudsExists,
        picker = earbudsPickerState,
        pendingApproval = pendingApproval,
        autostartHintDismissed = autostartHintDismissed,
        showNotifAccessDialog = showNotifAccessDialog,
        showAutostartDialog = showAutostartDialog,
        bluetoothOff = bluetoothOff,
    )

    private fun buildActions(): VortexActions = VortexActions(
        onForgetPeer = ::onForgetPeerClicked,
        onOpenAutostart = ::onOpenAutostartSettings,
        onDismissAutostartHint = ::dismissAutostartHint,
        onRequestBatteryWhitelist = ::onRequestBatteryWhitelist,
        onOpenEarbudsPicker = ::openEarbudsPicker,
        onPickEarbud = ::pickEarbud,
        onRescanEarbuds = ::rescanEarbudsPicker,
        onClosePicker = ::closeEarbudsPicker,
        onRemoveSavedEarbuds = ::removeSavedEarbuds,
        onToggleLaptopLock = ::toggleLaptopLock,
        onSuspendLaptop = ::suspendLaptop,
        onShutdownLaptop = ::shutdownLaptop,
        onApprove = ::onApproveClicked,
        onReject = ::onRejectClicked,
        onOpenNotificationAccess = ::onOpenNotificationAccess,
        onOpenScreenControl = ::onOpenAccessibilitySettings,
        onEnableBluetooth = ::onEnableBluetooth,
        isAggressiveOem = isAggressiveOemRom(),
        isIgnoringBatteryOptimizations = ::isIgnoringBatteryOptimizations,
    )

    private fun selectLaunchMode() {
        val autoAdv = BuildConfig.DEBUG && intent.getBooleanExtra("auto_advertise", false)
        val trustExists = peerStore.list().isNotEmpty()
        if (trustExists && !autoAdv) {
            VortexService.start(applicationContext)
            state.value = AdvertiseState.TrustedPresence
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
        } else if (autoAdv) {
            onStartClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        bluetoothOff.value = isBluetoothOff()
        if (!btReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            btReceiverRegistered = true
        }
        if (peerStore.list().isEmpty() && !advertiser.isAdvertising()) {
            onStartClicked()
        }
        val trust = peerStore.list().isNotEmpty()
        if (trust && isNotificationAccessGranted()) showNotifAccessDialog.value = false
        val dialogAlreadyUp = showAutostartDialog.value || showNotifAccessDialog.value
        if (trust && !dialogAlreadyUp) {
            val autostartPending = isAggressiveOemRom() &&
                !settingsPrefs.getBoolean("autostart_prompt_shown", false)
            when {
                autostartPending -> {
                    settingsPrefs.edit().putBoolean("autostart_prompt_shown", true).apply()
                    showAutostartDialog.value = true
                }
                !isNotificationAccessGranted() -> showNotifAccessDialog.value = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (btReceiverRegistered) {
            try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {  }
            btReceiverRegistered = false
        }
        if (peerStore.list().isEmpty()) {
            advertiser.stopAll()
            gattServer.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        earbudsPollJob?.cancel()
        earbudsPollJob = null
        val pending = pendingApproval.value
        val orch = pairingOrchestrator
        if (pending != null && orch != null) {
            val frame = orch.buildLocalApprovalFrame(pending.device, approve = false)
            if (frame != null) {
                gattServer.sendPairingControl(pending.device, frame)
                orch.commitLocalDecision(pending.device, approve = false)
            }
        }
        if (peerStore.list().isEmpty()) {
            advertiser.stopAll()
            gattServer.stop()
            lanServer?.stop()
        }
    }

























}



const val LAPTOP_STALE_MS: Long = 30_000

private val CardCorner = com.vortex.a3.ui.components.CardCorner
private val CardHeight = com.vortex.a3.ui.components.CardHeight



@Suppress("unused")
private val _ignored: BluetoothDevice? = null
