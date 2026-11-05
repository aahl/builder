package ng.anlo.hamitv.esp

import java.io.OutputStream

/**
 * Transport protection for API frames. Two implementations are planned:
 * [PlaintextFrameHelper] for development and a Noise (NNpsk0) variant for
 * production, mirroring ESPHome's own split between the plaintext and noise
 * frame helpers.
 */
interface FrameHelper {
    fun send(typeId: Int, body: ByteArray)
    fun close()
}

class PlaintextFrameHelper(private val out: OutputStream) : FrameHelper {

    override fun send(typeId: Int, body: ByteArray) {
        EspFrame.write(out, typeId, body)
    }

    override fun close() {
        runCatching { out.close() }
    }
}
