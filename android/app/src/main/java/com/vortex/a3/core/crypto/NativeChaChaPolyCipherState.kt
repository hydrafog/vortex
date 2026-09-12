package com.vortex.a3.core.crypto

import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import java.lang.reflect.Field
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.ShortBufferException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NativeChaChaPolyCipherState private constructor(
    private val cipher: Cipher
) : CipherState {

    private var key: ByteArray? = null
    private var keySpec: SecretKeySpec? = null
    private var n: Long = 0L
    private var hasKey: Boolean = false
    private val iv = ByteArray(12)

    override fun destroy() {
        key?.let { Arrays.fill(it, 0.toByte()) }
        key = null
        keySpec = null
        hasKey = false
    }

    override fun getCipherName(): String = "ChaChaPoly"
    override fun getKeyLength(): Int = 32
    override fun getMACLength(): Int = 16

    override fun initializeKey(keyBytes: ByteArray, offset: Int) {
        this.key = keyBytes.copyOfRange(offset, offset + 32)
        this.keySpec = SecretKeySpec(this.key, "ChaCha20")
        this.n = 0L
        this.hasKey = true
    }

    override fun hasKey(): Boolean = hasKey

    private fun prepareIv(nonce: Long) {
        iv[4] = (nonce and 0xFF).toByte()
        iv[5] = ((nonce ushr 8) and 0xFF).toByte()
        iv[6] = ((nonce ushr 16) and 0xFF).toByte()
        iv[7] = ((nonce ushr 24) and 0xFF).toByte()
        iv[8] = ((nonce ushr 32) and 0xFF).toByte()
        iv[9] = ((nonce ushr 40) and 0xFF).toByte()
        iv[10] = ((nonce ushr 48) and 0xFF).toByte()
        iv[11] = ((nonce ushr 56) and 0xFF).toByte()
    }

    @Throws(ShortBufferException::class)
    override fun encryptWithAd(
        ad: ByteArray?,
        plaintext: ByteArray,
        plaintextOffset: Int,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        length: Int
    ): Int {
        if (!hasKey) {
            if (ciphertext.size - ciphertextOffset < length) {
                throw ShortBufferException("Output buffer too small")
            }
            System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length)
            return length
        }
        if (ciphertext.size - ciphertextOffset < length + 16) {
            throw ShortBufferException("Output buffer too small for ciphertext and MAC")
        }
        try {
            prepareIv(n)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
            if (ad != null && ad.isNotEmpty()) {
                cipher.updateAAD(ad)
            }
            val outLen = cipher.doFinal(plaintext, plaintextOffset, length, ciphertext, ciphertextOffset)
            n++
            return outLen
        } catch (e: ShortBufferException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("encryptWithAd failed: ${e.message}", e)
        }
    }

    @Throws(ShortBufferException::class, BadPaddingException::class)
    override fun decryptWithAd(
        ad: ByteArray?,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        plaintext: ByteArray,
        plaintextOffset: Int,
        length: Int
    ): Int {
        if (!hasKey) {
            if (plaintext.size - plaintextOffset < length) {
                throw ShortBufferException("Output buffer too small")
            }
            System.arraycopy(ciphertext, ciphertextOffset, plaintext, plaintextOffset, length)
            return length
        }
        if (length < 16) {
            throw BadPaddingException("Ciphertext shorter than MAC tag")
        }
        if (plaintext.size - plaintextOffset < length - 16) {
            throw ShortBufferException("Output buffer too small for plaintext")
        }
        try {
            prepareIv(n)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            if (ad != null && ad.isNotEmpty()) {
                cipher.updateAAD(ad)
            }
            val outLen = cipher.doFinal(ciphertext, ciphertextOffset, length, plaintext, plaintextOffset)
            n++
            return outLen
        } catch (e: ShortBufferException) {
            throw e
        } catch (e: BadPaddingException) {
            throw e
        } catch (e: Exception) {
            throw BadPaddingException("decryptWithAd failed: ${e.message}")
        }
    }

    override fun fork(keyBytes: ByteArray, offset: Int): CipherState {
        val forked = create() ?: throw IllegalStateException("Native ChaCha20-Poly1305 unavailable")
        forked.initializeKey(keyBytes, offset)
        return forked
    }

    override fun setNonce(nonce: Long) {
        this.n = nonce
    }

    companion object {
        private const val TAG = "NativeChaChaPoly"

        fun create(): NativeChaChaPolyCipherState? {
            return try {
                val c = Cipher.getInstance("ChaCha20-Poly1305")
                NativeChaChaPolyCipherState(c)
            } catch (e: Throwable) {
                Log.w(TAG, "Native ChaCha20-Poly1305 cipher not available: ${e.message}")
                null
            }
        }

        fun wrapIfPossible(original: CipherState?): CipherState {
            if (original == null) throw IllegalArgumentException("original cannot be null")
            if (original is NativeChaChaPolyCipherState) return original
            if (!original.hasKey()) return original

            val nativeState = create() ?: return original
            return try {
                val cls = original.javaClass
                val inputField: Field = cls.getDeclaredField("input").apply { isAccessible = true }
                val nField: Field = cls.getDeclaredField("n").apply { isAccessible = true }
                val input = inputField.get(original) as? IntArray ?: return original
                val n = nField.getLong(original)
                if (input.size < 12) return original

                val key = ByteArray(32)
                for (i in 0 until 8) {
                    val w = input[4 + i]
                    key[i * 4] = (w and 0xFF).toByte()
                    key[i * 4 + 1] = ((w ushr 8) and 0xFF).toByte()
                    key[i * 4 + 2] = ((w ushr 16) and 0xFF).toByte()
                    key[i * 4 + 3] = ((w ushr 24) and 0xFF).toByte()
                }

                nativeState.initializeKey(key, 0)
                nativeState.setNonce(n)
                Log.i(TAG, "Accelerated Noise CipherState with native ChaCha20-Poly1305 (nonce=$n)")
                nativeState
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to extract key from CipherState for native acceleration: ${t.message}")
                original
            }
        }

        fun wrapPair(pair: CipherStatePair): CipherStatePair {
            val sender = wrapIfPossible(pair.sender)
            val receiver = wrapIfPossible(pair.receiver)
            return CipherStatePair(sender, receiver)
        }
    }
}
