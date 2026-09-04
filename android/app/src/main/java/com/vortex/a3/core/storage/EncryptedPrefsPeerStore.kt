package com.vortex.a3.core.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vortex.a3.core.pairing.PairingOrchestrator
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TrustedPeer(
    val peerStaticPub: ByteArray,
    val prs: ByteArray,
    val pairedAt: Long,
    val peerName: String? = null,
) {
    init {
        require(peerStaticPub.size == 32) { "peer_static_pub must be 32 bytes" }
        require(prs.size == 32) { "prs must be 32 bytes" }
    }

    fun encode(): ByteArray {
        val cleanName = peerName?.let { PairingOrchestrator.sanitizePeerName(it) }
        val nameBytes = if (cleanName.isNullOrEmpty()) {
            ByteArray(0)
        } else {
            cleanName.toByteArray(Charsets.UTF_8)
        }
        val buf = ByteBuffer.allocate(72 + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(peerStaticPub)
        buf.put(prs)
        buf.putLong(pairedAt)
        if (nameBytes.isNotEmpty()) buf.put(nameBytes)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPeer) return false
        return peerStaticPub.contentEquals(other.peerStaticPub) &&
            prs.contentEquals(other.prs) &&
            pairedAt == other.pairedAt &&
            peerName == other.peerName
    }

    override fun hashCode(): Int {
        var r = peerStaticPub.contentHashCode()
        r = 31 * r + prs.contentHashCode()
        r = 31 * r + pairedAt.hashCode()
        r = 31 * r + (peerName?.hashCode() ?: 0)
        return r
    }

    companion object {
        const val PEER_NAME_MAX_BYTES: Int = 384

        fun decode(bytes: ByteArray): TrustedPeer? {
            if (bytes.size < 72) return null
            val peerStaticPub = bytes.copyOfRange(0, 32)
            val prs = bytes.copyOfRange(32, 64)
            val pairedAt = ByteBuffer.wrap(bytes, 64, 8).order(ByteOrder.BIG_ENDIAN).long
            val tailLen = bytes.size - 72
            val peerName = if (tailLen in 1..PEER_NAME_MAX_BYTES) {
                runCatching {
                    val raw = String(bytes, 72, tailLen, Charsets.UTF_8)
                    PairingOrchestrator.sanitizePeerName(raw)
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            } else null
            return TrustedPeer(peerStaticPub, prs, pairedAt, peerName)
        }
    }
}

interface PeerStore {
    fun save(peer: TrustedPeer)
    fun load(peerStaticPub: ByteArray): TrustedPeer?
    fun list(): List<TrustedPeer>
    fun forget(peerStaticPub: ByteArray)

    fun loadCounter(peerStaticPub: ByteArray): Long = 0L
    fun bumpCounter(peerStaticPub: ByteArray, peerSeen: Long): Long = 0L

    fun nextAudioOutNonce(peerStaticPub: ByteArray): Long = 0L

    fun loadAudioInNonce(peerStaticPub: ByteArray): Long = 0L

    fun commitAudioInNonce(peerStaticPub: ByteArray, nonce: Long) {}

    fun tryAcceptAudioInNonce(peerStaticPub: ByteArray, nonce: Long): Boolean {
        val seen = loadAudioInNonce(peerStaticPub)
        if (nonce <= seen) return false
        commitAudioInNonce(peerStaticPub, nonce)
        return true
    }
}

class EncryptedPrefsPeerStore(context: Context) : PeerStore {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(false)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(peer: TrustedPeer) {
        val key = "peer-${peer.peerStaticPub.toHex()}"
        val b64 = Base64.encodeToString(peer.encode(), Base64.NO_WRAP)
        prefs.edit().putString(key, b64).apply()
    }

    override fun load(peerStaticPub: ByteArray): TrustedPeer? {
        val key = "peer-${peerStaticPub.toHex()}"
        val b64 = prefs.getString(key, null) ?: return null
        return TrustedPeer.decode(Base64.decode(b64, Base64.NO_WRAP))
    }

    override fun list(): List<TrustedPeer> {
        val all = prefs.all
        return all.entries
            .asSequence()
            .filter { it.key.startsWith("peer-") }
            .mapNotNull { entry ->
                val s = entry.value as? String ?: return@mapNotNull null
                runCatching { TrustedPeer.decode(Base64.decode(s, Base64.NO_WRAP)) }.getOrNull()
            }
            .filterNotNull()
            .toList()
    }

    override fun forget(peerStaticPub: ByteArray) {
        val hex = peerStaticPub.toHex()
        prefs.edit()
            .remove("peer-$hex")
            .remove("counter-$hex")
            .remove("audio_out_nonce-$hex")
            .remove("audio_in_nonce-$hex")
            .apply()
    }

    override fun loadCounter(peerStaticPub: ByteArray): Long {
        val key = "counter-${peerStaticPub.toHex()}"
        synchronized(counterLock) {
            return prefs.getLong(key, 0L)
        }
    }

    override fun bumpCounter(peerStaticPub: ByteArray, peerSeen: Long): Long {
        val key = "counter-${peerStaticPub.toHex()}"
        synchronized(counterLock) {
            val local = prefs.getLong(key, 0L)
            val next = maxOf(local, peerSeen) + 1
            prefs.edit().putLong(key, next).commit()
            return next
        }
    }

    private val counterLock = Any()
    private val nonceLock = Any()

    override fun nextAudioOutNonce(peerStaticPub: ByteArray): Long {
        val key = "audio_out_nonce-${peerStaticPub.toHex()}"
        synchronized(nonceLock) {
            val current = prefs.getLong(key, 0L)
            val next = current + 1
            prefs.edit().putLong(key, next).commit()
            return next
        }
    }

    override fun loadAudioInNonce(peerStaticPub: ByteArray): Long {
        val key = "audio_in_nonce-${peerStaticPub.toHex()}"
        synchronized(nonceLock) {
            return prefs.getLong(key, 0L)
        }
    }

    override fun commitAudioInNonce(peerStaticPub: ByteArray, nonce: Long) {
        val key = "audio_in_nonce-${peerStaticPub.toHex()}"
        synchronized(nonceLock) {
            val current = prefs.getLong(key, 0L)
            if (nonce > current) {
                prefs.edit().putLong(key, nonce).commit()
            }
        }
    }

    override fun tryAcceptAudioInNonce(peerStaticPub: ByteArray, nonce: Long): Boolean {
        val key = "audio_in_nonce-${peerStaticPub.toHex()}"
        synchronized(nonceLock) {
            val current = prefs.getLong(key, 0L)
            if (nonce <= current) return false
            prefs.edit().putLong(key, nonce).commit()
            return true
        }
    }

    companion object {
        const val MASTER_KEY_ALIAS = "com.vortex.peers.v1.master"
        const val PREFS_FILENAME = "vortex_peers_v1"
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
