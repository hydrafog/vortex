package com.vortex.a3.service

import android.app.Service
import android.content.Context
import android.os.Build
import android.util.Log
import com.vortex.a3.core.ble.Advertiser
import com.vortex.a3.core.ble.GattServer
import android.bluetooth.BluetoothManager
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.identity.Platform
import com.vortex.a3.core.lan.LanServer
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import com.vortex.a3.core.storage.EncryptedPrefsIdentityStore
import com.vortex.a3.core.storage.EncryptedPrefsPeerStore
import com.vortex.a3.core.storage.PeerStore
import com.vortex.a3.core.storage.loadOrGenerate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

class VortexStack(internal val service: Service) : VortexNotification.Host {

    internal val ctx: Context get() = service.applicationContext
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob())

    private var advertiser: Advertiser? = null
    internal var gattServer: GattServer? = null
    internal val notificationOutbox = com.vortex.a3.core.notif.NotificationOutbox()
    internal val sentIconPkgs = java.util.Collections.synchronizedSet(HashSet<String>())
    private var clipboardListener: com.vortex.a3.core.clipboard.ClipboardListener? = null
    internal var wifiDirectTeardownJob: kotlinx.coroutines.Job? = null
    internal val ICON_CHUNK = 180
    internal val CONTACTS_CHUNK = 450
    private var mirrorRefreshJob: kotlinx.coroutines.Job? = null

    internal var contactsProvider: com.vortex.a3.core.contacts.ContactsProvider? = null
    internal var callLogProvider: com.vortex.a3.core.calllog.CallLogProvider? = null
    internal var smsProvider: com.vortex.a3.core.sms.SmsProvider? = null
    internal val companionSendMutex = kotlinx.coroutines.sync.Mutex()
    internal var lanServer: LanServer? = null

    internal val pendingOffers =
        java.util.concurrent.ConcurrentHashMap<String, PendingOffer>()
    internal var offerRetryJob: kotlinx.coroutines.Job? = null
    internal val offerRetryKick = newOfferRetryKick()
    internal var lanWarmJob: kotlinx.coroutines.Job? = null
    @Volatile internal var offerUnreachableToasted: Boolean = false
    @Volatile internal var offerSeq: Long = 0L
    private var pairingOrchestrator: PairingOrchestrator? = null
    private var reconnectOrchestrator: ReconnectOrchestrator? = null
    internal var callFlowOrchestrator: com.vortex.a3.core.call.CallFlowOrchestrator? = null
    internal val callController by lazy { com.vortex.a3.core.call.CallController(ctx) }
    @Volatile internal var lastHandledCallControlSeq: Long = 0L

    internal var mediaHandoff: com.vortex.a3.core.media.MediaHandoffCoordinator? = null
    @Volatile internal var localMediaPlaying: Boolean = false
    @Volatile internal var lastPeerMediaPlaying: Boolean = false
    internal var audioCtl: com.vortex.a3.core.earbuds.AudioDeviceController? = null
    @Volatile internal var latestPeerState: com.vortex.a3.core.appstate.AppState? = null
    @Volatile internal var latestPeerStateAtMs: Long = 0L
    internal var fakeCallReceiver: android.content.BroadcastReceiver? = null
    internal var wifiDirectReceiver: android.content.BroadcastReceiver? = null
    private var identity: IdentityRecord? = null
    internal lateinit var peerStore: PeerStore
    internal var onStateChanged: () -> Unit = {}

    @Volatile internal var lastBleStatePushAtMs: Long = 0L

    fun isStarted(): Boolean = advertiser != null

    override fun phoneOwnsBuds(): Boolean {
        val mac = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx)?.address
        return mac != null && audioCtl?.isConnected(mac) == true
    }
    override fun peerState(): com.vortex.a3.core.appstate.AppState? = latestPeerState
    override fun peerStateAgeMs(): Long {
        val at = latestPeerStateAtMs
        return if (at == 0L) Long.MAX_VALUE
        else android.os.SystemClock.elapsedRealtime() - at
    }
    override fun phoneEarbudsBattery(): Int? = try {
        com.vortex.a3.core.earbuds.EarbudsDetector.readConnectedEarbuds(ctx)?.battery
    } catch (_: Throwable) { null }

    fun start(onStateChanged: () -> Unit): Boolean {
        this.onStateChanged = onStateChanged
        val identityStore = EncryptedPrefsIdentityStore(ctx)
        peerStore = EncryptedPrefsPeerStore(ctx)
        val identity = identityStore.loadOrGenerate(Platform.Android)

        com.vortex.a3.core.earbuds.EarbudsSwitchHolder.init(ctx, peerStore)

        pairingOrchestrator = null

        this.identity = identity
        if (!startBleComponents(identity)) {
            return false
        }

        val callFlow = startCallFlow()
        startMediaFollow()
        registerFakeCallReceiver(callFlow)
        registerWifiDirectReceiver()
        watchSwitchStateForCall(callFlow)
        forwardNotifications()
        startClipboardOutbound()
        forwardLiveActivities()
        forwardCallEvents()
        forwardHandoff()
        startContacts()
        startCallLog()
        startSms()

        com.vortex.a3.core.mirror.LaptopMirror.onRequestChanged = {
            lanServer?.nudge()
            pushStateViaBle()
        }
        com.vortex.a3.core.mirror.LaptopMirror.onCastFailed = { reason ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(
                        ctx,
                        "Can't show the laptop screen: $reason",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (t: Throwable) {
                    Log.w(TAG, "cast-failure toast suppressed: ${t.message}")
                }
            }
        }

        startLanServer(identity)
        return true
    }

    fun stop() {
        clipboardListener?.stop()
        wifiDirectTeardownJob?.cancel()
        com.vortex.a3.core.lan.WifiDirect.stop()
        scope.cancel()
        advertiser?.stopAll()
        gattServer?.stop()
        lanServer?.stop()
        callFlowOrchestrator?.stop()
        mediaHandoff?.stop()
        fakeCallReceiver?.let { try { service.unregisterReceiver(it) } catch (_: Exception) {} }
        wifiDirectReceiver?.let { try { service.unregisterReceiver(it) } catch (_: Exception) {} }
        if (VortexService.liveLan === lanServer) VortexService.liveLan = null
        if (VortexService.liveStack === this) VortexService.liveStack = null
    }

    fun refreshCallFlow() {
        if (callFlowOrchestrator?.start() == true) {
            Log.i(TAG, "call-flow refreshed; telephony listener now active")
        }
    }

    private fun startMediaFollow() {
        val ctl = com.vortex.a3.core.earbuds.AudioDeviceController(ctx)
        audioCtl = ctl
        val media = com.vortex.a3.core.media.MediaHandoffCoordinator(
            context = ctx,
            weOwnBuds = {
                val mac = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx)?.address
                mac != null && ctl.isConnected(mac)
            },
            peerHoldsBuds = {
                val fresh = android.os.SystemClock.elapsedRealtime() - latestPeerStateAtMs < PEER_FRESH_MS
                fresh && latestPeerState?.earbuds?.connected == true
            },
            isCallActive = { VortexService.callGateActive() },
            requestGrab = { grabBudsToPhone() },
            requestReturnToPeer = { handBudsToLaptop() },
            onMediaPlayingChanged = { playing ->
                localMediaPlaying = playing
                lanServer?.nudge()
            },
        )
        media.start()
        mediaHandoff = media
        com.vortex.a3.core.media.SmartSwitchSetting.init(ctx)
        com.vortex.a3.core.notif.NotificationMirrorSetting.init(ctx)
        com.vortex.a3.core.clipboard.ClipboardSyncSetting.init(ctx)
        com.vortex.a3.core.lan.FileAutoAcceptSetting.init(ctx)
        scope.launch {
            com.vortex.a3.core.media.SmartSwitchSetting.enabled.collect { on ->
                media.smartSwitchEnabled = on
            }
        }
        val clipListener = com.vortex.a3.core.clipboard.ClipboardListener(ctx)
        clipboardListener = clipListener
        scope.launch {
            com.vortex.a3.core.clipboard.ClipboardSyncSetting.enabled.collect { on ->
                if (on) clipListener.start() else clipListener.stop()
            }
        }
    }

    private fun watchSwitchStateForCall(callFlow: com.vortex.a3.core.call.CallFlowOrchestrator) {
        scope.launch {
            com.vortex.a3.core.earbuds.EarbudsSwitchHolder.state.collect { s ->
                if (s is com.vortex.a3.core.earbuds.SwitchState.AlmostDone ||
                    s == com.vortex.a3.core.earbuds.SwitchState.Idle) {
                    callFlow.notifyBudsConnected()
                }
            }
        }
    }

    @Volatile internal var latestContactsJson: ByteArray? = null
    @Volatile internal var latestContactsHash: String? = null

    @Volatile internal var lanDeliveredContactsHash: String? = null

    @Volatile internal var latestCallLogJson: ByteArray? = null
    @Volatile internal var latestCallLogHash: String? = null
    @Volatile internal var lanDeliveredCallLogHash: String? = null

    @Volatile internal var latestSmsJson: ByteArray? = null
    @Volatile internal var latestSmsHash: String? = null
    @Volatile internal var lanDeliveredSmsHash: String? = null

    private fun startBleComponents(identity: IdentityRecord): Boolean {
        val reconnect = ReconnectOrchestrator(identity, peerStore)
        reconnectOrchestrator = reconnect

        val server = GattServer(
            ctx,
            pairingOrchestrator = null,
            reconnectOrchestrator = reconnect,
        )
        if (!server.start()) {
            Log.e(TAG, "failed to start GATT server")
            return false
        }
        gattServer = server
        startNotesSync() // notes/todos bidirectional sync (NOTES_SYNC)

        server.onAudioOpReceived = { peerPub, jsonBytes ->
            val frame = com.vortex.a3.core.earbuds.AudioOpFrame.fromJsonBytes(jsonBytes)
            if (frame == null) {
                Log.w(TAG, "BLE-write AudioOp: malformed payload from ${peerPub.toHexPrefix()}")
            } else {
                scope.launch {
                    try {
                        com.vortex.a3.core.earbuds.EarbudsSwitchHolder.onIncoming(peerPub, frame)
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE-write AudioOp dispatch failed: ${e.message}")
                    }
                }
            }
        }
        server.onNotificationReceived = { _, jsonBytes ->
            val m = com.vortex.a3.core.notif.NotificationMirror.fromJsonBytes(jsonBytes)
            if (m == null) {
                Log.w(TAG, "BLE-write NOTIFICATION: malformed payload")
            } else if (m.resync) {
                com.vortex.a3.core.media.MediaNotificationListenerService
                    .resendMissing(m.knownKeys.toHashSet())
            } else if (m.dismiss) {
                if (m.key.isNotEmpty()) {
                    com.vortex.a3.core.media.MediaNotificationListenerService.dismissByKey(m.key)
                }
            } else if (m.invokeIndex >= 0) {
                handleNotifInvoke(m)
            } else if (com.vortex.a3.core.notif.NotificationMirrorSetting.isShowPeer()) {
                com.vortex.a3.core.notif.IncomingNotificationDisplay.show(ctx, m)
            }
        }

        server.onClipboardReceived = { _, jsonBytes ->
            if (com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) {
                try {
                    val o = org.json.JSONObject(String(jsonBytes, Charsets.UTF_8))
                    val text = o.optString("text").trim()
                    if (text.isNotEmpty()) {
                        com.vortex.a3.core.clipboard.ClipboardSyncGuard.markApplied(
                            com.vortex.a3.core.clipboard.ClipboardSyncGuard.sig(text)
                        )
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Vortex", text)
                        )
                        Log.i(TAG, "clipboard: synced from laptop (${text.length} chars)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onClipboardReceived parse/apply failed: ${e.message}")
                }
            }
        }

        val clipboardTextAsm = com.vortex.a3.core.clipboard.ClipboardImageAssembler()
        server.onClipboardTextChunk = { _, chunk ->
            if (com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) {
                try {
                    val bytes = clipboardTextAsm.add(chunk)
                    if (bytes != null) {
                        val text = String(bytes, Charsets.UTF_8).trim()
                        if (text.isNotEmpty()) {
                            com.vortex.a3.core.clipboard.ClipboardSyncGuard.markApplied(
                                com.vortex.a3.core.clipboard.ClipboardSyncGuard.sig(text)
                            )
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Vortex", text)
                            )
                            Log.i(TAG, "clipboard: long text synced from laptop (${text.length} chars)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onClipboardTextChunk apply failed: ${e.message}")
                }
            }
        }

        val clipboardImageAsm = com.vortex.a3.core.clipboard.ClipboardImageAssembler()
        server.onClipboardImageChunk = { _, chunk ->
            if (com.vortex.a3.core.clipboard.ClipboardSyncSetting.isEnabled()) {
                try {
                    val png = clipboardImageAsm.add(chunk)
                    if (png != null) {
                        val dir = java.io.File(ctx.cacheDir, "clipboard").apply { mkdirs() }
                        val file = java.io.File(dir, "in_${System.nanoTime()}.png")
                        file.writeBytes(png)
                        dir.listFiles { f -> f.name.startsWith("in_") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(8)
                            ?.forEach { it.delete() }
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx, "${ctx.packageName}.clipboard", file
                        )
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val clip = android.content.ClipData.newUri(ctx.contentResolver, "Vortex", uri)
                        cm?.setPrimaryClip(clip)
                        Log.i(TAG, "clipboard: image synced from laptop (${png.size} bytes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onClipboardImageChunk apply failed: ${e.message}")
                }
            }
        }

        server.onStateReceived = { peerPub, jsonBytes ->
            val state = com.vortex.a3.core.appstate.AppState.fromJsonBytes(jsonBytes)
            if (state == null) {
                Log.w(TAG, "BLE-write STATE: malformed AppState payload")
            } else {
                handlePeerAppState(peerPub, state)
            }
        }

        server.onCallControlReceived = { _, jsonBytes ->
            val ctrl = com.vortex.a3.core.call.CallControl.fromJsonBytes(jsonBytes)
            if (ctrl == null) {
                Log.w(TAG, "BLE-write CALL_CONTROL: malformed payload")
            } else {
                handleCallControl(ctrl)
            }
        }

        server.onPeerDisconnected = { _ ->
            mirrorRefreshJob?.cancel()
            lanServer?.setBleLinked(false)
            advertiser?.kickRotation()
        }

        server.onAudioSignalSubscribed = { _ ->
            lanServer?.setBleLinked(true)
            advertiser?.kickRotation()
            pushStateViaBle()
            com.vortex.a3.core.notes.NoteSync.markDirty()
            kickOfferRetry()
            sentIconPkgs.clear()
            for (la in com.vortex.a3.core.media.MediaNotificationListenerService.activeLiveActivities()) {
                VortexService.liveActivityBus.tryEmit(la)
            }
            mirrorRefreshJob?.cancel()
            mirrorRefreshJob = scope.launch {
                kotlinx.coroutines.delay(MIRROR_REFRESH_SETTLE_MS)
                contactsProvider?.refresh()
                callLogProvider?.refresh()
                smsProvider?.refresh()
            }
            scope.launch {
                for (peer in peerStore.list()) {
                    val peerPub = peer.peerStaticPub
                    notificationOutbox.flush(peerPub.notifHex()) { mirror ->
                        gattServer?.sendNotificationEncrypted(peerPub, mirror.toJsonBytes()) ?: false
                    }
                }
            }
        }

        reconnect.addListener { outcome ->
            server.registerAudioSession(
                outcome.peerStaticPub,
                outcome.device,
                outcome.ciphers.sender,
                outcome.ciphers.receiver,
            )
            val peerPub = outcome.peerStaticPub.copyOf()
            val bleWriter: suspend (com.vortex.a3.core.earbuds.AudioOpFrame) -> Result<Unit> = { f ->
                val ok = server.sendAudioOpEncrypted(peerPub, f.toJsonBytes())
                if (ok) Result.success(Unit)
                else Result.failure(IllegalStateException("BLE audio-signal not ready (no cipher / no subscriber)"))
            }
            com.vortex.a3.core.earbuds.EarbudsSwitchHolder.setBleWriter(peerPub, bleWriter)
            Log.i(TAG, "P2.13: BLE audio writer registered for peer=${peerPub.take(4).joinToString("") { "%02x".format(it) }}…")
            latestPeerState?.let { st ->
                VortexService.peerStateBus.tryEmit(peerPub.toHex() to st)
            }
        }

        val adv = Advertiser(ctx)
        adv.fastModeProvider = provider@{
            val srv = gattServer ?: return@provider false
            !srv.hasActiveConnection() &&
                android.os.SystemClock.elapsedRealtime() - srv.lastDisconnectAtMs <
                FAST_ADV_WINDOW_MS
        }
        val firstPeer = peerStore.list().firstOrNull()
        if (firstPeer != null) {
            adv.startTrustedPresence(
                prs = firstPeer.prs,
                scope = scope,
                rotationWindowSec = 60L,
                onError = { reason -> Log.w(TAG, "presence adv error: $reason") },
            )
            Log.i(TAG, "trusted-presence advertising started (have ${peerStore.list().size} peer(s))")
        } else {
            Log.i(TAG, "no trust — service idle, awaiting pairing")
        }
        advertiser = adv
        return true
    }

    fun restartBleComponents() {
        val id = identity ?: run {
            Log.w(TAG, "BT re-enabled but no identity yet; skipping BLE restart")
            return
        }
        Log.i(TAG, "Bluetooth re-enabled — restarting BLE advertiser + GATT server")
        try { advertiser?.stopAll() } catch (_: Exception) {}
        try { gattServer?.stop() } catch (_: Exception) {}
        advertiser = null
        gattServer = null
        if (!startBleComponents(id)) {
            Log.e(TAG, "BLE restart failed to reopen GATT server")
        }
    }

    fun handBudsToLaptop(): Boolean {
        val firstPeer = peerStore.list().firstOrNull() ?: return false
        val saved = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx) ?: return false
        com.vortex.a3.core.earbuds.EarbudsSwitchHolder.claim(firstPeer.peerStaticPub, saved.address)
        VortexService.pendingAudioClaim.set(true)
        lanServer?.nudge()
        return true
    }

    fun grabBudsToPhone(): Boolean {
        if (!isBluetoothOn()) {
            Log.i(TAG, "grab skipped — phone Bluetooth is OFF")
            return false
        }
        val firstPeer = peerStore.list().firstOrNull() ?: return false
        val saved = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx) ?: return false
        return com.vortex.a3.core.earbuds.EarbudsSwitchHolder
            .request(firstPeer.peerStaticPub, saved.address)
    }

    fun pushStateViaBle() {
        val peerPub = peerStore.list().firstOrNull()?.peerStaticPub ?: return
        val server = gattServer ?: return
        scope.launch {
            try {
                val json = buildLocalAppState().toJsonBytes()
                if (server.sendStateEncrypted(peerPub, json)) {
                    lastBleStatePushAtMs = android.os.SystemClock.elapsedRealtime()
                    Log.i(TAG, "state pushed over BLE")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushStateViaBle failed: ${e.message}")
            }
        }
    }

    private fun startLanServer(identity: IdentityRecord) {
        val lan = LanServer(ctx, identity, peerStore).also {
            it.start(com.vortex.a3.core.lan.LanServerMode.TrustedRuntime)
        }
        VortexService.liveLan = lan
        VortexService.liveStack = this
        lan.localAppStateProvider = { buildLocalAppState() }
        lan.onPeerAppState = { peerPub, state -> handlePeerAppState(peerPub, state) }
        lan.bulkProvider = provider@{ key, peerHash ->
            when (key) {
                "contacts" -> {
                    val json = latestContactsJson ?: return@provider null
                    val hash = latestContactsHash ?: return@provider null
                    if (hash == peerHash) null else Pair(json, hash)
                }
                "call_log" -> {
                    val json = latestCallLogJson ?: return@provider null
                    val hash = latestCallLogHash ?: return@provider null
                    if (hash == peerHash) null else Pair(json, hash)
                }
                "sms" -> {
                    val json = latestSmsJson ?: return@provider null
                    val hash = latestSmsHash ?: return@provider null
                    if (hash == peerHash) null else Pair(json, hash)
                }
                "sms_ids" -> {
                    if (!com.vortex.a3.core.sms.SmsMirrorSetting.isEnabled()) return@provider null
                    val ids = smsProvider?.readAllIds() ?: return@provider null
                    val json = org.json.JSONArray(ids).toString().toByteArray(Charsets.UTF_8)
                    val hash = sha256Hex(json)
                    if (hash == peerHash) null else Pair(json, hash)
                }
                else -> null
            }
        }
        lan.onBulkDelivered = { key, hash ->
            when (key) {
                "contacts" -> lanDeliveredContactsHash = hash
                "call_log" -> lanDeliveredCallLogHash = hash
                "sms" -> lanDeliveredSmsHash = hash
            }
        }
        lan.historyProvider = { key, since ->
            when {
                key == "sms_history" && com.vortex.a3.core.sms.SmsMirrorSetting.isEnabled() ->
                    smsProvider?.readHistorySince(since, 5000)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { com.vortex.a3.core.sms.smsToJsonBytes(it) }
                key == "call_log_history" && com.vortex.a3.core.calllog.CallLogMirrorSetting.isEnabled() ->
                    callLogProvider?.readHistorySince(since, 5000)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { com.vortex.a3.core.calllog.callLogToJsonBytes(it) }
                else -> null
            }
        }
        lan.mirrorHandler = { sock, input, output, pair, handshakeHash, firstFrame ->
            val laptopIp = sock.inetAddress?.hostAddress
            if (laptopIp == null) {
                Log.w(TAG, "mirror: no laptop IP; dropping session")
            } else {
                com.vortex.a3.core.lan.MirrorSession(
                    sock, input, output, pair, handshakeHash,
                    onStart = onStart@{ start ->
                        if (!MirrorConsent.beginPrompt()) {
                            Log.i(TAG, "mirror: duplicate START — consent already pending, ignoring")
                            return@onStart
                        }
                        val beginStream = {
                            val token = MirrorConsent.resultData
                            if (token != null) {
                                val key = com.vortex.a3.core.mirror.MirrorUdp.deriveMediaKey(handshakeHash)
                                ScreenMirrorService.start(
                                    ctx, MirrorConsent.resultCode, token,
                                    laptopIp, start.udpPort, start.w, start.h, start.fps, start.bitrate, key,
                                )
                                MirrorConsent.clear()
                            } else {
                                Log.w(TAG, "mirror START but consent denied; no stream")
                            }
                        }
                        MirrorConsent.onResult = { granted -> if (granted) beginStream() }
                        com.vortex.a3.core.mirror.MirrorRequestNotification.prompt(ctx)
                    },
                    onInput = { pkt -> VortexInputService.instance?.onPacket(pkt) },
                    onRequestKeyframe = { ScreenMirrorService.requestKeyframe(ctx) },
                    onStop = { ScreenMirrorService.stop(ctx) },
                ).run(firstFrame)
            }
        }
        lan.onFileServed = { token -> noteFileServed(token) }
        lanServer = lan
    }

    fun toggleAudio(onTarget: (String) -> Unit) {
        val mac = com.vortex.a3.core.earbuds.EarbudsStore.load(ctx)?.address ?: return
        val firstPeer = peerStore.list().firstOrNull() ?: return
        mediaHandoff?.noteManualSwitch()
        if (audioCtl?.isConnected(mac) == true) {
            Log.i(TAG, "notification: switch buds phone → laptop")
            onTarget("laptop")
            scope.launch { audioCtl?.disconnect(mac) }
            VortexService.pendingAudioClaim.set(true)
            lanServer?.nudge()
        } else {
            Log.i(TAG, "notification: switch buds laptop → phone")
            onTarget("phone")
            com.vortex.a3.core.earbuds.EarbudsSwitchHolder.request(firstPeer.peerStaticPub, mac)
        }
    }

    internal fun isBluetoothOn(): Boolean =
        service.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    companion object {
        internal const val TAG = "VortexStack"
        internal const val FAST_ADV_WINDOW_MS = 10 * 60_000L

        internal const val MIRROR_REFRESH_SETTLE_MS = 3_000L
        internal const val PEER_FRESH_MS = 30_000L
    }
}
