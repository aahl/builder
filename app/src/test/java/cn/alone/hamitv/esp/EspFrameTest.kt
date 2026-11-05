package cn.alone.hamitv.esp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EspFrameTest {

    @Test
    fun varintRoundTrip() {
        val values = intArrayOf(0, 1, 127, 128, 300, 16384, 2_097_151)
        for (value in values) {
            val out = ByteArrayOutputStream()
            Varint.write(out, value)
            val bytes = out.toByteArray()
            assertEquals(Varint.size(value), bytes.size)
            assertEquals(value, Varint.read(ByteArrayInputStream(bytes)))
            val (decoded, next) = Varint.decode(bytes, 0)
            assertEquals(value, decoded)
            assertEquals(bytes.size, next)
        }
    }

    @Test
    fun frameRoundTrip() {
        val body = byteArrayOf(0x08, 0x01, 0x12, 0x03)
        val encoded = EspFrame.encode(131, body)

        val frame = EspFrame.read(ByteArrayInputStream(encoded))
        assertEquals(EspFrame.INDICATOR_PLAINTEXT, frame.indicator)
        assertEquals(131, frame.typeId)
        assertArrayEquals(body, frame.body)
    }

    @Test
    fun lengthCoversOnlyTheBody() {
        // The type varint sits between the length and the body and is not
        // counted, which is what aioesphomeapi and the firmware expect.
        val body = ByteArray(19) { 0x2A }
        val encoded = EspFrame.encode(1, body)

        assertEquals(0x00, encoded[0].toInt())
        assertEquals(19, encoded[1].toInt())
        assertEquals(1, encoded[2].toInt())
        assertEquals(22, encoded.size)
    }

    @Test
    fun parsesAFrameCapturedFromAioesphomeapi() {
        // 00 13 01 0a 0d "aioesphomeapi" 10 01 18 13
        val captured = byteArrayOf(
            0x00, 0x13, 0x01, 0x0A, 0x0D,
            0x61, 0x69, 0x6F, 0x65, 0x73, 0x70, 0x68, 0x6F, 0x6D, 0x65, 0x61, 0x70, 0x69,
            0x10, 0x01, 0x18, 0x13,
        )

        val frame = EspFrame.read(ByteArrayInputStream(captured))
        assertEquals(1, frame.typeId)
        assertEquals(19, frame.body.size)
        assertEquals(0x0A, frame.body[0].toInt())
    }

    @Test
    fun consecutiveFramesAreReadIndependently() {
        val stream = ByteArrayOutputStream().apply {
            write(EspFrame.encode(1, byteArrayOf(0x01)))
            write(EspFrame.encode(2, byteArrayOf(0x02, 0x03)))
        }.toByteArray()

        val input = ByteArrayInputStream(stream)
        assertEquals(1, EspFrame.read(input).typeId)
        val second = EspFrame.read(input)
        assertEquals(2, second.typeId)
        assertArrayEquals(byteArrayOf(0x02, 0x03), second.body)
    }
}
