package cn.alone.hamitv.esp.noise

import java.security.MessageDigest
import javax.crypto.AEADBadTagException

/**
 * ChaCha20-Poly1305 (RFC 8439) in pure Kotlin.
 *
 * The TV runs Android 9 and has no JCA provider for this AEAD: `Cipher
 * .getInstance("ChaCha20-Poly1305")` throws "No provider found", which killed
 * the handshake at the first encrypted message. Shipping our own also keeps the
 * APK free of BouncyCastle (~8MB).
 */
internal object ChaCha20Poly1305 {

    private const val TAG_SIZE = 16
    private const val BLOCK_SIZE = 64

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val ciphertext = chacha20(key, 1, nonce, plaintext)
        return ciphertext + tag(key, nonce, aad, ciphertext)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= TAG_SIZE) { "ciphertext too short: ${sealed.size}" }
        val split = sealed.size - TAG_SIZE
        val ciphertext = sealed.copyOfRange(0, split)
        val expected = tag(key, nonce, aad, ciphertext)
        val actual = sealed.copyOfRange(split, sealed.size)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw AEADBadTagException("poly1305 tag mismatch")
        }
        return chacha20(key, 1, nonce, ciphertext)
    }

    /** Poly1305 over the AEAD layout: aad, pad, ciphertext, pad, both lengths. */
    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val otk = chacha20Block(key, 0, nonce).copyOfRange(0, 32)
        val mac = ByteArray(aad.size + pad16(aad.size) + ciphertext.size + pad16(ciphertext.size) + 16)
        var offset = 0
        aad.copyInto(mac, offset)
        offset += aad.size + pad16(aad.size)
        ciphertext.copyInto(mac, offset)
        offset += ciphertext.size + pad16(ciphertext.size)
        writeLe64(mac, offset, aad.size.toLong())
        writeLe64(mac, offset + 8, ciphertext.size.toLong())
        return poly1305(mac, otk)
    }

    private fun pad16(size: Int): Int = (16 - (size % 16)) % 16

    private fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = counter
        var offset = 0
        while (offset < data.size) {
            val stream = chacha20Block(key, block, nonce)
            val count = minOf(BLOCK_SIZE, data.size - offset)
            for (i in 0 until count) {
                out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += count
            block++
        }
        return out
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = readLe32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = readLe32(nonce, i * 4)

        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }

        val out = ByteArray(BLOCK_SIZE)
        for (i in 0 until 16) writeLe32(out, i * 4, x[i] + state[i])
        return out
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }

    /**
     * Poly1305 with 26-bit limbs (the poly1305-donna layout). Each block adds
     * the 2^128 bit for full blocks, or the explicit 0x01 byte for the tail.
     */
    private fun poly1305(message: ByteArray, key: ByteArray): ByteArray {
        val r0 = readLe32(key, 0) and 0x3ffffff
        val r1 = (readLe32(key, 3) ushr 2) and 0x3ffff03
        val r2 = (readLe32(key, 6) ushr 4) and 0x3ffc0ff
        val r3 = (readLe32(key, 9) ushr 6) and 0x3f03fff
        val r4 = (readLe32(key, 12) ushr 8) and 0x00fffff

        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5

        var h0 = 0
        var h1 = 0
        var h2 = 0
        var h3 = 0
        var h4 = 0

        var offset = 0
        while (offset + 16 <= message.size) {
            h0 += readLe32(message, offset) and 0x3ffffff
            h1 += (readLe32(message, offset + 3) ushr 2) and 0x3ffffff
            h2 += (readLe32(message, offset + 6) ushr 4) and 0x3ffffff
            h3 += (readLe32(message, offset + 9) ushr 6) and 0x3ffffff
            h4 += (readLe32(message, offset + 12) ushr 8) or (1 shl 24)

            var d0 = h0.toLong() * r0 + h1.toLong() * s4 + h2.toLong() * s3 +
                h3.toLong() * s2 + h4.toLong() * s1
            var d1 = h0.toLong() * r1 + h1.toLong() * r0 + h2.toLong() * s4 +
                h3.toLong() * s3 + h4.toLong() * s2
            var d2 = h0.toLong() * r2 + h1.toLong() * r1 + h2.toLong() * r0 +
                h3.toLong() * s4 + h4.toLong() * s3
            var d3 = h0.toLong() * r3 + h1.toLong() * r2 + h2.toLong() * r1 +
                h3.toLong() * r0 + h4.toLong() * s4
            var d4 = h0.toLong() * r4 + h1.toLong() * r3 + h2.toLong() * r2 +
                h3.toLong() * r1 + h4.toLong() * r0

            var carry = (d0 shr 26).toInt()
            h0 = (d0 and 0x3ffffff).toInt()
            d1 += carry; carry = (d1 shr 26).toInt(); h1 = (d1 and 0x3ffffff).toInt()
            d2 += carry; carry = (d2 shr 26).toInt(); h2 = (d2 and 0x3ffffff).toInt()
            d3 += carry; carry = (d3 shr 26).toInt(); h3 = (d3 and 0x3ffffff).toInt()
            d4 += carry; carry = (d4 shr 26).toInt(); h4 = (d4 and 0x3ffffff).toInt()

            h0 += carry * 5
            carry = h0 shr 26
            h0 = h0 and 0x3ffffff
            h1 += carry

            offset += 16
        }

        if (offset < message.size) {
            val tail = ByteArray(16)
            message.copyInto(tail, 0, offset, message.size)
            tail[message.size - offset] = 1

            h0 += readLe32(tail, 0) and 0x3ffffff
            h1 += (readLe32(tail, 3) ushr 2) and 0x3ffffff
            h2 += (readLe32(tail, 6) ushr 4) and 0x3ffffff
            h3 += (readLe32(tail, 9) ushr 6) and 0x3ffffff
            h4 += readLe32(tail, 12) ushr 8

            var d0 = h0.toLong() * r0 + h1.toLong() * s4 + h2.toLong() * s3 +
                h3.toLong() * s2 + h4.toLong() * s1
            var d1 = h0.toLong() * r1 + h1.toLong() * r0 + h2.toLong() * s4 +
                h3.toLong() * s3 + h4.toLong() * s2
            var d2 = h0.toLong() * r2 + h1.toLong() * r1 + h2.toLong() * r0 +
                h3.toLong() * s4 + h4.toLong() * s3
            var d3 = h0.toLong() * r3 + h1.toLong() * r2 + h2.toLong() * r1 +
                h3.toLong() * r0 + h4.toLong() * s4
            var d4 = h0.toLong() * r4 + h1.toLong() * r3 + h2.toLong() * r2 +
                h3.toLong() * r1 + h4.toLong() * r0

            var carry = (d0 shr 26).toInt()
            h0 = (d0 and 0x3ffffff).toInt()
            d1 += carry; carry = (d1 shr 26).toInt(); h1 = (d1 and 0x3ffffff).toInt()
            d2 += carry; carry = (d2 shr 26).toInt(); h2 = (d2 and 0x3ffffff).toInt()
            d3 += carry; carry = (d3 shr 26).toInt(); h3 = (d3 and 0x3ffffff).toInt()
            d4 += carry; carry = (d4 shr 26).toInt(); h4 = (d4 and 0x3ffffff).toInt()

            h0 += carry * 5
            carry = h0 shr 26
            h0 = h0 and 0x3ffffff
            h1 += carry
        }

        // Fully carry h.
        var carry = h1 shr 26; h1 = h1 and 0x3ffffff; h2 += carry
        carry = h2 shr 26; h2 = h2 and 0x3ffffff; h3 += carry
        carry = h3 shr 26; h3 = h3 and 0x3ffffff; h4 += carry
        carry = h4 shr 26; h4 = h4 and 0x3ffffff; h0 += carry * 5
        carry = h0 shr 26; h0 = h0 and 0x3ffffff; h1 += carry

        // h + -p, then pick it when h >= p.
        var g0 = h0 + 5
        carry = g0 shr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + carry
        carry = g1 shr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + carry
        carry = g2 shr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + carry
        carry = g3 shr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + carry - (1 shl 26)

        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }

        val f0 = (h0.toLong() or (h1.toLong() shl 26)) and 0xFFFFFFFFL
        val f1 = ((h1 ushr 6).toLong() or (h2.toLong() shl 20)) and 0xFFFFFFFFL
        val f2 = ((h2 ushr 12).toLong() or (h3.toLong() shl 14)) and 0xFFFFFFFFL
        val f3 = ((h3 ushr 18).toLong() or (h4.toLong() shl 8)) and 0xFFFFFFFFL

        val s0 = readLe32(key, 16).toLong() and 0xFFFFFFFFL
        val sKey1 = readLe32(key, 20).toLong() and 0xFFFFFFFFL
        val sKey2 = readLe32(key, 24).toLong() and 0xFFFFFFFFL
        val sKey3 = readLe32(key, 28).toLong() and 0xFFFFFFFFL

        var t = f0 + s0
        val o0 = (t and 0xFFFFFFFFL).toInt()
        t = f1 + sKey1 + (t ushr 32)
        val o1 = (t and 0xFFFFFFFFL).toInt()
        t = f2 + sKey2 + (t ushr 32)
        val o2 = (t and 0xFFFFFFFFL).toInt()
        t = f3 + sKey3 + (t ushr 32)
        val o3 = (t and 0xFFFFFFFFL).toInt()

        val tag = ByteArray(16)
        writeLe32(tag, 0, o0)
        writeLe32(tag, 4, o1)
        writeLe32(tag, 8, o2)
        writeLe32(tag, 12, o3)
        return tag
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
}
