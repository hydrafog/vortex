package com.vortex.a3.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenMirrorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWakeLock: android.os.PowerManager.WakeLock? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var videoServer: java.net.ServerSocket? = null
    private var videoClient: java.net.Socket? = null
    private var videoOut: java.io.OutputStream? = null
    private var tcpSealer: com.vortex.a3.core.mirror.MirrorTcpSealer? = null
    @Volatile private var clientConnected = false
    private val writeLock = Any()

    @Volatile private var writeStartedNs = 0L

    private var encoderJob: Job? = null

    @Volatile private var running = false
    @Volatile private var bitrate: Int = 6_000_000
    @Volatile private var currentBitrate: Int = 6_000_000
    private var adaptWindowStartNs: Long = 0
    private var adaptBlockedNs: Long = 0
    @Volatile private var framesSent: Long = 0
    @Volatile private var codecConfigAnnexB: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { startMirroring(intent) }
            ACTION_REQUEST_KEYFRAME -> requestSyncFrame()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        encoderJob?.cancel()
        scope.cancel()
        releaseMirrorPipeline()
        super.onDestroy()
    }

    private fun startMirroring(intent: Intent) {
        try {
            if (running) {
                Log.i(TAG, "mirror: restart — tearing down previous session")
                running = false
                encoderJob?.cancel()
                releaseMirrorPipeline()
                clientConnected = false
                codecConfigAnnexB = null
                adaptWindowStartNs = 0
                adaptBlockedNs = 0
            }
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val resultData =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
            val width = (intent.getIntExtra(EXTRA_WIDTH, 0) / 2) * 2
            val height = (intent.getIntExtra(EXTRA_HEIGHT, 0) / 2) * 2
            val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(15, 60)
            bitrate = intent.getIntExtra(EXTRA_BITRATE, 6_000_000)
            currentBitrate = (bitrate * ADAPT_START_FRAC).toInt().coerceAtLeast(ADAPT_MIN_BITRATE)
            val key = intent.getByteArrayExtra(EXTRA_KEY)
            if (resultCode == Int.MIN_VALUE || resultData == null ||
                width == 0 || height == 0 || key == null || key.size != 32
            ) {
                Log.w(TAG, "mirror config invalid; stopping")
                stopSelf()
                return
            }

            createNotificationChannel()
            val notif = buildNotification("Mirroring to laptop")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }

            @Suppress("DEPRECATION")
            screenWakeLock = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(
                    android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "VortexMirror:screen",
                ).also { it.acquire(60 * 60 * 1000L) }

            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = pm.getMediaProjection(resultCode, resultData)
            if (proj == null) {
                Log.w(TAG, "projection denied")
                stopSelf()
                return
            }
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    stopSelf()
                }
            }, mainHandler)
            projection = proj

            tcpSealer = com.vortex.a3.core.mirror.MirrorTcpSealer(key)
            videoServer = java.net.ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(MIRROR_VIDEO_PORT))
                soTimeout = VIDEO_ACCEPT_TIMEOUT_MS
            }

            configureEncoder(width, height, fps)

            val vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = proj.createVirtualDisplay(
                "VortexMirror", width, height, resources.displayMetrics.densityDpi,
                vdFlags, inputSurface, null, mainHandler,
            )
            if (virtualDisplay == null) {
                Log.w(TAG, "virtual display failed")
                stopSelf()
                return
            }

            running = true
            framesSent = 0
            updateStatus("Streaming ${width}x${height}@${fps} (TCP :$MIRROR_VIDEO_PORT)")
            scope.launch { acceptVideoClient() }
            encoderJob = scope.launch { encoderLoop() }
        } catch (t: Throwable) {
            Log.e(TAG, "startMirroring failed", t)
            stopSelf()
        }
    }

    private fun configureEncoder(width: Int, height: Int, fps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 60)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // NOTE: KEY_MAX_FPS_TO_ENCODER (encoder-input cap) was tried to
            }
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    private fun encoderLoop() {
        val localCodec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (scope.isActive && running) {
            val index = try {
                localCodec.dequeueOutputBuffer(info, 10_000)
            } catch (t: Throwable) {
                Log.e(TAG, "encoder dequeue failed", t)
                break
            }
            when {
                index >= 0 -> {
                    val buf = localCodec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val au = ByteArray(info.size)
                        buf.get(au)
                        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val normalized = normalizeAccessUnit(au, isKeyframe)
                        if (normalized.isNotEmpty()) sendAccessUnit(normalized)
                        framesSent++
                        if (framesSent % 300L == 0L) updateStatus("frames=$framesSent")
                    }
                    localCodec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = localCodec.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")?.toByteArray()
                    val csd1 = fmt.getByteBuffer("csd-1")?.toByteArray()
                    val config = buildCodecConfigAnnexB(csd0, csd1)
                    codecConfigAnnexB = config.takeIf { it.isNotEmpty() }
                    if (config.isNotEmpty()) sendAccessUnit(config)
                }
            }
        }
        running = false
        stopSelf()
    }

    private fun acceptVideoClient() {
        val server = videoServer ?: return
        while (scope.isActive && running && !clientConnected) {
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                client.sendBufferSize = VIDEO_SEND_BUFFER
                videoClient = client
                videoOut = java.io.BufferedOutputStream(client.getOutputStream(), VIDEO_SEND_BUFFER)
                clientConnected = true
                startWriteWatchdog()
                Log.i(TAG, "mirror: laptop video client connected ${client.inetAddress?.hostAddress}")
                codecConfigAnnexB?.let { runCatching { writeFrame(it) } }
                requestSyncFrame()
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                Log.w(TAG, "mirror: accept failed: ${e.message}")
                break
            }
        }
    }

    private fun sendAccessUnit(data: ByteArray) {
        if (!clientConnected) return
        writeFrame(data)
    }

    private fun writeFrame(data: ByteArray) = synchronized(writeLock) {
        val s = tcpSealer ?: return
        val out = videoOut ?: return
        try {
            val sealed = s.sealAccessUnit(data)
            val t0 = System.nanoTime()
            writeStartedNs = t0
            out.write(sealed)
            out.flush()
            writeStartedNs = 0L
            val now = System.nanoTime()
            adaptBlockedNs += now - t0
            maybeAdaptBitrate(now)
        } catch (e: Exception) {
            writeStartedNs = 0L
            Log.w(TAG, "mirror: video write failed: ${e.message}")
            running = false
        }
    }

    private fun startWriteWatchdog() = scope.launch(Dispatchers.IO) {
        while (isActive && running) {
            delay(1_000)
            val started = writeStartedNs
            if (started != 0L && System.nanoTime() - started > WRITE_STALL_LIMIT_NS) {
                Log.w(TAG, "mirror: laptop stopped draining video — dropping the session")
                running = false
                try { videoClient?.close() } catch (_: Throwable) {}
                return@launch
            }
        }
    }

    private fun maybeAdaptBitrate(now: Long) {
        if (adaptWindowStartNs == 0L) { adaptWindowStartNs = now; return }
        val elapsed = now - adaptWindowStartNs
        if (elapsed < ADAPT_WINDOW_NS) return
        val blockedFrac = adaptBlockedNs.toDouble() / elapsed
        adaptWindowStartNs = now
        adaptBlockedNs = 0
        val next = when {
            blockedFrac > ADAPT_BLOCKED_HI -> (currentBitrate * ADAPT_DOWN).toInt()
            blockedFrac < ADAPT_BLOCKED_LO -> (currentBitrate * ADAPT_UP).toInt()
            else -> return
        }.coerceIn(ADAPT_MIN_BITRATE, bitrate)
        if (next != currentBitrate) {
            currentBitrate = next
            try {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next)
                })
            } catch (_: Throwable) {}
        }
    }

    private fun requestSyncFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Throwable) {}
    }

    private fun releaseMirrorPipeline() {
        try { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() } catch (_: Throwable) {}
        screenWakeLock = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        codecConfigAnnexB = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        clientConnected = false
        videoOut = null
        try { videoClient?.close() } catch (_: Throwable) {}
        videoClient = null
        try { videoServer?.close() } catch (_: Throwable) {}
        videoServer = null
        tcpSealer = null
    }


    private fun normalizeAccessUnit(accessUnit: ByteArray, isKeyframe: Boolean): ByteArray {
        val annexB = avccToAnnexB(accessUnit)
        val config = codecConfigAnnexB
        return if (isKeyframe && config != null && config.isNotEmpty()) config + annexB else annexB
    }

    private fun buildCodecConfigAnnexB(csd0: ByteArray?, csd1: ByteArray?): ByteArray {
        val out = ArrayList<Byte>()
        appendAnnexBUnit(out, csd0)
        appendAnnexBUnit(out, csd1)
        return out.toByteArray()
    }

    private fun appendAnnexBUnit(out: MutableList<Byte>, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        if (looksLikeAnnexB(bytes)) { out.addAll(bytes.asList()); return }
        out.addAll(byteArrayOf(0, 0, 0, 1).asList())
        out.addAll(bytes.asList())
    }

    private fun avccToAnnexB(input: ByteArray): ByteArray {
        if (input.isEmpty() || looksLikeAnnexB(input)) return input
        val out = ArrayList<Byte>(input.size + 32)
        var offset = 0
        while (offset + 4 <= input.size) {
            val nalSize =
                ((input[offset].toInt() and 0xFF) shl 24) or
                    ((input[offset + 1].toInt() and 0xFF) shl 16) or
                    ((input[offset + 2].toInt() and 0xFF) shl 8) or
                    (input[offset + 3].toInt() and 0xFF)
            offset += 4
            if (nalSize <= 0 || offset + nalSize > input.size) return input
            out.addAll(byteArrayOf(0, 0, 0, 1).asList())
            for (i in offset until offset + nalSize) out.add(input[i])
            offset += nalSize
        }
        return if (out.isEmpty()) input else out.toByteArray()
    }

    private fun looksLikeAnnexB(b: ByteArray): Boolean {
        if (b.size < 4) return false
        return (b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 1) ||
            (b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1)
    }


    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenMirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vortex screen mirroring")
            .setContentText(text)
            .setSmallIcon(com.vortex.a3.R.drawable.ic_notification_mirror)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(com.vortex.a3.R.drawable.ic_vortex_media_pause, "Stop", stop)
            .build()
    }

    private fun updateStatus(text: String) {
        Log.d(TAG, text)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Vortex Mirror", NotificationManager.IMPORTANCE_MIN),
        )
    }

    companion object {
        private const val TAG = "VortexMirror"
        private const val CHANNEL_ID = "vortex_mirror"
        private const val NOTIFICATION_ID = 7301

        private const val MIRROR_VIDEO_PORT = 51822

        private const val VIDEO_ACCEPT_TIMEOUT_MS = 1_000

        private const val VIDEO_SEND_BUFFER = 64 * 1024

        private const val ADAPT_MIN_BITRATE = 800_000
        private const val ADAPT_START_FRAC = 0.6
        private const val ADAPT_WINDOW_NS = 700_000_000L
        private const val ADAPT_BLOCKED_HI = 0.20
        private const val ADAPT_BLOCKED_LO = 0.05
        private const val ADAPT_DOWN = 0.75
        private const val ADAPT_UP = 1.12

        private const val WRITE_STALL_LIMIT_NS = 6_000_000_000L

        const val ACTION_START = "com.vortex.a3.action.MIRROR_START"
        const val ACTION_STOP = "com.vortex.a3.action.MIRROR_STOP"
        const val ACTION_REQUEST_KEYFRAME = "com.vortex.a3.action.MIRROR_KEYFRAME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IP = "ip"
        const val EXTRA_UDP_PORT = "udp_port"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_KEY = "media_key"

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            ip: String,
            udpPort: Int,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            mediaKey: ByteArray,
        ) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_IP, ip)
                putExtra(EXTRA_UDP_PORT, udpPort)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrate)
                putExtra(EXTRA_KEY, mediaKey)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestKeyframe(context: Context) {
            context.startService(
                Intent(context, ScreenMirrorService::class.java)
                    .setAction(ACTION_REQUEST_KEYFRAME),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenMirrorService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val dup = duplicate()
    val bytes = ByteArray(dup.remaining())
    dup.get(bytes)
    return bytes
}
