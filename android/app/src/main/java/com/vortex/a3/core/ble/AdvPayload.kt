package com.vortex.a3.core.ble


@JvmInline
value class AdvFlags(val raw: Byte) {
    companion object {
        const val PAIRABLE: Byte = 0b00000001
        const val TRUSTED_PRESENCE: Byte = 0b00000010
        const val RESERVED_MASK: Byte = 0b11111100.toByte()

        fun pairable(): AdvFlags = AdvFlags(PAIRABLE)
        fun trustedPresence(): AdvFlags = AdvFlags(TRUSTED_PRESENCE)
    }

    val isPairable: Boolean get() = (raw.toInt() and PAIRABLE.toInt()) != 0
    val isTrustedPresence: Boolean get() = (raw.toInt() and TRUSTED_PRESENCE.toInt()) != 0

    val isWellFormed: Boolean
        get() {
            if ((raw.toInt() and RESERVED_MASK.toInt()) != 0) return false
            val lo = (raw.toInt() and 0b11)
            return lo == PAIRABLE.toInt() || lo == TRUSTED_PRESENCE.toInt()
        }
}

data class AdvPayload(
    val version: Byte,
    val flags: AdvFlags,
    val payload8: ByteArray,
) {
    init {
        require(payload8.size == 8) { "payload_8 must be 8 bytes" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(Ble.ADV_PAYLOAD_LEN)
        out[0] = version
        out[1] = flags.raw
        System.arraycopy(payload8, 0, out, 2, 8)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvPayload) return false
        return version == other.version &&
            flags.raw == other.flags.raw &&
            payload8.contentEquals(other.payload8)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + flags.raw.toInt()
        result = 31 * result + payload8.contentHashCode()
        return result
    }

    companion object {
        fun pairable(instanceId: ByteArray): AdvPayload {
            require(instanceId.size == 8) { "instance ID must be 8 bytes" }
            return AdvPayload(Ble.V1_VERSION, AdvFlags.pairable(), instanceId.copyOf())
        }

        fun trustedPresence(token: ByteArray): AdvPayload {
            require(token.size == 8) { "token must be 8 bytes" }
            return AdvPayload(Ble.V1_VERSION, AdvFlags.trustedPresence(), token.copyOf())
        }

        fun decode(bytes: ByteArray): Result<AdvPayload> {
            if (bytes.size != Ble.ADV_PAYLOAD_LEN) {
                return Result.failure(AdvDecodeException("wrong length: ${bytes.size}"))
            }
            if (bytes[0] != Ble.V1_VERSION) {
                return Result.failure(AdvDecodeException("wrong version: ${bytes[0]}"))
            }
            val flags = AdvFlags(bytes[1])
            if (!flags.isWellFormed) {
                return Result.failure(AdvDecodeException("bad flags: 0x%02x".format(bytes[1])))
            }
            return Result.success(
                AdvPayload(
                    version = bytes[0],
                    flags = flags,
                    payload8 = bytes.copyOfRange(2, 10),
                )
            )
        }
    }
}

class AdvDecodeException(message: String) : Exception(message)
