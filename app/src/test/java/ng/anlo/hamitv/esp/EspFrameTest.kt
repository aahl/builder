package ng.anlo.hamitv.esp

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
