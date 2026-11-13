package cn.alone.hamitv.study

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Minimal client for the voice Microsoft Edge uses for read-aloud.
 *
 * It is a websocket at `speech.platform.bing.com`; the handshake needs a DRM
 * token (see [MsGec]) and a client version that moves with it. After that the
 * server expects a `speech.config` frame and an SSML frame, and answers with
 * binary frames that start with a two-byte header length, a text header and
 * then MP3 bytes.
 *
 * No API key and no account: this is the same unauthenticated endpoint the
 * browser extension uses.
 */
object EdgeTts {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val ENDPOINT =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val TIMEOUT_MS = 20_000L

    /**
     * The service validates the client version and the DRM token together: an
     * old version string is rejected with 403 before the websocket opens. These
     * two move together, the way edge-tts bumps them.
     */
    private const val CHROMIUM_MAJOR = "143"
    private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"

    private val client by lazy {
        HttpClient(CIO) {
            install(WebSockets)
        }
    }

    /**
     * Synthesise [text] to MP3. Throws when the service is unreachable or
     * answers without audio; callers treat that as "no sound this time".
     */
    suspend fun synthesize(text: String, voice: StudyVoice): ByteArray = withContext(Dispatchers.IO) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return@withContext ByteArray(0)
        withTimeout(TIMEOUT_MS) { request(spoken, voice) }
    }

    private suspend fun request(text: String, voice: StudyVoice): ByteArray {
        val stamp = timestamp()
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url = buildString {
            append(ENDPOINT)
            append("?TrustedClientToken=").append(TRUSTED_CLIENT_TOKEN)
            append("&ConnectionId=").append(connectionId)
            append("&Sec-MS-GEC=").append(MsGec.token())
            append("&Sec-MS-GEC-Version=").append(SEC_MS_GEC_VERSION)
        }
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        val audio = ByteArrayOutputStream()
        client.webSocket({
            url(url)
            header("Pragma", "no-cache")
            header("Cache-Control", "no-cache")
            header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            header("Cookie", "muid=$muid;")
            header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 " +
                    "Edg/$CHROMIUM_MAJOR.0.0.0",
            )
            header("Accept-Language", "en-US,en;q=0.9")
        }) {
            send(Frame.Text(configMessage(stamp)))
            send(Frame.Text(ssmlMessage(requestId, stamp, text, voice)))
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> readAudio(frame.readBytes(), audio)
                    is Frame.Text -> if (frame.readText().contains("Path:turn.end")) break
                    else -> Unit
                }
            }
        }
        if (audio.size() == 0) throw IOException("edge-tts 没有返回音频")
        return audio.toByteArray()
    }

    /** A binary frame is [2-byte header length][header][payload]; only audio frames carry MP3. */
    private fun readAudio(data: ByteArray, out: ByteArrayOutputStream) {
        if (data.size < 2) return
        val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val start = 2 + headerLength
        if (data.size <= start) return
        val header = String(data, 2, headerLength, Charsets.UTF_8)
        if (header.contains("Path:audio")) out.write(data, start, data.size - start)
    }

    private fun configMessage(stamp: String): String =
        "X-Timestamp:$stamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$OUTPUT_FORMAT"}}}}"""

    private fun ssmlMessage(requestId: String, stamp: String, text: String, voice: StudyVoice): String {
        val lang = StudyVoices.langOf(voice.voice)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='${voice.voice}'><prosody pitch='${voice.pitch}' rate='${voice.rate}' volume='+0%'>" +
            "${escape(text)}</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:$stamp\r\n" +
            "Path:ssml\r\n\r\n$ssml"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;")

    private fun timestamp(): String =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
