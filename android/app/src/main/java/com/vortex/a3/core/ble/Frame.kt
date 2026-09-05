package com.vortex.a3.core.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FrameType {
    const val PAIRING_HANDSHAKE: Byte = 0x10
    const val PAIRING_APPROVAL: Byte = 0x11
    const val PAIRING_TRUSTED_INFO: Byte = 0x12
    const val RECONNECT_HANDSHAKE: Byte = 0x20
    const val LAN_JOIN_PROOF_RESERVED_V2: Byte = 0x21
    const val TRANSPORT_KEEPALIVE: Byte = 0x30
    const val TRANSPORT_APP_DATA: Byte = 0x31
    const val AUDIO_OP: Byte = 0x32
    const val STATE: Byte = 0x33
    const val NOTIFICATION: Byte = 0x34
    const val LIVE_ACTIVITY: Byte = 0x35
    const val ICON: Byte = 0x36
    const val CALL: Byte = 0x37
    const val CALL_CONTROL: Byte = 0x38
    const val CONTACTS: Byte = 0x39
    const val CALL_LOG: Byte = 0x3A
    const val SMS: Byte = 0x3B
    const val SMS_THREAD: Byte = 0x3C
    const val BULK_SYNC: Byte = 0x3D
    const val CALL_LOG_HISTORY: Byte = 0x3E
    const val SMS_IDS: Byte = 0x3F
    const val CLIPBOARD: Byte = 0x40
    const val CLIPBOARD_IMAGE: Byte = 0x41
    const val CLIPBOARD_IMAGE_OFFER: Byte = 0x42
    const val CLIPBOARD_TEXT: Byte = 0x43
    const val CLIPBOARD_FILE: Byte = 0x45
    const val WIFI_DIRECT_OFFER: Byte = 0x46
    const val FILE_PUSH_OFFER: Byte = 0x49
    const val FILE_PUSH: Byte = 0x4A
    const val FILE_PUSH_DECISION: Byte = 0x4B
    const val HANDOFF: Byte = 0x4C
    const val NOTES_SYNC: Byte = 0x4D
    const val FRAG: Byte = 0x4E
    const val ERROR: Byte = 0x7F
}

object FrameSub {
    const val PING: Byte = 0x01
    const val PONG: Byte = 0x02
    const val ECHO_REQUEST: Byte = 0x01
    const val ECHO_RESPONSE: Byte = 0x02
}

const val FRAME_HEADER_LEN: Int = 4

const val MAX_FRAME_PAYLOAD: Int = 63 * 1024

data class Frame(
    val type: Byte,
    val sub: Byte,
    val payload: ByteArray,
) {
    init {
        require(payload.size <= MAX_FRAME_PAYLOAD) { "payload too large" }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(FRAME_HEADER_LEN + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(type)
        buf.put(sub)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type && sub == other.sub && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = type.toInt()
        r = 31 * r + sub.toInt()
        r = 31 * r + payload.contentHashCode()
        return r
    }

    companion object {
        fun echoResponse(payload: ByteArray): Frame =
            Frame(FrameType.TRANSPORT_KEEPALIVE, FrameSub.ECHO_RESPONSE, payload.copyOf())

        fun decode(bytes: ByteArray): Result<Frame> {
            if (bytes.size < FRAME_HEADER_LEN) {
                return Result.failure(IllegalArgumentException("frame too short: ${bytes.size}"))
            }
            val length = (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
            if (length > MAX_FRAME_PAYLOAD) {
                return Result.failure(IllegalArgumentException("declared length $length exceeds max"))
            }
            val total = FRAME_HEADER_LEN + length
            if (bytes.size != total) {
                return Result.failure(
                    IllegalArgumentException(
                        "length mismatch: declared $total, actual ${bytes.size}",
                    )
                )
            }
            return Result.success(
                Frame(
                    type = bytes[0],
                    sub = bytes[1],
                    payload = bytes.copyOfRange(FRAME_HEADER_LEN, bytes.size),
                )
            )
        }
    }
}
