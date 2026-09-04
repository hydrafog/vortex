package com.vortex.a3.core.mirror

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MirrorTcpSealer(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "ChaCha20")
    private var counter: Long = 0

    fun sealAccessUnit(au: ByteArray): ByteArray {
        val counterVal = counter
        counter++
        val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putLong(counterVal).array()
        val nonce = ByteArray(12)
        System.arraycopy(counterBytes, 0, nonce, 4, 8)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
        cipher.updateAAD(counterBytes)
        val ct = cipher.doFinal(au)

        val msgLen = 8 + ct.size
        return ByteBuffer.allocate(4 + msgLen).order(ByteOrder.BIG_ENDIAN)
            .putInt(msgLen)
            .put(counterBytes)
            .put(ct)
            .array()
    }
}
