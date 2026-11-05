package cn.alone.hamitv.esp.noise

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 (RFC 7748). Android ships no X25519 in JCA, and a full curve library
 * would add megabytes to the APK, so the ladder is done on BigInteger: a
 * handshake costs a couple of scalar multiplications, which is nothing.
 *
 * Not constant time. The key it protects is a 32-byte PSK exchanged over the
 * LAN, and the ladder runs on the TV's own CPU; a remote timing attack against
 * this is not a realistic threat here.
 */
object X25519 {

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    private val random = SecureRandom()

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val privateKey = ByteArray(32).also(random::nextBytes)
        return KeyPair(privateKey, publicKey(privateKey))
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    fun scalarMult(privateKey: ByteArray, point: ByteArray): ByteArray {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(point.size == 32) { "point must be 32 bytes" }

        val scalar = privateKey.copyOf()
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = (scalar[31].toInt() and 127).toByte()
        scalar[31] = (scalar[31].toInt() or 64).toByte()

        val x1 = decode(point)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (scalar[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = add(x2, z2)
            val aa = mul(a, a)
            val b = sub(x2, z2)
            val bb = mul(b, b)
            val e = sub(aa, bb)
            val c = add(x3, z3)
            val d = sub(x3, z3)
            val da = mul(d, a)
            val cb = mul(c, b)
            val sum = add(da, cb)
            val diff = sub(da, cb)
            x3 = mul(sum, sum)
            z3 = mul(x1, mul(diff, diff))
            x2 = mul(aa, bb)
            z2 = mul(e, add(aa, mul(A24, e)))
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encode(mul(x2, z2.modPow(P.subtract(BigInteger.valueOf(2)), P)))
    }

    private fun add(a: BigInteger, b: BigInteger) = a.add(b).mod(P)

    private fun sub(a: BigInteger, b: BigInteger) = a.subtract(b).mod(P)

    private fun mul(a: BigInteger, b: BigInteger) = a.multiply(b).mod(P)

    /** Little endian, top bit cleared (RFC 7748 §5). */
    private fun decode(bytes: ByteArray): BigInteger {
        val copy = bytes.copyOf()
        copy[31] = (copy[31].toInt() and 127).toByte()
        return BigInteger(1, copy.reversedArray())
    }

    private fun encode(value: BigInteger): ByteArray {
        val big = value.mod(P).toByteArray()
        val out = ByteArray(32)
        val trimmed = if (big.size > 32) big.copyOfRange(big.size - 32, big.size) else big
        for (i in trimmed.indices) out[i] = trimmed[trimmed.size - 1 - i]
        return out
    }
}
