package com.vortex.a3.core.lan

import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.vortex.a3.core.ble.FRAME_HEADER_LEN
import com.vortex.a3.core.ble.Frame
import com.vortex.a3.core.ble.MAX_FRAME_PAYLOAD
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class MirrorSession(
    private val sock: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val pair: CipherStatePair,
    val handshakeHash: ByteArray,
    private val onStart: (MirrorStart) -> Unit,
    private val onInput: (ByteArray) -> Unit,
    private val onRequestKeyframe: () -> Unit,
    private val onStop: () -> Unit,
) {
    val peerAddress: java.net.InetAddress = sock.inetAddress

    fun run(firstFrame: Frame) {
        try {
            if (firstFrame.type != SCREEN_MIRROR_CONTROL || firstFrame.sub != SUB_START) {
                Log.w(TAG, "mirror: first frame not START; closing")
                return
            }
            val startPlain = try {
                aeadOpen(pair.receiver, firstFrame.payload)
            } catch (e: Exception) {
                Log.w(TAG, "mirror: START AEAD open failed: ${e.message}")
                return
            }
            val start = MirrorStart.fromJson(startPlain) ?: run {
                Log.w(TAG, "mirror: bad START payload")
                return
            }
            Log.i(TAG, "mirror: START ${start.w}x${start.h}@${start.fps} ${start.bitrate}bps udp=${start.udpPort}")
            onStart(start)

            while (true) {
                val frame = readFrame(input) ?: break
                val plain = try {
                    aeadOpen(pair.receiver, frame.payload)
                } catch (e: Exception) {
                    Log.w(TAG, "mirror: AEAD open failed (type=0x${"%02x".format(frame.type)}): ${e.message}")
                    continue
                }
                when (frame.type) {
                    SCREEN_MIRROR_INPUT -> if (plain.size == 5) onInput(plain)
                    SCREEN_MIRROR_CONTROL -> when (frame.sub) {
                        SUB_PING -> sealAndWrite(SCREEN_MIRROR_CONTROL, SUB_PONG, ByteArray(0))
                        SUB_REQUEST_KEYFRAME -> onRequestKeyframe()
                        SUB_STOP -> {
                            Log.i(TAG, "mirror: STOP received")
                            return
                        }
                        else -> Log.d(TAG, "mirror: control sub=0x${"%02x".format(frame.sub)}")
                    }
                    else -> Log.d(TAG, "mirror: unexpected frame type=0x${"%02x".format(frame.type)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mirror: session error: ${e.message}")
        } finally {
            onStop()
            try { sock.close() } catch (_: Exception) {}
            Log.i(TAG, "mirror: session closed")
        }
    }


    private fun sealAndWrite(type: Byte, sub: Byte, plaintext: ByteArray) {
        val ct = aeadSeal(pair.sender, plaintext)
        output.write(Frame(type, sub, ct).encode())
        output.flush()
    }

    private fun readFrame(input: DataInputStream): Frame? {
        return try {
            val header = ByteArray(FRAME_HEADER_LEN)
            input.readFully(header)
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length > MAX_FRAME_PAYLOAD) return null
            val payload = ByteArray(length)
            if (length > 0) input.readFully(payload)
            Frame.decode(header + payload).getOrNull()
        } catch (e: Exception) {
            null
        }
    }

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

    companion object {
        private const val TAG = "VortexMirror"

        const val SCREEN_MIRROR_INPUT: Byte = 0x46
        const val SCREEN_MIRROR_CONTROL: Byte = 0x47

        const val SUB_START: Byte = 0x01
        const val SUB_STOP: Byte = 0x02
        const val SUB_REQUEST_KEYFRAME: Byte = 0x03
        const val SUB_PING: Byte = 0x04
        const val SUB_PONG: Byte = 0x05

        fun isMirrorStart(frame: Frame): Boolean =
            frame.type == SCREEN_MIRROR_CONTROL && frame.sub == SUB_START
    }
}

data class MirrorStart(
    val w: Int,
    val h: Int,
    val fps: Int,
    val bitrate: Int,
    val udpPort: Int,
) {
    companion object {
        fun fromJson(bytes: ByteArray): MirrorStart? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            MirrorStart(
                w = o.getInt("w"),
                h = o.getInt("h"),
                fps = o.getInt("fps"),
                bitrate = o.getInt("bitrate"),
                udpPort = o.getInt("udp_port"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
