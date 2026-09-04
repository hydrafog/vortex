package com.vortex.a3.service

import java.security.MessageDigest


internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun ByteArray.toHexPrefix(): String =
    take(4).joinToString("") { "%02x".format(it) } + "…"

internal fun ByteArray.notifHex(): String = toHex()

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
