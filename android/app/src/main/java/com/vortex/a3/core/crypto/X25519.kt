package com.vortex.a3.core.crypto

import com.southernstorm.noise.protocol.Noise

object X25519 {
    const val PRIV_LEN: Int = 32
    const val PUB_LEN: Int = 32

    fun publicFromPrivate(privateBytes: ByteArray): ByteArray {
        require(privateBytes.size == PRIV_LEN) { "X25519 private must be $PRIV_LEN bytes" }
        val dh = Noise.createDH("25519")
        try {
            dh.setPrivateKey(privateBytes, 0)
            val out = ByteArray(PUB_LEN)
            dh.getPublicKey(out, 0)
            return out
        } finally {
            dh.destroy()
        }
    }
}
