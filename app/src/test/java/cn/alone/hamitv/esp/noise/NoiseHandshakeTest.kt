package cn.alone.hamitv.esp.noise

import cn.alone.hamitv.esp.NoiseFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Golden vectors produced by tools/noise_reference.py, an independent Python
 * implementation of Noise_NNpsk0_25519_ChaChaPoly_SHA256 using `cryptography`.
 * If the Kotlin side drifts by a single byte, these fail.
 */
class NoiseHandshakeTest {

    private val psk = ByteArray(32) { it.toByte() }
    private val initiatorEphemeral = ByteArray(32) { 0x11 }
    private val responderEphemeral = ByteArray(32) { 0x22 }
    private val prologue = NoiseHandshake.prologue(ByteArray(0))

    private val expectedMessage1 =
        "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13" +
            "66bf64c5cbb260ac3183c475cbe90867"
    private val expectedMessage2 =
        "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20" +
            "6baf0cb4f2a0d4e5bb3ff3ad4360b874"
    private val expectedInitiatorFrame = "5a21e9c8e1227f97526d65f66b39b80270710a6cc505c4e313"
    private val expectedResponderFrame = "997a4b828d93665b46d2d479645592bcd3508276a47875e1eb"

    @Test
    fun prologueBindsTheHelloFrame() {
        assertArrayEquals(
            "NoiseAPIInit".toByteArray() + byteArrayOf(0, 0),
            NoiseHandshake.prologue(ByteArray(0)),
        )
        // A non-empty hello frame is length prefixed, big endian.
        assertArrayEquals(
            "NoiseAPIInit".toByteArray() + byteArrayOf(0, 2, 0xab.toByte(), 0xcd.toByte()),
            NoiseHandshake.prologue(byteArrayOf(0xab.toByte(), 0xcd.toByte())),
        )
    }

    @Test
    fun handshakeMatchesTheReferenceImplementation() {
        val initiator = NoiseHandshake(psk, prologue, NoiseHandshake.Role.INITIATOR, initiatorEphemeral)
        val responder = NoiseHandshake(psk, prologue, NoiseHandshake.Role.RESPONDER, responderEphemeral)

        val message1 = initiator.writeMessage1()
        assertEquals(expectedMessage1, message1.toHex())

        responder.readMessage1(message1)
        val message2 = responder.writeMessage2()
        assertEquals(expectedMessage2, message2.toHex())

        initiator.readMessage2(message2)
        initiator.split()
        responder.split()

        val outbound = NoiseFrame.encode(requireNotNull(initiator.sendCipher), 1, "hello".toByteArray())
        assertEquals(expectedInitiatorFrame, outbound.toHex())

        val received = NoiseFrame.read(outbound.inputStream(), requireNotNull(responder.receiveCipher))
        assertEquals(1, received.typeId)
        assertEquals("hello", received.body.decodeToString())

        val reply = NoiseFrame.encode(requireNotNull(responder.sendCipher), 2, "hello".toByteArray())
        assertEquals(expectedResponderFrame, reply.toHex())

        val answer = NoiseFrame.read(reply.inputStream(), requireNotNull(initiator.receiveCipher))
        assertEquals(2, answer.typeId)
        assertEquals("hello", answer.body.decodeToString())
    }

    @Test
    fun wrongPskFailsTheHandshake() {
        val initiator = NoiseHandshake(psk, prologue, NoiseHandshake.Role.INITIATOR, initiatorEphemeral)
        val responder = NoiseHandshake(
            ByteArray(32) { (it + 1).toByte() },
            prologue,
            NoiseHandshake.Role.RESPONDER,
            responderEphemeral,
        )

        val message1 = initiator.writeMessage1()

        assertThrows(AEADBadTagException::class.java) { responder.readMessage1(message1) }
    }

    @Test
    fun framesCarryTheNoiseIndicatorAndBigEndianLength() {
        val initiator = NoiseHandshake(psk, prologue, NoiseHandshake.Role.INITIATOR, initiatorEphemeral)
        val responder = NoiseHandshake(psk, prologue, NoiseHandshake.Role.RESPONDER, responderEphemeral)
        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        initiator.split()
        responder.split()

        val body = ByteArray(300) { it.toByte() }
        val frame = NoiseFrame.encode(requireNotNull(initiator.sendCipher), 0x0102, body)

        assertEquals(NoiseFrame.INDICATOR, frame[0].toInt())
        val length = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        // 4 byte inner header + body + 16 byte tag
        assertEquals(4 + body.size + NoiseFrame.MAC_SIZE, length)
        assertEquals(3 + length, frame.size)
    }

    @Test
    fun tamperedFrameIsRejected() {
        val initiator = NoiseHandshake(psk, prologue, NoiseHandshake.Role.INITIATOR, initiatorEphemeral)
        val responder = NoiseHandshake(psk, prologue, NoiseHandshake.Role.RESPONDER, responderEphemeral)
        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        initiator.split()
        responder.split()

        val frame = NoiseFrame.encode(requireNotNull(initiator.sendCipher), 1, "hello".toByteArray())
        frame[5] = (frame[5].toInt() xor 0x40).toByte()

        assertThrows(AEADBadTagException::class.java) {
            NoiseFrame.read(frame.inputStream(), requireNotNull(responder.receiveCipher))
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
