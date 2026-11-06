package cn.alone.hamitv.esp.noise

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256 helpers and the HKDF used by Noise (RFC 5869 with the Noise labels). */
internal object NoiseCrypto {

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF(chaining_key, ikm, count): the first `count` 32-byte outputs. */
    fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, count: Int): List<ByteArray> {
        val tempKey = hmac(chainingKey, inputKeyMaterial)
        val out = ArrayList<ByteArray>(count)
        var previous = ByteArray(0)
        for (i in 1..count) {
            previous = hmac(tempKey, previous + byteArrayOf(i.toByte()))
            out += previous
        }
        return out
    }
}

/**
 * One direction of the transport cipher. The nonce is the 96-bit value Noise
 * prescribes: 32 zero bits followed by a little-endian 64-bit counter, which is
 * exactly the 12-byte IV ChaCha20-Poly1305 wants.
 *
 * The AEAD is our own: the TV's Android 9 has no JCA provider for it.
 */
class CipherState(private val key: ByteArray?) {

    private var nonce: Long = 0

    val hasKey: Boolean get() = key != null

    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = key ?: return plaintext
        val bytes = nonceBytes()
        nonce++
        return ChaCha20Poly1305.seal(key, bytes, aad, plaintext)
    }

    fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val key = key ?: return ciphertext
        val bytes = nonceBytes()
        nonce++
        return ChaCha20Poly1305.open(key, bytes, aad, ciphertext)
    }

    private fun nonceBytes(): ByteArray {
        val bytes = ByteArray(12)
        var value = nonce
        for (i in 0 until 8) {
            bytes[4 + i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return bytes
    }
}

/**
 * The `SymmetricState` half of the Noise spec: chaining key, handshake hash and
 * the current cipher. Shared by both handshake roles.
 */
internal class SymmetricState(protocolName: ByteArray, prologue: ByteArray) {

    var chainingKey: ByteArray = NoiseCrypto.sha256(protocolName)
        private set

    var handshakeHash: ByteArray = chainingKey
        private set

    var cipher = CipherState(null)
        private set

    init {
        // InitializeSymmetric only mixes the prologue; hashing h into itself
        // first is not in the spec and desynchronises every other client.
        mixHash(prologue)
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = NoiseCrypto.sha256(handshakeHash, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (ck, tempK) = NoiseCrypto.hkdf(chainingKey, inputKeyMaterial, 2)
        chainingKey = ck
        cipher = CipherState(tempK)
    }

    /** The `psk` token: mixes a hash of the PSK into h and the key into k. */
    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (ck, tempH, tempK) = NoiseCrypto.hkdf(chainingKey, inputKeyMaterial, 3)
        chainingKey = ck
        mixHash(tempH)
        cipher = CipherState(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encrypt(plaintext, handshakeHash)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decrypt(ciphertext, handshakeHash)
        mixHash(ciphertext)
        return plaintext
    }

    /** Returns the two transport ciphers; the caller picks which one it sends on. */
    fun split(): Pair<CipherState, CipherState> {
        val (first, second) = NoiseCrypto.hkdf(chainingKey, ByteArray(0), 2)
        return CipherState(first) to CipherState(second)
    }
}
