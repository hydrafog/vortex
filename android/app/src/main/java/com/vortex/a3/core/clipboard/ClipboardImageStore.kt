package com.vortex.a3.core.clipboard

import java.security.MessageDigest

object ClipboardImageStore {
    @Volatile private var token: String = ""
    @Volatile private var bytes: ByteArray? = null

    @Synchronized
    fun stash(png: ByteArray): String {
        val t = sha256Hex(png).take(16)
        token = t
        bytes = png
        return t
    }

    @Synchronized
    fun getByToken(token: String): ByteArray? =
        if (token.isNotEmpty() && token == this.token) bytes else null

    private fun sha256Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
