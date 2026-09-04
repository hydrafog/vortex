package com.vortex.a3.core.identity

import com.vortex.a3.core.crypto.X25519
import java.nio.ByteBuffer
import java.security.SecureRandom

const val IDENTITY_VERSION: Byte = 0x01

enum class Platform(val byte: Byte) {
    Android(0x01.toByte()),
    Linux(0x02.toByte());

    companion object {
        fun fromByte(b: Byte): Platform? = values().firstOrNull { it.byte == b }
    }
}

data class IdentityRecord(
    val version: Byte,
    val deviceId: ByteArray,
    val staticPriv: ByteArray,
    val staticPub: ByteArray,
    val createdAt: Long,
    val platform: Platform,
) {
    init {
        require(deviceId.size == 16) { "device_id must be 16 bytes" }
        require(staticPriv.size == X25519.PRIV_LEN) { "static_priv must be ${X25519.PRIV_LEN} bytes" }
        require(staticPub.size == X25519.PUB_LEN) { "static_pub must be ${X25519.PUB_LEN} bytes" }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(90)
        buf.put(version)
        buf.put(deviceId)
        buf.put(staticPriv)
        buf.put(staticPub)
        buf.putLong(createdAt)
        buf.put(platform.byte)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityRecord) return false
        return version == other.version &&
            deviceId.contentEquals(other.deviceId) &&
            staticPriv.contentEquals(other.staticPriv) &&
            staticPub.contentEquals(other.staticPub) &&
            createdAt == other.createdAt &&
            platform == other.platform
    }

    override fun hashCode(): Int {
        var r = version.toInt()
        r = 31 * r + deviceId.contentHashCode()
        r = 31 * r + staticPriv.contentHashCode()
        r = 31 * r + staticPub.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + platform.hashCode()
        return r
    }

    companion object {
        fun fromPrivate(
            platform: Platform,
            deviceId: ByteArray,
            staticPriv: ByteArray,
            createdAt: Long,
        ): IdentityRecord = IdentityRecord(
            version = IDENTITY_VERSION,
            deviceId = deviceId.copyOf(),
            staticPriv = staticPriv.copyOf(),
            staticPub = X25519.publicFromPrivate(staticPriv),
            createdAt = createdAt,
            platform = platform,
        )

        fun generate(platform: Platform): IdentityRecord {
            val rng = SecureRandom()
            val deviceId = ByteArray(16).also { rng.nextBytes(it) }
            val priv = ByteArray(X25519.PRIV_LEN).also { rng.nextBytes(it) }
            return fromPrivate(platform, deviceId, priv, System.currentTimeMillis() / 1000)
        }
    }
}

data class IdentityPublicView(
    val version: Byte,
    val deviceIdHex: String,
    val staticPubHex: String,
    val createdAt: Long,
    val platform: Platform,
) {
    companion object {
        fun from(record: IdentityRecord): IdentityPublicView = IdentityPublicView(
            version = record.version,
            deviceIdHex = record.deviceId.joinToString("") { "%02x".format(it) },
            staticPubHex = record.staticPub.joinToString("") { "%02x".format(it) },
            createdAt = record.createdAt,
            platform = record.platform,
        )
    }
}
