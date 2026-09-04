package com.vortex.a3.core.clipboard

import java.security.MessageDigest

object ClipboardBlobStore {
    private const val MAX_ENTRIES = 32

    private val blobs = LinkedHashMap<String, ByteArray>()

    @Synchronized
    fun stash(bytes: ByteArray): String {
        val token = sha256Hex(bytes).take(16)
        blobs.remove(token)
        blobs[token] = bytes
        while (blobs.size > MAX_ENTRIES) {
            val oldest = blobs.keys.iterator().next()
            blobs.remove(oldest)
        }
        return token
    }

    @Synchronized
    fun getByToken(token: String): ByteArray? =
        if (token.isNotEmpty()) blobs[token] else null

    private fun sha256Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
