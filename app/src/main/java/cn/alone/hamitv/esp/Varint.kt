package cn.alone.hamitv.esp

import java.io.EOFException
import java.io.InputStream

/**
 * Protobuf-style base-128 varint, as used by the ESPHome native API.
 */
object Varint {

    fun size(value: Int): Int {
        var v = value
        var n = 1
        while (v and 0x7F.inv() != 0) {
            n++
            v = v ushr 7
        }
        return n
    }

    fun write(out: java.io.OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    fun read(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = input.read()
            if (b < 0) throw EOFException("eof while reading varint")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalStateException("varint longer than 32 bits")
    }

    /** Decodes a varint from [buf] at [offset]; returns value and the offset just past it. */
    fun decode(buf: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = offset
        while (shift < 32) {
            if (i >= buf.size) throw EOFException("truncated varint")
            val b = buf[i].toInt() and 0xFF
            i++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        throw IllegalStateException("varint longer than 32 bits")
    }
}
