package com.vortex.a3.core.clipboard

import java.security.MessageDigest

object ClipboardSyncGuard {
    @Volatile private var lastSig: String = ""

    fun sig(text: String): String = sig(text.toByteArray(Charsets.UTF_8))

    fun sig(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(d[i]))
        return sb.toString()
    }

    fun markApplied(sig: String) {
        lastSig = sig
    }

    fun wasJustApplied(sig: String): Boolean = sig.isNotEmpty() && sig == lastSig
}
