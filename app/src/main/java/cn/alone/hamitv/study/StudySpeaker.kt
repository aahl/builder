package cn.alone.hamitv.study

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the question out loud. Synthesis is network bound and may fail on a TV
 * with no route to the service; in that case the quiz stays usable and only
 * [lastError] changes.
 */
class StudySpeaker private constructor(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var player: MediaPlayer? = null
    private var job: Job? = null
    private var focusHeld = false

    @Volatile
    var lastError: String? = null
        private set

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener { change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
            }
            .build()
    }

    /**
     * Speak [text]; [onFinished] runs when playback ends, or straight away when
     * there is nothing to say. Cancellation (a new question, leaving the page)
     * does not call it, so callers do not advance behind the child's back.
     */
    fun speak(text: String, voice: StudyVoice, onFinished: (() -> Unit)? = null) {
        if (!voice.enabled || text.isBlank()) {
            onFinished?.invoke()
            return
        }
        stop()
        job = scope.launch {
            val bytes = try {
                EdgeTts.synthesize(text, voice)
            } catch (e: CancellationException) {
                // A new question (or leaving the page) cancelled this one.
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "cannot read the question", e)
                onFinished?.invoke()
                return@launch
            }
            lastError = null
            play(bytes, onFinished)
        }
    }

    /** Play audio that is already synthesised; the console's test button uses it. */
    fun playEncoded(bytes: ByteArray) {
        stop()
        scope.launch { play(bytes) }
    }

    fun stop() {
        job?.cancel()
        job = null
        release()
    }

    private suspend fun play(bytes: ByteArray, onFinished: (() -> Unit)? = null) {
        if (bytes.isEmpty()) {
            onFinished?.invoke()
            return
        }
        val file = File(app.cacheDir, "study_read.mp3")
        withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }

        release()
        if (!requestFocus()) Log.w(TAG, "playing without audio focus")
        val mp = MediaPlayer()
        mp.setAudioAttributes(attributes())
        mp.setOnCompletionListener {
            release()
            onFinished?.invoke()
        }
        mp.setOnErrorListener { _, what, extra ->
            lastError = "播放失败 $what/$extra"
            release()
            onFinished?.invoke()
            true
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "cannot play the audio", e)
            runCatching { mp.release() }
            release()
            onFinished?.invoke()
        }
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun release() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        if (focusHeld) {
            runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            focusHeld = false
        }
    }

    companion object {
        private const val TAG = "StudySpeaker"

        @Volatile
        private var instance: StudySpeaker? = null

        fun get(context: Context): StudySpeaker = instance ?: synchronized(this) {
            instance ?: StudySpeaker(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Turns a question into something a TTS voice reads naturally: "56 - 28 = ?"
 * becomes "56 减 28 等于", and the options are announced with the same letters
 * the screen shows. The position ("第 1 题，共 5 题") is on screen only; reading
 * it just delays the question.
 */
object StudySpeech {

    fun text(question: Question): String {
        val options = question.options.mapIndexed { i, option ->
            "${('A' + i)}，$option"
        }.joinToString("；")
        return "${normalize(question.q)}。选项：$options。"
    }

    /** A wrong answer: say so first, then the question, then the right option. */
    fun wrongAnswer(question: Question): String {
        val answer = question.options.getOrNull(question.answer).orEmpty()
        return "答错了。${normalize(question.q)}。正确答案是 $answer。"
    }

    private fun normalize(prompt: String): String = prompt
        .replace(Regex("""(?<=\d)\s*[-−]\s*(?=\d)"""), " 减 ")
        .replace(Regex("""(?<=\d)\s*\+\s*(?=\d)"""), " 加 ")
        .replace(Regex("""(?<=\d)\s*[×*]\s*(?=\d)"""), " 乘 ")
        .replace(Regex("""(?<=\d)\s*[÷/]\s*(?=\d)"""), " 除以 ")
        .replace(Regex("""\s*=\s*"""), " 等于 ")
        .trim()
}
