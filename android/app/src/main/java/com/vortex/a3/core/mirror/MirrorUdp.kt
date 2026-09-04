package com.vortex.a3.core.mirror

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MirrorUdp {
    const val MAX_FRAGMENT_DATA: Int = 1100
    const val INNER_HEADER: Int = 8
    private val KEY_INFO = "vortex/mirror/udp".toByteArray(Charsets.US_ASCII)

    fun deriveMediaKey(handshakeHash: ByteArray): ByteArray =
        hkdfSha256(handshakeHash, KEY_INFO, 32)

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var pos = 0
        var block = 1
        var t = ByteArray(0)
        while (pos < len) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(block.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            block++
        }
        return out
    }
}

class MirrorSealer(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "ChaCha20")
    private var counter: Long = 0

    fun sealAccessUnit(frameId: Int, au: ByteArray): List<ByteArray> {
        val fragCount =
            if (au.isEmpty()) 1
            else (au.size + MirrorUdp.MAX_FRAGMENT_DATA - 1) / MirrorUdp.MAX_FRAGMENT_DATA
        val out = ArrayList<ByteArray>(fragCount)
        var off = 0
        for (idx in 0 until fragCount) {
            val len = minOf(MirrorUdp.MAX_FRAGMENT_DATA, au.size - off).coerceAtLeast(0)
            val inner = ByteBuffer.allocate(MirrorUdp.INNER_HEADER + len).order(ByteOrder.BIG_ENDIAN)
            inner.putInt(frameId)
            inner.putShort(idx.toShort())
            inner.putShort(fragCount.toShort())
            if (len > 0) inner.put(au, off, len)
            off += len

            val counterVal = counter
            counter++
            val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putLong(counterVal).array()
            val nonce = ByteArray(12)
            System.arraycopy(counterBytes, 0, nonce, 4, 8)

            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
            cipher.updateAAD(counterBytes)
            val ct = cipher.doFinal(inner.array())

            val pkt = ByteArray(8 + ct.size)
            System.arraycopy(counterBytes, 0, pkt, 0, 8)
            System.arraycopy(ct, 0, pkt, 8, ct.size)
            out.add(pkt)
        }
        return out
    }
}
