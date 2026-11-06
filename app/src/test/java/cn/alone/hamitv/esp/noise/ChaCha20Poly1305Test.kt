package cn.alone.hamitv.esp.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * RFC 8439 vectors. These matter more than usual: the TV has no JCA provider
 * for this AEAD, so this implementation is the only one that runs on device.
 */
class ChaCha20Poly1305Test {

    private fun hex(value: String): ByteArray =
        value.replace(" ", "").replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val nonce = hex("070000004041424344454647")
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")

    @Test
    fun matchesRfc8439AeadVector() {
        val plaintext = hex(
            "4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f6620" +
                "2739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074" +
                "697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062" +
                "652069742e",
        )
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8c" +
                "a9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c98" +
                "03aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586" +
                "cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )
        assertArrayEquals(expected, ChaCha20Poly1305.seal(key, nonce, aad, plaintext))
        assertArrayEquals(plaintext, ChaCha20Poly1305.open(key, nonce, aad, expected))
    }

    @Test
    fun rejectsATamperedTag() {
        val sealed = ChaCha20Poly1305.seal(key, nonce, aad, "hello".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        val result = runCatching { ChaCha20Poly1305.open(key, nonce, aad, sealed) }
        assertTrue(result.exceptionOrNull() is AEADBadTagException)
    }

    @Test
    fun roundTripsEveryLengthAroundBlockBoundaries() {
        for (size in 0..130) {
            val data = ByteArray(size) { (it * 7).toByte() }
            val sealed = ChaCha20Poly1305.seal(key, nonce, aad, data)
            assertEquals(size + 16, sealed.size)
            assertArrayEquals(data, ChaCha20Poly1305.open(key, nonce, aad, sealed))
        }
    }

    @Test
    fun emptyPayloadMatchesTheHandshakeCase() {
        val sealed = ChaCha20Poly1305.seal(key, nonce, ByteArray(0), ByteArray(0))
        assertEquals(16, sealed.size)
        assertEquals(0, ChaCha20Poly1305.open(key, nonce, ByteArray(0), sealed).size)
    }
}
