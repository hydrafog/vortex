package com.vortex.a3.core.crypto

import java.nio.ByteBuffer

object Sas {
    val LABEL: ByteArray = "vortex/v1/sas".toByteArray(Charsets.UTF_8)

    fun derive(transcriptHash: ByteArray): Pair<Int, String> {
        val mac = Hmac.sha256(LABEL, transcriptHash)
        val sasInt = ByteBuffer.wrap(mac, 0, 4).int.toLong() and 0xFFFFFFFFL
        val sasValue = (sasInt % 1_000_000L).toInt()
        return sasValue to "%06d".format(sasValue)
    }
}
