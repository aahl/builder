package cn.alone.hamitv.esp.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class X25519Test {

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun matchesRfc7748Vector() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val point = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")

        val shared = X25519.scalarMult(scalar, point)

        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            shared.toHex(),
        )
    }

    @Test
    fun derivesPublicKeysAndAgreesOnSharedSecret() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")

        val alicePublic = X25519.publicKey(alice)
        val bobPublic = X25519.publicKey(bob)

        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPublic.toHex())

        val shared = X25519.scalarMult(alice, bobPublic)
        assertArrayEquals(shared, X25519.scalarMult(bob, alicePublic))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", shared.toHex())
    }

    @Test
    fun generatedKeysAreUsable() {
        val pair = X25519.generateKeyPair()
        val other = X25519.generateKeyPair()

        assertEquals(32, pair.publicKey.size)
        assertArrayEquals(
            X25519.scalarMult(pair.privateKey, other.publicKey),
            X25519.scalarMult(other.privateKey, pair.publicKey),
        )
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
