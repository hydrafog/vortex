package com.vortex.a3.core.crypto

object Derive {
    val PRS_LABEL: ByteArray = "vortex/v1/prs".toByteArray(Charsets.UTF_8)
    val SES_LABEL: ByteArray = "vortex/v1/ses".toByteArray(Charsets.UTF_8)

    fun prs(chainingKey: ByteArray): ByteArray = Hmac.sha256(PRS_LABEL, chainingKey)

    fun ses(transcriptHash: ByteArray): ByteArray = Hmac.sha256(SES_LABEL, transcriptHash)
}
