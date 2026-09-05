package com.vortex.a3.core.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import com.vortex.a3.core.ble.FRAME_HEADER_LEN
import com.vortex.a3.core.ble.Frame
import com.vortex.a3.core.ble.FrameSub
import com.vortex.a3.core.ble.FrameType
import com.vortex.a3.core.ble.MAX_FRAME_PAYLOAD
import com.vortex.a3.core.crypto.NoiseRunner
import com.vortex.a3.core.identity.IdentityRecord
import com.vortex.a3.core.storage.PeerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

sealed class LanServerMode {
    data class PairingWindow(val instanceId: ByteArray) : LanServerMode() {
        init { require(instanceId.size == 8) { "instanceId must be 8 bytes" } }
        override fun equals(other: Any?): Boolean =
            other is PairingWindow && instanceId.contentEquals(other.instanceId)
        override fun hashCode(): Int = instanceId.contentHashCode()
    }

    object TrustedRuntime : LanServerMode()
}

class LanServer(
    private val context: Context,
    private val identity: IdentityRecord,
    private val peerStore: PeerStore,
) {

    var localAppStateProvider: () -> com.vortex.a3.core.appstate.AppState = {
        com.vortex.a3.core.appstate.AppState(
            deviceClass = com.vortex.a3.core.appstate.DeviceClass.PHONE,
        )
    }

    var onPeerAppState: (ByteArray, com.vortex.a3.core.appstate.AppState) -> Unit = { _, _ -> }

    var bulkProvider: (key: String, peerHash: String) -> Pair<ByteArray, String>? = { _, _ -> null }

    var onBulkDelivered: (key: String, hash: String) -> Unit = { _, _ -> }

    var onFileServed: (token: String) -> Unit = { }

    var historyProvider: (key: String, sinceMs: Long) -> ByteArray? = { _, _ -> null }

    @Volatile
    var mirrorHandler: ((
        Socket, DataInputStream, DataOutputStream, CipherStatePair, ByteArray, Frame,
    ) -> Unit)? = null

    fun nudge() {
        val listener = registrationListener ?: return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        try {
            nsd.unregisterService(listener)
        } catch (_: Exception) {  }
        registrationListener = null
        scope.launch {
            kotlinx.coroutines.delay(150)
            reannounce()
        }
    }

    private suspend fun reannounce() {
        val socket = serverSocket ?: return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = derivePrivateInstanceName()
            serviceType = currentServiceType()
            this.port = socket.localPort
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "NSD re-registered: ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "NSD re-register failed: $code")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            registrationListener = l
        } catch (e: Exception) {
            Log.w(TAG, "NSD re-register threw: ${e.message}")
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var acceptJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var perfLock: WifiManager.WifiLock? = null

    private fun acquirePerfLock() {
        if (perfLock?.isHeld == true) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            perfLock = wifi.createWifiLock(mode, "vortex:file-xfer").apply { acquire() }
        } catch (_: Exception) {}
    }

    private fun releasePerfLock() {
        if (lanHotJob?.isActive == true) return
        try { if (perfLock?.isHeld == true) perfLock?.release() } catch (_: Exception) {}
        perfLock = null
    }

    @Volatile
    private var lanHotJob: Job? = null

    @Volatile
    private var lanHotGen: Int = 0

    @Volatile
    private var bleLinked: Boolean = false

    fun keepLanHot(ms: Long = HOT_WINDOW_MS): Boolean {
        val first = lanHotJob?.isActive != true
        acquirePerfLock()
        acquireMulticast()
        if (first) {
            nudge()
            Log.i(TAG, "LAN hot: radio + mDNS held for an incoming file pull")
        }
        val gen = ++lanHotGen
        lanHotJob?.cancel()
        lanHotJob = scope.launch {
            kotlinx.coroutines.delay(ms)
            if (gen != lanHotGen) return@launch
            lanHotJob = null
            releasePerfLock()
            if (bleLinked) releaseMulticast()
            Log.i(TAG, "LAN hot window over (no file pull for ${ms}ms)")
        }
        return first
    }

    private val clientSlots = Semaphore(MAX_CONCURRENT_CLIENTS)

    @Volatile
    private var mode: LanServerMode = LanServerMode.TrustedRuntime

    fun start(mode: LanServerMode = LanServerMode.TrustedRuntime): Boolean {
        if (acceptJob != null) {
            Log.w(TAG, "already started")
            return true
        }
        this.mode = mode
        acquireMulticast()
        acceptJob = scope.launch { startInternal() }
        return true
    }

    private suspend fun startInternal() {
        val socket = try {
            ServerSocket(DEFAULT_PORT)
        } catch (e: Exception) {
            try {
                ServerSocket(0)
            } catch (e2: Exception) {
                Log.e(TAG, "failed to bind ServerSocket", e2)
                return
            }
        }
        val port = socket.localPort
        serverSocket = socket
        Log.i(TAG, "TCP listener on port $port")

        val nsd = context.getSystemService(NsdManager::class.java)
        val instanceName = derivePrivateInstanceName()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = currentServiceType()
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: ${info.serviceName} (${info.serviceType}) on port ${info.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "NSD register failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD unregister failed: $code")
            }
        }
        try {
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        } catch (e: Exception) {
            Log.e(TAG, "NSD registerService threw", e)
        }

        acceptLoop(socket)
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "could not acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticast() {
        val lock = multicastLock ?: return
        try { if (lock.isHeld) lock.release() } catch (_: Exception) {}
        multicastLock = null
        Log.i(TAG, "multicast lock released")
    }

    fun setBleLinked(linked: Boolean) {
        bleLinked = linked
        if (linked) {
            if (lanHotJob?.isActive != true) releaseMulticast()
        } else if (acceptJob != null) {
            acquireMulticast()
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        registrationListener?.let {
            try {
                context.getSystemService(NsdManager::class.java).unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "NSD unregister threw: ${e.message}")
            }
        }
        registrationListener = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        multicastLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
        }
        multicastLock = null
        Log.i(TAG, "LAN server stopped")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "accept threw: ${e.message}")
                return
            }
            try { client.tcpNoDelay = true } catch (_: Exception) {}
            try { client.sendBufferSize = 4 * 1024 * 1024 } catch (_: Exception) {}
            Log.i(TAG, "TCP accept from ${client.inetAddress.hostAddress}:${client.port}")
            if (!clientSlots.tryAcquire()) {
                Log.w(TAG, "client limit reached; dropping ${client.inetAddress.hostAddress}")
                try { client.close() } catch (_: Exception) {}
                continue
            }
            scope.launch {
                try {
                    handleClient(client)
                } finally {
                    clientSlots.release()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            try { client.soTimeout = HANDSHAKE_TIMEOUT_MS } catch (_: Exception) {}
            client.use { sock ->
                val input = DataInputStream(sock.getInputStream())
                val output = DataOutputStream(sock.getOutputStream())

                val msg1 = readFrame(input) ?: return
                if (msg1.type != FrameType.RECONNECT_HANDSHAKE
                    || msg1.sub != HANDSHAKE_MSG1) {
                    Log.w(TAG, "first frame not IK msg1: type=0x${"%02x".format(msg1.type)}")
                    return
                }

                val trustedList = try { peerStore.list() } catch (_: Exception) { emptyList() }
                if (trustedList.isEmpty()) {
                    Log.w(TAG, "no trusted peers — rejecting IK")
                    return
                }
                var handshake: HandshakeState? = null
                var peerPub: ByteArray? = null
                var trusted: com.vortex.a3.core.storage.TrustedPeer? = null
                var peerCounter: Long = 0L
                for (peer in trustedList) {
                    val candidate = HandshakeState(NoiseRunner.NOISE_IK, HandshakeState.RESPONDER)
                    val prologue = ByteArray(NoiseRunner.PROLOGUE_IK.size + 32)
                    System.arraycopy(NoiseRunner.PROLOGUE_IK, 0, prologue, 0,
                        NoiseRunner.PROLOGUE_IK.size)
                    System.arraycopy(peer.prs, 0, prologue, NoiseRunner.PROLOGUE_IK.size, 32)
                    candidate.setPrologue(prologue, 0, prologue.size)
                    candidate.localKeyPair.setPrivateKey(identity.staticPriv, 0)
                    candidate.start()
                    val readBuf = ByteArray(Noise.MAX_PACKET_LEN)
                    val ptLen = try {
                        candidate.readMessage(msg1.payload, 0, msg1.payload.size, readBuf, 0)
                    } catch (_: Exception) {
                        candidate.destroy()
                        -1
                    }
                    if (ptLen >= 0) {
                        val pub = ByteArray(32).also { candidate.remotePublicKey.getPublicKey(it, 0) }
                        if (pub.contentEquals(peer.peerStaticPub)) {
                            handshake = candidate
                            peerPub = pub
                            trusted = peer
                            if (ptLen >= 8) {
                                peerCounter = java.nio.ByteBuffer.wrap(readBuf, 0, 8)
                                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                                    .long
                            }
                            break
                        } else {
                            candidate.destroy()
                        }
                    }
                }
                if (handshake == null || peerPub == null || trusted == null) {
                    Log.w(TAG, "no trusted PRS accepted msg1; closing")
                    return
                }
                val localCounter = peerStore.loadCounter(peerPub)
                if (peerCounter < localCounter) {
                    Log.w(
                        TAG,
                        "possible trust rollback over LAN: peer=$peerCounter local=$localCounter",
                    )
                }
                val nextCounter = peerStore.bumpCounter(peerPub, peerCounter)

                val writeBuf = ByteArray(Noise.MAX_PACKET_LEN)
                val counterPayload = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putLong(nextCounter)
                    .array()
                val n = handshake.writeMessage(
                    writeBuf, 0, counterPayload, 0, counterPayload.size,
                )
                writeFrame(output, Frame(FrameType.RECONNECT_HANDSHAKE, HANDSHAKE_MSG2, writeBuf.copyOf(n)))
                val transcriptHash = handshake.handshakeHash.copyOf()
                Log.i(TAG, "IK over TCP complete; transcript=${transcriptHash.toHexPrefix()}")

                val pair: CipherStatePair = handshake.split()
                handshake.destroy()

                try { sock.soTimeout = IDLE_TIMEOUT_MS } catch (_: Exception) {}

                val peerPubFinal: ByteArray = peerPub

                val outLock = java.util.concurrent.locks.ReentrantLock()
                fun lockedWrite(frame: Frame) {
                    outLock.lock()
                    try { writeFrame(output, frame) } finally { outLock.unlock() }
                }
                fun lockedSealAndWrite(type: Byte, sub: Byte, plaintext: ByteArray) {
                    outLock.lock()
                    try {
                        val ct = aeadSeal(pair.sender, plaintext)
                        writeFrame(output, Frame(type, sub, ct))
                    } finally { outLock.unlock() }
                }

                val writer: suspend (com.vortex.a3.core.earbuds.AudioOpFrame) -> Result<Unit> =
                    { outFrame ->
                        try {
                            lockedSealAndWrite(FrameType.AUDIO_OP, 0x01, outFrame.toJsonBytes())
                            Result.success(Unit)
                        } catch (e: Exception) {
                            Log.w(TAG, "audio-op session write failed: ${e.message}")
                            Result.failure(e)
                        }
                    }

                val firstFrame = readFrame(input)
                val mh = mirrorHandler
                if (firstFrame != null && mh != null &&
                    com.vortex.a3.core.lan.MirrorSession.isMirrorStart(firstFrame)
                ) {
                    mh(sock, input, output, pair, transcriptHash, firstFrame)
                    return
                }

                try {
                var pending: Frame? = firstFrame
                while (true) {
                    val frame = pending ?: readFrame(input) ?: break
                    pending = null
                    when {
                        frame.type == FrameType.TRANSPORT_KEEPALIVE
                            && frame.sub == FrameSub.PING -> {
                            Log.i(TAG, "ping (${frame.payload.size} bytes); responding")
                            lockedWrite(
                                Frame(FrameType.TRANSPORT_KEEPALIVE, FrameSub.PONG, frame.payload.copyOf()),
                            )
                        }
                        frame.type == FrameType.TRANSPORT_APP_DATA -> {
                            val plain = runCatching {
                                aeadOpen(pair.receiver, frame.payload)
                            }.getOrNull()
                            if (plain == null) {
                                Log.w(TAG, "app-state AEAD decrypt failed")
                                continue
                            }
                            val peerState = com.vortex.a3.core.appstate.AppState
                                .fromJsonBytes(plain)
                            if (peerState != null) {
                                val budsLog = peerState.earbuds
                                    ?.let { "earbuds=${it.name}(battery=${it.battery}, connected=${it.connected})" }
                                    ?: "earbuds=none"
                                Log.i(
                                    TAG,
                                    "← app-state from peer (battery=${peerState.battery} " +
                                        "class=${peerState.deviceClass} $budsLog)",
                                )
                                try {
                                    onPeerAppState(peerPub, peerState)
                                } catch (e: Exception) {
                                    Log.w(TAG, "onPeerAppState listener threw: ${e.message}")
                                }
                            } else {
                                Log.w(TAG, "app-state JSON parse failed")
                            }
                            val local = localAppStateProvider()
                            lockedSealAndWrite(
                                FrameType.TRANSPORT_APP_DATA,
                                0x01,
                                local.toJsonBytes(),
                            )
                            Log.i(TAG, "→ app-state sent")
                        }
                        frame.type == FrameType.BULK_SYNC -> {
                            val plain = runCatching {
                                aeadOpen(pair.receiver, frame.payload)
                            }.getOrNull()
                            if (plain == null) {
                                Log.w(TAG, "bulk-sync AEAD decrypt failed")
                                continue
                            }
                            val req = runCatching {
                                org.json.JSONObject(String(plain, Charsets.UTF_8))
                            }.getOrNull()
                            if (req == null) {
                                Log.w(TAG, "bulk-sync request JSON parse failed")
                                continue
                            }
                            val status = org.json.JSONObject()
                            fun sendChunked(frameType: Byte, json: ByteArray) {
                                val total = ((json.size + BULK_CHUNK - 1) / BULK_CHUNK).coerceAtLeast(1)
                                for (idx in 0 until total) {
                                    val s = idx * BULK_CHUNK
                                    val e = minOf(s + BULK_CHUNK, json.size)
                                    val payload = java.io.ByteArrayOutputStream().apply {
                                        write((total ushr 8) and 0xFF); write(total and 0xFF)
                                        write((idx ushr 8) and 0xFF); write(idx and 0xFF)
                                        write(json, s, e - s)
                                    }.toByteArray()
                                    lockedSealAndWrite(frameType, 0x00, payload)
                                }
                            }
                            for (key in req.keys()) {
                                if (key == "clipboard_image") {
                                    val token = req.optString(key, "")
                                    val png = com.vortex.a3.core.clipboard.ClipboardImageStore
                                        .getByToken(token)
                                    if (png == null) {
                                        Log.i(TAG, "bulk-sync: clipboard_image token=$token not found")
                                        status.put(key, "nomatch")
                                    } else {
                                        acquirePerfLock()
                                        try { sendChunked(FrameType.CLIPBOARD_IMAGE, png) }
                                        finally { releasePerfLock() }
                                        Log.i(TAG, "bulk-sync: clipboard_image sent (${png.size} bytes)")
                                        status.put(key, "sent")
                                    }
                                    continue
                                }
                                if (key == "clipboard_file") {
                                    val token = req.optString(key, "")
                                    val blob = com.vortex.a3.core.clipboard.ClipboardBlobStore
                                        .getByToken(token)
                                    if (blob == null) {
                                        Log.i(TAG, "bulk-sync: clipboard_file token=$token not found")
                                        status.put(key, "nomatch")
                                    } else {
                                        keepLanHot()
                                        sendChunked(FrameType.CLIPBOARD_FILE, blob)
                                        Log.i(TAG, "bulk-sync: clipboard_file sent (${blob.size} bytes)")
                                        status.put(key, "sent")
                                        try { onFileServed(token) } catch (e: Exception) {
                                            Log.w(TAG, "onFileServed listener threw: ${e.message}")
                                        }
                                    }
                                    continue
                                }
                                val historyFrameType = when (key) {
                                    "sms_history" -> FrameType.SMS_THREAD
                                    "call_log_history" -> FrameType.CALL_LOG_HISTORY
                                    else -> null
                                }
                                if (historyFrameType != null) {
                                    val since = req.optString(key, "").toLongOrNull() ?: 0L
                                    val json = try {
                                        historyProvider(key, since)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "bulk-sync: $key history provider threw (permission denied?): ${e.message}")
                                        status.put(key, "error")
                                        continue
                                    }
                                    if (json == null) {
                                        Log.i(TAG, "bulk-sync: $key caught up (since=$since)")
                                        status.put(key, "match")
                                    } else {
                                        sendChunked(historyFrameType, json)
                                        Log.i(TAG, "bulk-sync: $key sent (${json.size} bytes since=$since)")
                                        status.put(key, "sent")
                                    }
                                    continue
                                }
                                val peerHash = req.optString(key, "")
                                val frameType = when (key) {
                                    "contacts" -> FrameType.CONTACTS
                                    "call_log" -> FrameType.CALL_LOG
                                    "sms" -> FrameType.SMS
                                    "sms_ids" -> FrameType.SMS_IDS
                                    else -> {
                                        Log.w(TAG, "bulk-sync: unknown dataset '$key'")
                                        status.put(key, "unknown")
                                        continue
                                    }
                                }
                                val data = try {
                                    bulkProvider(key, peerHash)
                                } catch (e: Exception) {
                                    Log.w(TAG, "bulk-sync: $key provider threw (permission denied?): ${e.message}")
                                    status.put(key, "error")
                                    continue
                                }
                                if (data == null) {
                                    Log.i(TAG, "bulk-sync: $key matches peer cache; nothing to send")
                                    status.put(key, "match")
                                    onBulkDelivered(key, peerHash)
                                } else {
                                    val (json, hash) = data
                                    sendChunked(frameType, json)
                                    Log.i(TAG, "bulk-sync: $key sent (${json.size} bytes)")
                                    status.put(key, "sent")
                                    onBulkDelivered(key, hash)
                                }
                            }
                            lockedSealAndWrite(
                                FrameType.BULK_SYNC, 0x02,
                                status.toString().toByteArray(Charsets.UTF_8),
                            )
                        }
                        frame.type == FrameType.AUDIO_OP -> {
                            val plain = runCatching {
                                aeadOpen(pair.receiver, frame.payload)
                            }.getOrNull()
                            if (plain == null) {
                                Log.w(TAG, "audio-op AEAD decrypt failed")
                                continue
                            }
                            val audioFrame = com.vortex.a3.core.earbuds.AudioOpFrame
                                .fromJsonBytes(plain)
                            if (audioFrame == null) {
                                Log.w(TAG, "audio-op JSON parse failed")
                                continue
                            }
                            Log.i(TAG, "← audio-op ${audioFrame.op} nonce=${audioFrame.nonce}")
                            com.vortex.a3.core.earbuds.EarbudsSwitchHolder
                                .setSessionWriter(peerPubFinal, writer)
                            try {
                                com.vortex.a3.core.earbuds.EarbudsSwitchHolder
                                    .onIncoming(peerPub, audioFrame)
                            } catch (e: Exception) {
                                Log.w(TAG, "audio-op dispatch threw: ${e.message}")
                            }
                        }
                        frame.type == FrameType.FILE_PUSH_OFFER -> {
                            val plain = runCatching {
                                aeadOpen(pair.receiver, frame.payload)
                            }.getOrNull()
                            if (plain == null) {
                                Log.w(TAG, "file-push offer AEAD decrypt failed")
                                continue
                            }
                            val names = ArrayList<String>()
                            val extracts = ArrayList<Boolean>()
                            var total = 0L
                            runCatching {
                                val obj = org.json.JSONObject(String(plain, Charsets.UTF_8))
                                total = obj.optLong("total", 0L)
                                val arr = obj.optJSONArray("files")
                                if (arr != null) {
                                    for (i in 0 until arr.length()) {
                                        val fo = arr.getJSONObject(i)
                                        names.add(fo.optString("name", "vortex-file"))
                                        extracts.add(fo.optBoolean("extract", false))
                                    }
                                } else {
                                    names.add(obj.optString("name", "vortex-file"))
                                    extracts.add(false)
                                    total = obj.optLong("bytes", 0L)
                                }
                            }
                            if (names.isEmpty()) {
                                Log.w(TAG, "file-push offer: empty/invalid")
                                continue
                            }
                            val label = if (names.size == 1) names[0] else "${names.size} files"
                            Log.i(TAG, "← file-push offer: $label ($total bytes); asking user")
                            val accepted = FileConsent.request(context, label, names.size, total)
                            lockedSealAndWrite(
                                FrameType.FILE_PUSH_DECISION, 0x00,
                                byteArrayOf(if (accepted) 1 else 0),
                            )
                            if (!accepted) {
                                Log.i(TAG, "file-push declined")
                                continue
                            }
                            var saved = 0
                            var aborted = false
                            for ((fi, name) in names.withIndex()) {
                                if (aborted) break
                                val extract = extracts.getOrElse(fi) { false }
                                val sink = IncomingFileSink(context, name, extract)
                                var finished = false
                                try {
                                    while (!finished) {
                                        val chunkFrame = readFrame(input)
                                        if (chunkFrame == null) {
                                            aborted = true
                                            break
                                        }
                                        if (chunkFrame.type != FrameType.FILE_PUSH) {
                                            Log.w(TAG, "file-push: unexpected frame 0x${"%02x".format(chunkFrame.type)}; aborting")
                                            pending = chunkFrame
                                            aborted = true
                                            break
                                        }
                                        val cplain = runCatching {
                                            aeadOpen(pair.receiver, chunkFrame.payload)
                                        }.getOrNull()
                                        if (cplain == null) {
                                            aborted = true
                                            break
                                        }
                                        if (cplain.size < 4) {
                                            aborted = true
                                            break
                                        }
                                        val (totalChunks, chunkIdx, dataOffset) = if (
                                            cplain.size >= 10 &&
                                            cplain[0] == 0xFF.toByte() &&
                                            cplain[1] == 0xFF.toByte()
                                        ) {
                                            val t = ((cplain[2].toLong() and 0xFF) shl 24) or
                                                    ((cplain[3].toLong() and 0xFF) shl 16) or
                                                    ((cplain[4].toLong() and 0xFF) shl 8) or
                                                    (cplain[5].toLong() and 0xFF)
                                            val idx = ((cplain[6].toLong() and 0xFF) shl 24) or
                                                      ((cplain[7].toLong() and 0xFF) shl 16) or
                                                      ((cplain[8].toLong() and 0xFF) shl 8) or
                                                      (cplain[9].toLong() and 0xFF)
                                            Triple(t, idx, 10)
                                        } else {
                                            val t = ((cplain[0].toInt() and 0xFF) shl 8) or (cplain[1].toInt() and 0xFF)
                                            val idx = ((cplain[2].toInt() and 0xFF) shl 8) or (cplain[3].toInt() and 0xFF)
                                            Triple(t.toLong(), idx.toLong(), 4)
                                        }

                                        val chunkData = if (cplain.size > dataOffset) {
                                            cplain.copyOfRange(dataOffset, cplain.size)
                                        } else {
                                            ByteArray(0)
                                        }
                                        if (!sink.writeChunk(chunkData)) {
                                            aborted = true
                                            break
                                        }
                                        if (chunkIdx + 1 >= totalChunks) {
                                            finished = true
                                        }
                                    }
                                    if (finished && sink.finish()) {
                                        saved++
                                    } else {
                                        Log.w(TAG, "file-push '$name' incomplete; discarded")
                                    }
                                } finally {
                                    sink.close()
                                }
                            }
                            if (saved > 0) {
                                IncomingFile.notifyReceived(context, label, saved)
                            }
                        }
                        else -> Log.i(TAG, "post-IK frame type=0x${"%02x".format(frame.type)} ignored")
                    }
                }
                } finally {
                    com.vortex.a3.core.earbuds.EarbudsSwitchHolder
                        .clearSessionWriter(peerPubFinal, writer)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "client handler error: ${e.message}")
        }
    }

    private fun readFrame(input: DataInputStream): Frame? {
        return try {
            val header = ByteArray(FRAME_HEADER_LEN)
            input.readFully(header)
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length > MAX_FRAME_PAYLOAD) return null
            val payload = ByteArray(length)
            if (length > 0) input.readFully(payload)
            val full = header + payload
            Frame.decode(full).getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: Frame) {
        output.write(frame.encode())
        output.flush()
    }

    private fun currentServiceType(): String = when (mode) {
        is LanServerMode.PairingWindow -> NSD_SERVICE_TYPE_PAIRING
        LanServerMode.TrustedRuntime -> NSD_SERVICE_TYPE_TRUSTED
    }

    private fun derivePrivateInstanceName(): String {
        return when (val m = mode) {
            is LanServerMode.PairingWindow -> "vortex-${m.instanceId.toHexShort()}"
            LanServerMode.TrustedRuntime -> {
                val peers = try { peerStore.list() } catch (_: Exception) { emptyList() }
                val prs = peers.firstOrNull()?.prs
                if (prs != null) {
                    val bucket = System.currentTimeMillis() / 1000L / NSD_ROTATION_SEC
                    val token = com.vortex.a3.core.crypto.Presence.deriveToken(prs, bucket)
                    "vortex-${token.toHexShort()}"
                } else {
                    val nonce = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
                    Log.w(TAG, "trusted-runtime mDNS without PRS; using random nonce")
                    "vortex-${nonce.toHexShort()}"
                }
            }
        }
    }

    private fun ByteArray.toHexShort(): String =
        joinToString("") { "%02x".format(it) }


    private fun aeadSeal(cipher: CipherState, plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + cipher.macLength)
        val n = cipher.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(n)
    }

    private fun aeadOpen(cipher: CipherState, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < cipher.macLength) {
            throw IllegalArgumentException("ciphertext shorter than MAC")
        }
        val out = ByteArray(ciphertext.size)
        val n = cipher.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(n)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHexPrefix(): String =
        take(4).joinToString("") { "%02x".format(it) } + "…"

    companion object {
        private const val TAG = "VortexLan"
        const val DEFAULT_PORT: Int = 51820
        const val NSD_SERVICE_TYPE_TRUSTED = "_vortex._tcp."
        const val NSD_SERVICE_TYPE_PAIRING = "_vortex-pair._tcp."
        const val NSD_INSTANCE_NAME = "vortex-android"
        const val HANDSHAKE_MSG1: Byte = 0x01
        const val HANDSHAKE_MSG2: Byte = 0x02
        const val HANDSHAKE_TIMEOUT_MS: Int = 15_000
        const val IDLE_TIMEOUT_MS: Int = 90_000
        const val MAX_CONCURRENT_CLIENTS: Int = 16

        const val HOT_WINDOW_MS: Long = 60_000

        const val BULK_CHUNK: Int = 60 * 1024
        const val NSD_ROTATION_SEC: Long = 60L
    }
}
