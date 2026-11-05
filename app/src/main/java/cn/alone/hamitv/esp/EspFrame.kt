package cn.alone.hamitv.esp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ESPHome native API framing.
 *
 * On the wire each frame is:
 *
 *     [indicator: 1 byte][body length: varint][message type: varint][body]
 *
 * The length covers the protobuf body only; the message type varint sits
 * between the length and the body and is not counted (see
 * api_frame_helper_plaintext.cpp in ESPHome). The indicator is 0x00 for
 * plaintext frames and 0x01 for Noise-protected ones.
 */
object EspFrame {

    const val INDICATOR_PLAINTEXT = 0x00
    const val INDICATOR_ENCRYPTED = 0x01

    data class Frame(val indicator: Int, val typeId: Int, val body: ByteArray)

    fun encode(typeId: Int, body: ByteArray, indicator: Int = INDICATOR_PLAINTEXT): ByteArray {
        val out = ByteArrayOutputStream(1 + Varint.size(body.size) + Varint.size(typeId) + body.size)
        out.write(indicator)
        Varint.write(out, body.size)
        Varint.write(out, typeId)
        out.write(body)
        return out.toByteArray()
    }

    fun write(out: OutputStream, typeId: Int, body: ByteArray, indicator: Int = INDICATOR_PLAINTEXT) {
        out.write(encode(typeId, body, indicator))
        out.flush()
    }

    fun read(input: InputStream): Frame {
        val indicator = input.read()
        if (indicator < 0) throw EOFException("stream closed")
        val bodyLength = Varint.read(input)
        if (bodyLength > MAX_BODY_LENGTH) {
            throw IllegalStateException("unreasonable body length $bodyLength")
        }
        val typeId = Varint.read(input)
        val body = ByteArray(bodyLength)
        var offset = 0
        while (offset < bodyLength) {
            val read = input.read(body, offset, bodyLength - offset)
            if (read < 0) throw EOFException("stream closed mid-frame")
            offset += read
        }
        return Frame(indicator, typeId, body)
    }

    /** Matches the firmware's MAX_MESSAGE_SIZE guard. */
    private const val MAX_BODY_LENGTH = 2_097_151
}
