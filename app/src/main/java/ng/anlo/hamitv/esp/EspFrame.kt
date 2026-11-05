package ng.anlo.hamitv.esp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ESPHome native API framing.
 *
 * On the wire each frame is:
 *
 *     [indicator: 1 byte][payload length: varint][payload]
 *
 * and the payload itself is:
 *
 *     [message type id: varint][protobuf body]
 *
 * The indicator is 0x00 for plaintext frames and 0x01 for frames protected by
 * the Noise transport (handshake frames are also marked 0x01).
 */
object EspFrame {

    const val INDICATOR_PLAINTEXT = 0x00
    const val INDICATOR_ENCRYPTED = 0x01

    data class Frame(val indicator: Int, val typeId: Int, val body: ByteArray)

    fun encode(typeId: Int, body: ByteArray, indicator: Int = INDICATOR_PLAINTEXT): ByteArray {
        val payloadLength = Varint.size(typeId) + body.size
        val out = ByteArrayOutputStream(1 + Varint.size(payloadLength) + payloadLength)
        out.write(indicator)
        Varint.write(out, payloadLength)
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
        val payloadLength = Varint.read(input)
        val payload = ByteArray(payloadLength)
        var offset = 0
        while (offset < payloadLength) {
            val read = input.read(payload, offset, payloadLength - offset)
            if (read < 0) throw EOFException("stream closed mid-frame")
            offset += read
        }
        val (typeId, bodyStart) = Varint.decode(payload, 0)
        return Frame(indicator, typeId, payload.copyOfRange(bodyStart, payloadLength))
    }
}
