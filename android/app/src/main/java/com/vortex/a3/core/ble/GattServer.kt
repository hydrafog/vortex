package com.vortex.a3.core.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.vortex.a3.core.pairing.PairingOrchestrator
import com.vortex.a3.core.pairing.ReconnectOrchestrator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GattServer(
    private val context: Context,
    var pairingOrchestrator: PairingOrchestrator? = null,
    var reconnectOrchestrator: ReconnectOrchestrator? = null,
) {

    private var server: BluetoothGattServer? = null
    private var pairingControlChar: BluetoothGattCharacteristic? = null
    private var reconnectControlChar: BluetoothGattCharacteristic? = null
    private var audioSignalChar: BluetoothGattCharacteristic? = null

    @Volatile private var lastDecodeWarnMs: Long = 0L
    private val decodeWarnIntervalMs: Long = 1000L

    private val pairingSubscribers: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(HashSet())
    private val reconnectSubscribers: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(HashSet())
    private val audioSignalSubscribers: MutableSet<BluetoothDevice> =
        Collections.synchronizedSet(HashSet())

    private val audioSendCiphers: ConcurrentHashMap<String, CipherState> = ConcurrentHashMap()

    private val audioRecvCiphers: ConcurrentHashMap<String, CipherState> = ConcurrentHashMap()

    private val recvAeadFails: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    private val audioRecvNonce: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val deviceToPeerPub: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()

    private val prepWriteBuf: ConcurrentHashMap<String, java.io.ByteArrayOutputStream> = ConcurrentHashMap()
    private val prepWriteChar: ConcurrentHashMap<String, java.util.UUID> = ConcurrentHashMap()

    @Volatile var onAudioOpReceived: (peerStaticPub: ByteArray, jsonBytes: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onNotificationReceived: (peerStaticPub: ByteArray, jsonBytes: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onClipboardReceived: (peerStaticPub: ByteArray, jsonBytes: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onClipboardImageChunk: (peerStaticPub: ByteArray, chunk: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onClipboardTextChunk: (peerStaticPub: ByteArray, chunk: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onStateReceived: (peerStaticPub: ByteArray, jsonBytes: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onCallControlReceived: (peerStaticPub: ByteArray, jsonBytes: ByteArray) -> Unit =
        { _, _ -> }

    /** Invoked when the laptop WRITES a NOTES_SYNC chunk (`[total][idx][data]`):
     *  reassembled + LWW-merged into the local notes store. */
    @Volatile var onNotesSyncReceived: (peerStaticPub: ByteArray, chunk: ByteArray) -> Unit =
        { _, _ -> }

    @Volatile var onAudioSignalSubscribed: (device: BluetoothDevice) -> Unit = { }

    @Volatile var onPeerDisconnected: (device: BluetoothDevice) -> Unit = { }

    private val peerToDevice: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()

    private val capabilityResponse: ByteArray = ByteBuffer.allocate(3)
        .order(ByteOrder.BIG_ENDIAN)
        .put(Ble.V1_VERSION)
        .putShort(0)
        .array()

    private val cccUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun start(): Boolean {
        if (server != null) {
            Log.w(TAG, "server already running")
            return true
        }
        val bm = context.getSystemService(BluetoothManager::class.java)
        val s = try {
            bm.openGattServer(context, callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "missing BLUETOOTH_CONNECT", e)
            return false
        } ?: run {
            Log.e(TAG, "openGattServer returned null")
            return false
        }

        val service = BluetoothGattService(
            Ble.VORTEX_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Ble.CAPABILITY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        val pairingControl = BluetoothGattCharacteristic(
            Ble.PAIRING_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        pairingControl.addDescriptor(
            BluetoothGattDescriptor(
                cccUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(pairingControl)
        pairingControlChar = pairingControl

        val reconnectControl = BluetoothGattCharacteristic(
            Ble.RECONNECT_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        reconnectControl.addDescriptor(
            BluetoothGattDescriptor(
                cccUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(reconnectControl)
        reconnectControlChar = reconnectControl

        val audioSignal = BluetoothGattCharacteristic(
            Ble.AUDIO_SIGNAL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE
                or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        audioSignal.addDescriptor(
            BluetoothGattDescriptor(
                cccUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(audioSignal)
        audioSignalChar = audioSignal

        try {
            s.addService(service)
        } catch (e: SecurityException) {
            Log.e(TAG, "addService missing permission", e)
            s.close()
            return false
        }
        server = s
        Log.i(TAG, "GATT server started, service ${Ble.VORTEX_SERVICE_UUID}")
        return true
    }

    fun stop() {
        val s = server ?: return
        try {
            s.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close threw: ${e.message}")
        }
        server = null
        pairingControlChar = null
        reconnectControlChar = null
        audioSignalChar = null
        pairingSubscribers.clear()
        reconnectSubscribers.clear()
        audioSignalSubscribers.clear()
        Log.i(TAG, "GATT server stopped")
    }

    fun sendPairingControl(device: BluetoothDevice, frame: Frame) =
        notifyTo(device, frame, pairingControlChar, pairingSubscribers)

    private fun notifyReconnectTo(device: BluetoothDevice, frame: Frame) =
        notifyTo(device, frame, reconnectControlChar, reconnectSubscribers)

    private val deviceMtu = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun sendAudioSignal(device: BluetoothDevice, frame: Frame): Boolean {
        val budget = ((deviceMtu[device.address] ?: 23) - 3).coerceAtLeast(1)
        val encoded = frame.encode()
        if (encoded.size <= budget) {
            return notifyTo(device, frame, audioSignalChar, audioSignalSubscribers)
        }
        val cap = budget - FRAME_HEADER_LEN - 4
        if (cap <= 0) {
            Log.w(TAG, "sendAudioSignal: budget $budget too small to fragment; dropping")
            return false
        }
        val total = (encoded.size + cap - 1) / cap
        if (total > 0xFFFF) return false
        for (idx in 0 until total) {
            val start = idx * cap
            val end = minOf(start + cap, encoded.size)
            val payload = ByteArray(4 + (end - start))
            payload[0] = ((total ushr 8) and 0xFF).toByte()
            payload[1] = (total and 0xFF).toByte()
            payload[2] = ((idx ushr 8) and 0xFF).toByte()
            payload[3] = (idx and 0xFF).toByte()
            encoded.copyInto(payload, 4, start, end)
            if (!notifyTo(device, Frame(FrameType.FRAG, 0x00, payload),
                    audioSignalChar, audioSignalSubscribers)
            ) {
                return false
            }
            if (idx + 1 < total) {
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
        }
        Log.i(TAG, "sendAudioSignal: fragmented ${encoded.size}B frame into $total FRAGs (budget $budget)")
        return true
    }

    fun registerAudioSession(
        peerStaticPub: ByteArray,
        device: BluetoothDevice,
        sendCipher: CipherState,
        recvCipher: CipherState,
    ) {
        val peerHex = peerStaticPub.toHex()
        peerToDevice[peerHex] = device
        audioSendCiphers[device.address] = sendCipher
        audioRecvCiphers[device.address] = recvCipher
        audioRecvNonce[device.address] = 0L
        deviceToPeerPub[device.address] = peerStaticPub.copyOf()
        Log.i(TAG, "registered audio session for peer=${peerHex.take(8)}… device=${device.address}")
    }

    fun forgetAudioSession(peerStaticPub: ByteArray) {
        val peerHex = peerStaticPub.toHex()
        val device = peerToDevice.remove(peerHex) ?: return
        audioSendCiphers.remove(device.address)
        audioRecvCiphers.remove(device.address)
        audioRecvNonce.remove(device.address)
        deviceToPeerPub.remove(device.address)
        Log.i(TAG, "forgot audio session for peer=${peerHex.take(8)}…")
    }

    private fun sealAndNotify(
        peerStaticPub: ByteArray,
        frameType: Byte,
        plain: ByteArray,
        logTag: String,
        logSuccess: Boolean = false,
        verbose: Boolean = false,
    ): Boolean {
        val peerHex = peerStaticPub.toHex()
        val device = peerToDevice[peerHex] ?: run {
            if (verbose) Log.w(TAG, "$logTag: no device for peer=${peerHex.take(8)}…")
            return false
        }
        val cipher = audioSendCiphers[device.address] ?: run {
            if (verbose) Log.w(TAG, "$logTag: no cipher for device=${device.address}")
            return false
        }
        val subscribed = synchronized(audioSignalSubscribers) { device in audioSignalSubscribers }
        if (!subscribed) {
            if (verbose) Log.w(TAG, "$logTag: ${device.address} hasn't subscribed to AUDIO_SIGNAL yet")
            return false
        }
        val notifyOk = synchronized(cipher) {
            if (audioSendCiphers[device.address] !== cipher) {
                Log.w(TAG, "$logTag: send cipher superseded by a fresh IK; dropping frame")
                return false
            }
            val ct = ByteArray(plain.size + cipher.macLength)
            val n = try {
                cipher.encryptWithAd(null, plain, 0, ct, 0, plain.size)
            } catch (e: Exception) {
                Log.e(TAG, "$logTag: AEAD seal failed", e)
                return false
            }
            sendAudioSignal(device, Frame(frameType, 0x00, ct.copyOf(n)))
        }
        if (notifyOk) {
            if (logSuccess) Log.i(TAG, "$logTag: notified ${device.address}")
        } else if (verbose) {
            Log.w(TAG, "$logTag: notifyCharacteristicChanged refused for ${device.address}; caller should LAN-fallback")
        }
        return notifyOk
    }

    fun sendAudioOpEncrypted(peerStaticPub: ByteArray, audioOpJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.AUDIO_OP, audioOpJson, "sendAudioOpEncrypted", logSuccess = true, verbose = true)

    fun sendStateEncrypted(peerStaticPub: ByteArray, stateJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.STATE, stateJson, "sendStateEncrypted", logSuccess = true)

    fun sendNotificationEncrypted(peerStaticPub: ByteArray, notifJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.NOTIFICATION, notifJson, "sendNotificationEncrypted", logSuccess = true)

    /** One notes/todos sync chunk (NOTES_SYNC 0x4D): `[total][idx][data]` of the
     *  full item set. Bidirectional LWW sync; see NoteSync. */
    fun sendNotesSyncEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.NOTES_SYNC, chunkPayload, "sendNotesSync")

    fun sendClipboardEncrypted(peerStaticPub: ByteArray, clipJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CLIPBOARD, clipJson, "sendClipboardEncrypted", logSuccess = true)

    fun sendClipboardImageChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CLIPBOARD_IMAGE, chunkPayload, "sendClipboardImageChunk")

    fun sendClipboardTextChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CLIPBOARD_TEXT, chunkPayload, "sendClipboardTextChunk")

    fun sendWifiDirectOfferEncrypted(peerStaticPub: ByteArray, offerJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.WIFI_DIRECT_OFFER, offerJson, "sendWifiDirectOffer", logSuccess = true)

    fun sendClipboardImageOfferEncrypted(peerStaticPub: ByteArray, offerJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CLIPBOARD_IMAGE_OFFER, offerJson, "sendClipboardImageOffer", logSuccess = true)

    fun sendLiveActivityEncrypted(peerStaticPub: ByteArray, liveJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.LIVE_ACTIVITY, liveJson, "sendLiveActivityEncrypted", logSuccess = true)

    fun sendCallEncrypted(peerStaticPub: ByteArray, callJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CALL, callJson, "sendCallEncrypted", logSuccess = true)

    fun sendHandoffEncrypted(peerStaticPub: ByteArray, handoffJson: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.HANDOFF, handoffJson, "sendHandoffEncrypted", logSuccess = true)

    fun sendIconChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.ICON, chunkPayload, "sendIconChunkEncrypted")

    fun sendContactsChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CONTACTS, chunkPayload, "sendContactsChunkEncrypted")

    fun sendCallLogChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.CALL_LOG, chunkPayload, "sendCallLogChunkEncrypted")

    fun sendSmsChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.SMS, chunkPayload, "sendSmsChunkEncrypted")

    fun sendSmsThreadChunkEncrypted(peerStaticPub: ByteArray, chunkPayload: ByteArray): Boolean =
        sealAndNotify(peerStaticPub, FrameType.SMS_THREAD, chunkPayload, "sendSmsThreadChunkEncrypted")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun audioSignalSubscriberSnapshot(): List<BluetoothDevice> =
        synchronized(audioSignalSubscribers) { audioSignalSubscribers.toList() }

    private fun notifyTo(
        device: BluetoothDevice,
        frame: Frame,
        char: BluetoothGattCharacteristic?,
        subscribers: MutableSet<BluetoothDevice>,
    ): Boolean {
        val s = server ?: return false
        val c = char ?: return false
        val subscribed = synchronized(subscribers) { device in subscribers }
        if (!subscribed) {
            Log.w(TAG, "skipped notify to ${device.address}: not subscribed to ${c.uuid}")
            return false
        }
        c.value = frame.encode()
        return try {
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(device, c, false)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify threw for ${device.address}: ${e.message}")
            false
        }
    }

    fun isRunning(): Boolean = server != null

    private val connectedAddrs =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastDisconnectAtMs: Long = android.os.SystemClock.elapsedRealtime()
        private set

    fun hasActiveConnection(): Boolean = connectedAddrs.isNotEmpty()

    private val callback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val addr = device?.address ?: return
            deviceMtu[addr] = mtu
            Log.i(TAG, "ATT MTU for $addr → $mtu (notify budget ${mtu - 3})")
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val state = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "STATE_$newState"
            }
            Log.i(TAG, "GATT $state device=${device?.address ?: "?"} status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && device != null) {
                connectedAddrs.add(device.address)
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                connectedAddrs.remove(device.address)
                if (connectedAddrs.isEmpty()) {
                    lastDisconnectAtMs = android.os.SystemClock.elapsedRealtime()
                }
                prepWriteBuf.remove(device.address)
                prepWriteChar.remove(device.address)
                deviceMtu.remove(device.address)
                pairingOrchestrator?.forgetDeviceOnDisconnect(device)
                reconnectOrchestrator?.forgetDevice(device)
                try { onPeerDisconnected(device) } catch (e: Exception) {
                    Log.w(TAG, "onPeerDisconnected hook threw: ${e.message}")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val s = server ?: return
            val uuid = characteristic?.uuid
            val payload: ByteArray? = when (uuid) {
                Ble.CAPABILITY_UUID -> capabilityResponse
                else -> {
                    Log.w(TAG, "unexpected READ on $uuid")
                    null
                }
            }
            try {
                if (payload != null) {
                    val slice = if (offset >= payload.size) ByteArray(0)
                    else payload.copyOfRange(offset, payload.size)
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                } else {
                    s.sendResponse(
                        device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                    )
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "sendResponse threw: ${e.message}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val s = server ?: return
            val uuid = characteristic?.uuid
            val payload = value ?: ByteArray(0)

            if (preparedWrite) {
                val addr = device?.address
                if (addr != null && uuid != null) {
                    prepWriteBuf.getOrPut(addr) { java.io.ByteArrayOutputStream() }.write(payload)
                    prepWriteChar[addr] = uuid
                }
                if (responseNeeded) {
                    try {
                        s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "sendResponse(prepare) threw: ${e.message}")
                    }
                }
                return
            }

            if (responseNeeded) {
                try {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
                } catch (e: SecurityException) {
                    Log.w(TAG, "sendResponse threw: ${e.message}")
                }
            }
            dispatchWrite(device, uuid, payload)
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            val s = server ?: return
            val addr = device?.address
            val assembled = addr?.let { prepWriteBuf.remove(it) }?.toByteArray()
            val uuid = addr?.let { prepWriteChar.remove(it) }
            try {
                s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            } catch (e: SecurityException) {
                Log.w(TAG, "sendResponse(execute) threw: ${e.message}")
            }
            if (execute && assembled != null && assembled.isNotEmpty()) {
                dispatchWrite(device, uuid, assembled)
            }
        }

        private fun dispatchWrite(device: BluetoothDevice?, uuid: java.util.UUID?, payload: ByteArray) {
            val frame = Frame.decode(payload).getOrNull()
            if (frame == null) {
                val now = System.currentTimeMillis()
                if (now - lastDecodeWarnMs >= decodeWarnIntervalMs) {
                    lastDecodeWarnMs = now
                    Log.w(TAG, "frame decode failed for write of ${payload.size} bytes on $uuid")
                }
                return
            }

            when (uuid) {
                Ble.PAIRING_CONTROL_UUID -> {
                    Log.i(TAG, "PairingControl WRITE ${payload.size} bytes")
                    val orchestrator = pairingOrchestrator
                    if (orchestrator == null || device == null) {
                        Log.w(TAG, "PairingControl write rejected: no orchestrator wired")
                        return
                    }
                    val out = orchestrator.onPairingControlFrame(device, frame)
                    if (out != null) sendPairingControl(device, out)
                }

                Ble.RECONNECT_CONTROL_UUID -> {
                    Log.i(TAG, "ReconnectControl WRITE ${payload.size} bytes")
                    val orchestrator = reconnectOrchestrator
                    if (orchestrator == null || device == null) {
                        Log.w(TAG, "no reconnect orchestrator wired; dropping frame")
                        return
                    }
                    val out = orchestrator.onReconnectFrame(device, frame)
                    if (out != null) notifyReconnectTo(device, out)
                }

                Ble.AUDIO_SIGNAL_UUID -> {
                    val addr = device?.address ?: return
                    val peerPub = deviceToPeerPub[addr] ?: run {
                        Log.w(TAG, "AudioSignal WRITE: no peer-pub for $addr; drop")
                        return
                    }
                    val cipher = audioRecvCiphers[addr] ?: run {
                        Log.w(TAG, "AudioSignal WRITE: no recv cipher for $addr; drop")
                        return
                    }
                    if (frame.type != FrameType.AUDIO_OP &&
                        frame.type != FrameType.NOTIFICATION &&
                        frame.type != FrameType.STATE &&
                        frame.type != FrameType.CALL_CONTROL &&
                        frame.type != FrameType.CLIPBOARD &&
                        frame.type != FrameType.CLIPBOARD_IMAGE &&
                        frame.type != FrameType.CLIPBOARD_TEXT &&
                        frame.type != FrameType.NOTES_SYNC
                    ) {
                        Log.w(TAG, "AudioSignal WRITE: unexpected frame type ${frame.type}")
                        return
                    }
                    val plain = ByteArray(frame.payload.size)
                    val baseNonce = audioRecvNonce[addr] ?: 0L
                    var n = -1
                    var usedNonce = baseNonce
                    for (skip in 0..NONCE_RESYNC_WINDOW) {
                        cipher.setNonce(baseNonce + skip)
                        try {
                            n = cipher.decryptWithAd(null, frame.payload, 0, plain, 0, frame.payload.size)
                            usedNonce = baseNonce + skip
                            break
                        } catch (_: Exception) {
                            n = -1
                        }
                    }
                    if (n < 0) {
                        cipher.setNonce(baseNonce)
                        val fails = (recvAeadFails[addr] ?: 0) + 1
                        recvAeadFails[addr] = fails
                        Log.w(TAG, "AudioSignal WRITE: AEAD open failed (#$fails); drop")
                        if (fails >= 3) {
                            Log.w(TAG, "AudioSignal WRITE: recv cipher desynced — disconnecting to force re-handshake")
                            recvAeadFails.remove(addr)
                            audioRecvNonce.remove(addr)
                            try { device?.let { server?.cancelConnection(it) } } catch (_: Exception) {}
                        }
                        return
                    }
                    if (usedNonce > baseNonce) {
                        Log.w(TAG, "AudioSignal WRITE: resynced past ${usedNonce - baseNonce} dropped frame(s) (no re-handshake)")
                    }
                    audioRecvNonce[addr] = usedNonce + 1
                    recvAeadFails[addr] = 0
                    val jsonBytes = plain.copyOf(n)
                    when (frame.type) {
                        FrameType.AUDIO_OP -> {
                            Log.i(TAG, "AudioSignal WRITE: dispatching $n bytes from peer=${peerPub.toHex().take(8)}…")
                            try {
                                onAudioOpReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onAudioOpReceived threw: ${e.message}")
                            }
                        }
                        FrameType.NOTIFICATION -> {
                            try {
                                onNotificationReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onNotificationReceived threw: ${e.message}")
                            }
                        }
                        FrameType.CLIPBOARD -> {
                            try {
                                onClipboardReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onClipboardReceived threw: ${e.message}")
                            }
                        }
                        FrameType.CLIPBOARD_IMAGE -> {
                            try {
                                onClipboardImageChunk(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onClipboardImageChunk threw: ${e.message}")
                            }
                        }
                        FrameType.CLIPBOARD_TEXT -> {
                            try {
                                onClipboardTextChunk(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onClipboardTextChunk threw: ${e.message}")
                            }
                        }
                        FrameType.STATE -> {
                            try {
                                onStateReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onStateReceived threw: ${e.message}")
                            }
                        }
                        FrameType.CALL_CONTROL -> {
                            try {
                                onCallControlReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onCallControlReceived threw: ${e.message}")
                            }
                        }
                        FrameType.NOTES_SYNC -> {
                            try {
                                onNotesSyncReceived(peerPub, jsonBytes)
                            } catch (e: Exception) {
                                Log.w(TAG, "onNotesSyncReceived threw: ${e.message}")
                            }
                        }
                    }
                }

                else -> {
                    Log.w(TAG, "WRITE on unexpected $uuid — ignored")
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val s = server ?: return
            if (descriptor?.uuid == cccUuid && device != null) {
                val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                val charUuid = descriptor.characteristic.uuid
                val targetSet = when (charUuid) {
                    Ble.PAIRING_CONTROL_UUID -> pairingSubscribers
                    Ble.RECONNECT_CONTROL_UUID -> reconnectSubscribers
                    Ble.AUDIO_SIGNAL_UUID -> audioSignalSubscribers
                    else -> null
                }
                if (targetSet != null) {
                    if (enabled) targetSet.add(device) else targetSet.remove(device)
                    Log.i(TAG, "${device.address} ${if (enabled) "subscribed to" else "unsubscribed from"} $charUuid")
                    if (enabled && charUuid == Ble.AUDIO_SIGNAL_UUID) {
                        try { onAudioSignalSubscribed(device) } catch (e: Exception) {
                            Log.w(TAG, "onAudioSignalSubscribed hook threw: ${e.message}")
                        }
                    }
                }
            }
            if (responseNeeded) {
                try {
                    s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (e: SecurityException) {
                    Log.w(TAG, "sendResponse threw: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "VortexGattSrv"
        private const val NONCE_RESYNC_WINDOW = 128
    }
}
