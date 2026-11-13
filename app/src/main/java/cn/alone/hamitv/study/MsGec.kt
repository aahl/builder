package cn.alone.hamitv.study

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/**
 * The DRM token the read-aloud endpoint demands (`Sec-MS-GEC`, v2).
 *
 * It is `<CS64>-<SHA256>`, but neither part is the standard hash: Microsoft
 * seeds SHA-256 with a custom state derived from the first seven bytes of the
 * message and mixes a second digest through a table of rotations. This is a
 * straight port of rany2/edge-tts `drm.py`, checked against its output in
 * [cn.alone.hamitv.study.MsGecTest] — do not "simplify" it.
 */
internal object MsGec {

    private const val TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH_SECONDS = 11_644_473_600.0
    private const val M32 = 0xFFFFFFFFL

    private val SHA_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val SHA_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SHA_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L,
        0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L,
    )
    private val ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L,
        0xAB1C5ED5L, 0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL,
        0x9BDC06A7L, 0xC19BF174L, 0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL,
        0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL, 0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L, 0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL,
        0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L, 0xA2BFE8A1L, 0xA81A664BL,
        0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L, 0x19A4C116L,
        0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L,
        0xC67178F2L,
    )
    private const val SHA_ADJUSTMENT = 0xC9DC06A7L
    private val BLOCK_CONSTANTS: LongArray
        get() = ROUND_CONSTANTS.copyOf().also { it[14] = SHA_ADJUSTMENT }
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0L

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L
    private val CS64_COEFFICIENTS = longArrayOf(
        0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L, 0x8D1EC211L, 0x314EF5D5L, 0xE92491B1L,
        0xF0A5B9D5L, 0x06064CF3L, 0xDA79FA60L, 0xBED20560L, 0x89F9C9F8L, 0x35FA38C6L, 0xCB49E3C6L,
    )
    private val CS64_OFFSETS = longArrayOf(
        0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL, 0xD405B4D5L, 0x26E55F3CL, 0x607801C6L,
        0x82B2C1FBL, 0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L, 0xBB04A2C1L, 0xC5626167L,
    )

    /** Token for [nowMs], rounded down to the five-minute bucket the server expects. */
    fun token(nowMs: Long = System.currentTimeMillis()): String {
        var ticks = nowMs / 1000.0 + WIN_EPOCH_SECONDS
        ticks -= ticks % 300.0
        ticks *= 10_000_000.0
        val data = (String.format(Locale.US, "%.0f", ticks) + TRUSTED).toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (swap, acc) = cs64Digest(data, digest)
        val packed = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swap.toInt())
            .putInt(acc.toInt())
            .array()
        return packed.toHexUpper() + "-" + digest.toHexUpper()
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "token salt needs at least 7 bytes" }
        val length = 57 + data.size
        val tail = data.copyOfRange(7, data.size) +
            byteArrayOf(0x80.toByte()) +
            ByteArray(((55 - length) % 64 + 64) % 64) +
            ByteBuffer.allocate(8).putLong(length.toLong() * 8).array()

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < tail.size) {
            val words = LongArray(64)
            for (i in 0 until 16) {
                val base = offset + i * 4
                words[i] = ((tail[base].toLong() and 0xFF) shl 24) or
                    ((tail[base + 1].toLong() and 0xFF) shl 16) or
                    ((tail[base + 2].toLong() and 0xFF) shl 8) or
                    (tail[base + 3].toLong() and 0xFF)
            }
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            state = LongArray(8) { (state[it] + working[it]) and M32 }
            offset += 64
        }
        val out = ByteBuffer.allocate(32)
        state.forEach { out.putInt(it.toInt()) }
        return out.array()
    }

    private fun sha256Init(seed: ByteArray): LongArray {
        val words = LongArray(64)
        words[14] = ((seed[0].toLong() and 0xFF) shl 16) or
            ((seed[1].toLong() and 0xFF) shl 8) or
            (seed[2].toLong() and 0xFF)
        words[15] = ((seed[3].toLong() and 0xFF) shl 24) or
            ((seed[4].toLong() and 0xFF) shl 16) or
            ((seed[5].toLong() and 0xFF) shl 8) or
            (seed[6].toLong() and 0xFF)
        for (index in 16 until 30) {
            var value = SHA_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words[index] = value and M32
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA_ADJUSTMENT - ROUND_CONSTANTS[14]) and M32
        expandSchedule(words, 31)
        val working = rounds(SHA_STATE, words, 14)
        return LongArray(8) { (SHA_INITIAL[it] + working[it]) and M32 }
    }

    /** Eight rounds are unrolled per pass; the register roles rotate instead of the values. */
    private fun rounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) BLOCK_CONSTANTS else ROUND_CONSTANTS
        val aligned = minOf(64, (firstRound + 7) / 8 * 8)

        var index = firstRound
        while (index < aligned) {
            val rotE = e * 0x100000001L
            val rotA = a * 0x100000001L
            val t1 = h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (g xor (e and (f xor g))) + constants[index] + words[index]
            val t2 = ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((a and b) or (c and (a or b)))
            val nextA = (t1 + t2) and M32
            val nextE = (d + t1) and M32
            h = g
            g = f
            f = e
            e = nextE
            d = c
            c = b
            b = a
            a = nextA
            index += 1
        }

        while (index < 64) {
            val i = index
            var rotE = e * 0x100000001L
            var rotA = a * 0x100000001L
            var t1 = h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (g xor (e and (f xor g))) + constants[i] + words[i]
            d = (d + t1) and M32
            h = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((a and b) or (c and (a or b)))) and M32

            rotE = d * 0x100000001L
            rotA = h * 0x100000001L
            t1 = g + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (f xor (d and (e xor f))) + constants[i + 1] + words[i + 1]
            c = (c + t1) and M32
            g = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((h and a) or (b and (h or a)))) and M32

            rotE = c * 0x100000001L
            rotA = g * 0x100000001L
            t1 = f + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (e xor (c and (d xor e))) + constants[i + 2] + words[i + 2]
            b = (b + t1) and M32
            f = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((g and h) or (a and (g or h)))) and M32

            rotE = b * 0x100000001L
            rotA = f * 0x100000001L
            t1 = e + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (d xor (b and (c xor d))) + constants[i + 3] + words[i + 3]
            a = (a + t1) and M32
            e = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((f and g) or (h and (f or g)))) and M32

            rotE = a * 0x100000001L
            rotA = e * 0x100000001L
            t1 = d + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (c xor (a and (b xor c))) + constants[i + 4] + words[i + 4]
            h = (h + t1) and M32
            d = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((e and f) or (g and (e or f)))) and M32

            rotE = h * 0x100000001L
            rotA = d * 0x100000001L
            t1 = c + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (b xor (h and (a xor b))) + constants[i + 5] + words[i + 5]
            g = (g + t1) and M32
            c = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((d and e) or (f and (d or e)))) and M32

            rotE = g * 0x100000001L
            rotA = c * 0x100000001L
            t1 = b + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (a xor (g and (h xor a))) + constants[i + 6] + words[i + 6]
            f = (f + t1) and M32
            b = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((c and d) or (e and (c or d)))) and M32

            rotE = f * 0x100000001L
            rotA = b * 0x100000001L
            t1 = a + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                (h xor (f and (g xor h))) + constants[i + 7] + words[i + 7]
            e = (e + t1) and M32
            a = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                ((b and c) or (d and (b or c)))) and M32

            index += 8
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: LongArray, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val sigma1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val sigma0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words[index] = (sigma1 + words[index - 7] + sigma0 + words[index - 16]) and M32
        }
    }

    private fun sigma0(value: Long): Long =
        ((value ushr 7) xor (value shl 25) xor (value ushr 18) xor (value shl 14) xor (value ushr 3)) and M32

    private fun sigma1(value: Long): Long =
        ((value ushr 17) xor (value shl 15) xor (value ushr 19) xor (value shl 13) xor (value ushr 10)) and M32

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val keyA = littleEndianWord(digest, 0) or 1L
        val keyB = littleEndianWord(digest, 4) or 1L
        val factorA = (keyA * CS64_FACTOR) and M32
        val factorB = (keyB * CS64_FACTOR) and M32

        val terms = ArrayList<Long>(CS64_COEFFICIENTS.size + data.size / 4 + 2)
        val offsets = ArrayList<Long>(CS64_COEFFICIENTS.size + data.size / 4 + 2)
        CS64_COEFFICIENTS.forEachIndexed { index, coefficient ->
            terms.add((coefficient * (if (index % 2 == 0) factorA else factorB)) and M32)
            offsets.add(CS64_OFFSETS[index])
        }

        val prefixed = ByteArray(data.size + 1)
        System.arraycopy(data, 0, prefixed, 1, data.size)
        wordsLe4Even(prefixed).forEachIndexed { index, word ->
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factorA
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and M32)
            offsets.add(offset and M32)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]

            var t = rol16((offsets[index] + x) and M32)
            var q = (t * 0x76F50F34L) and M32
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M32
            t = rol16((offsets[index + 1] + y) and M32)
            q = (t * 0x1881CE3CL) and M32
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M32
            swapAcc = (swapAcc + swapLast) and M32

            state = (state * keyA + x) and M32
            state = (rol16(state) * 0xCF98B111L) and M32
            state = (rol16(state) * 0x87085B9FL) and M32
            state = (rol16(state) * 0x12CEB96DL) and M32
            state = (rol16(state) * 0x257E1D83L) and M32
            acc += state
            t = (state * keyB + y) and M32
            t = (rol16(t) * 0xA27416F5L) and M32
            t = (rol16(t) * 0xD38396FFL) and M32
            state = (rol16((rol16(t) * 0x7C932B89L) and M32) * 0xBFA49F69L) and M32
            acc = (acc + state) and M32

            index += 2
        }
        return ((swapLast xor state) and M32) to ((swapAcc xor acc) and M32)
    }

    private fun rol16(value: Long): Long {
        val v = value and M32
        return ((v shl 16) or (v ushr 16)) and M32
    }

    private fun littleEndianWord(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val padded = ByteArray((data.size + 3) / 4 * 4)
        System.arraycopy(data, 0, padded, 0, data.size)
        val count = padded.size / 4
        val words = LongArray(count + if (count % 2 == 1) 1 else 0)
        for (i in 0 until count) {
            words[i] = (padded[i * 4].toLong() and 0xFF) or
                ((padded[i * 4 + 1].toLong() and 0xFF) shl 8) or
                ((padded[i * 4 + 2].toLong() and 0xFF) shl 16) or
                ((padded[i * 4 + 3].toLong() and 0xFF) shl 24)
        }
        return words
    }

    private fun ByteArray.toHexUpper(): String =
        joinToString("") { String.format(Locale.US, "%02X", it) }
}
