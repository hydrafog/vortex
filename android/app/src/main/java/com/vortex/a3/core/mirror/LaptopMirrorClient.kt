package com.vortex.a3.core.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LaptopMirrorClient(
    private val port: Int,
    key: ByteArray,
    private val surface: Surface,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val onVideoSize: ((Int, Int) -> Unit)? = null,
) {
    private val keySpec = SecretKeySpec(key, "ChaCha20")
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var codec: MediaCodec? = null

    fun start() {
        running = true
        try {
            acceptAndDecode()
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "laptop-mirror: stream ended: ${t.message}")
        } finally {
            cleanup()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Throwable) {  }
        try { server?.close() } catch (_: Throwable) {  }
    }

    private fun acceptAndDecode() {
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(port))
        srv.soTimeout = 60_000
        server = srv
        Log.i(TAG, "laptop-mirror: viewer server up on :$port — waiting for laptop")

        while (running) {
            val s = try {
                srv.accept()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "laptop-mirror: no laptop connection: ${t.message}")
                return
            }
            s.tcpNoDelay = true
            socket = s
            Log.i(TAG, "laptop-mirror: laptop connected from ${s.inetAddress?.hostAddress}")
            try {
                decodeConnection(s)
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "laptop-mirror: connection ended (${t.message}) — re-accepting")
            } finally {
                try { s.close() } catch (_: Throwable) {}
                socket = null
            }
        }
    }

    private fun decodeConnection(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered())
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(format, surface, null, 0)
        dec.start()
        codec = dec
        val info = MediaCodec.BufferInfo()
        val lenBuf = ByteArray(4)
        var frames = 0L
        var sawKeyframe = false
        try {
            while (running) {
                input.readFully(lenBuf)
                val msgLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
                if (msgLen < 8 + 16 || msgLen > MAX_AU) {
                    Log.w(TAG, "laptop-mirror: bad frame length $msgLen — closing")
                    break
                }
                val msg = ByteArray(msgLen)
                input.readFully(msg)
                val au = open(msg.copyOfRange(0, 8), msg)
                if (au == null) {
                    Log.w(TAG, "laptop-mirror: AEAD open failed — closing")
                    break
                }
                if (!sawKeyframe) {
                    if (!isKeyframe(au)) continue
                    sawKeyframe = true
                    Log.i(TAG, "laptop-mirror: first keyframe — decoding")
                }
                try {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = dec.getInputBuffer(inIdx)
                        if (ib != null) {
                            ib.clear()
                            ib.put(au)
                            dec.queueInputBuffer(inIdx, 0, au.size, frames * 1_000, 0)
                        } else {
                            dec.queueInputBuffer(inIdx, 0, 0, frames * 1_000, 0)
                        }
                    }
                    var outIdx = dec.dequeueOutputBuffer(info, 0)
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            reportSize(dec.outputFormat)
                        } else {
                            dec.releaseOutputBuffer(outIdx, true)
                        }
                        outIdx = dec.dequeueOutputBuffer(info, 0)
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "laptop-mirror: decoder hiccup: ${e.message}")
                    break
                }
                if (frames == 0L) Log.i(TAG, "laptop-mirror: first AU decoded")
                frames++
            }
        } finally {
            try { dec.stop() } catch (_: Throwable) {}
            try { dec.release() } catch (_: Throwable) {}
            if (codec === dec) codec = null
        }
    }

    private var reportedSize = false

    private fun reportSize(format: MediaFormat) {
        if (reportedSize) return
        val w: Int
        val h: Int
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            w = format.getInteger(MediaFormat.KEY_WIDTH)
            h = format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        if (w <= 0 || h <= 0) return
        reportedSize = true
        Log.i(TAG, "laptop-mirror: stream is ${w}x$h")
        onVideoSize?.invoke(w, h)
    }

    private fun isKeyframe(au: ByteArray): Boolean {
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0) {
                val nalIdx = when {
                    au[i + 2].toInt() == 1 -> i + 3
                    au[i + 2].toInt() == 0 && i + 3 < au.size && au[i + 3].toInt() == 1 -> i + 4
                    else -> { i++; continue }
                }
                if (nalIdx < au.size) {
                    val type = au[nalIdx].toInt() and 0x1F
                    if (type == 7 || type == 5) return true
                }
                i = nalIdx
            } else {
                i++
            }
        }
        return false
    }

    private fun open(counterBytes: ByteArray, msg: ByteArray): ByteArray? = try {
        val nonce = ByteArray(12)
        System.arraycopy(counterBytes, 0, nonce, 4, 8)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonce))
        cipher.updateAAD(counterBytes)
        cipher.doFinal(msg, 8, msg.size - 8)
    } catch (_: Throwable) {
        null
    }

    private fun cleanup() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        try { server?.close() } catch (_: Throwable) {}
        codec = null
        socket = null
        server = null
    }

    companion object {
        private const val TAG = "LaptopMirror"
        private const val MAX_AU = 8 * 1024 * 1024
    }
}
