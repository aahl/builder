package cn.alone.hamitv.esp

import cn.alone.hamitv.esp.noise.CipherState
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted framing, mirroring ESPHome's noise frame helper:
 *
 *     [0x01][payload length: 2 bytes big endian][payload]
 *
 * Handshake payloads start with a status byte. Transport payloads are the
 * ciphertext of `[type: 2 bytes][length: 2 bytes][protobuf]` followed by the
 * 16-byte Poly1305 tag. Note the inner header is fixed width here, unlike the
 * varint header used by plaintext frames.
 */
object NoiseFrame {

    const val INDICATOR = 0x01
    const val HEADER_SIZE = 3
    const val MAC_SIZE = 16
    const val MAX_PAYLOAD = 0xFFFF

    /** Handshake frames are read and written without encryption. */
    fun writeRaw(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val header = byteArrayOf(
            INDICATOR.toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
        )
        out.write(header)
        out.write(payload)
        out.flush()
    }

    fun readRaw(input: InputStream): ByteArray {
        val header = readFully(input, HEADER_SIZE)
        if (header[0].toInt() and 0xFF != INDICATOR) {
            throw IllegalStateException("bad frame indicator ${header[0].toInt() and 0xFF}")
        }
        val length = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
        return readFully(input, length)
    }

    fun encode(send: CipherState, typeId: Int, body: ByteArray): ByteArray {
        require(body.size <= MAX_PAYLOAD) { "body too large: ${body.size}" }
        val inner = ByteArray(4 + body.size)
        inner[0] = (typeId shr 8).toByte()
        inner[1] = typeId.toByte()
        inner[2] = (body.size shr 8).toByte()
        inner[3] = body.size.toByte()
        body.copyInto(inner, 4)
        val ciphertext = send.encrypt(inner, ByteArray(0))
        return byteArrayOf(
            INDICATOR.toByte(),
            (ciphertext.size shr 8).toByte(),
            ciphertext.size.toByte(),
        ) + ciphertext
    }

    fun write(out: OutputStream, send: CipherState, typeId: Int, body: ByteArray) {
        out.write(encode(send, typeId, body))
        out.flush()
    }

    /** Reads one encrypted frame and returns its message type and protobuf body. */
    fun read(input: InputStream, receive: CipherState): EspFrame.Frame {
        val ciphertext = readRaw(input)
        if (ciphertext.size < 4 + MAC_SIZE) {
            throw IllegalStateException("encrypted frame too short: ${ciphertext.size}")
        }
        val inner = receive.decrypt(ciphertext, ByteArray(0))
        if (inner.size < 4) throw IllegalStateException("decrypted frame too short: ${inner.size}")
        val typeId = ((inner[0].toInt() and 0xFF) shl 8) or (inner[1].toInt() and 0xFF)
        val length = ((inner[2].toInt() and 0xFF) shl 8) or (inner[3].toInt() and 0xFF)
        // Trust the real length, not the one the peer claims.
        val available = (inner.size - 4).coerceAtMost(length)
        return EspFrame.Frame(EspFrame.INDICATOR_ENCRYPTED, typeId, inner.copyOfRange(4, 4 + available))
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException("stream closed mid-frame")
            offset += read
        }
        return buffer
    }
}
