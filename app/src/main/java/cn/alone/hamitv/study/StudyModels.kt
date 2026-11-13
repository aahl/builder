package cn.alone.hamitv.study

import kotlinx.serialization.Serializable

/**
 * One quiz item. The TV only renders text, so a question is a prompt plus two
 * or more options and the index of the right one; [note] is shown after the
 * answer and is optional.
 */
@Serializable
data class Question(
    val id: String = "",
    val q: String = "",
    val options: List<String> = emptyList(),
    val answer: Int = 0,
    val note: String = "",
) {
    fun isValid(): Boolean =
        id.isNotBlank() && q.isNotBlank() && options.size >= 2 && answer in options.indices
}

/**
 * What the parent can change. [dailyCapMin] bounds both watching time and the
 * time that can be earned in a day, so the two can never drift apart.
 */
@Serializable
data class StudyRules(
    val enabled: Boolean = false,
    val dailyCapMin: Int = 60,
    val unlockMin: Int = 10,
    val groupSize: Int = 5,
    val passPercent: Int = 60,
    val exempt: List<String> = emptyList(),
) {
    fun sanitized(): StudyRules = copy(
        dailyCapMin = dailyCapMin.coerceIn(1, 24 * 60),
        unlockMin = unlockMin.coerceIn(1, 240),
        groupSize = groupSize.coerceIn(1, 20),
        passPercent = passPercent.coerceIn(0, 100),
        exempt = exempt.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(30),
    )
}

/**
 * The time account. [balanceSec] is watching time already earned; [usedTodaySec]
 * is what the screen has actually been on today, which is what the daily cap is
 * checked against. Both day counters reset when [day] no longer matches the
 * local date, while the balance carries over like a wallet.
 */
@Serializable
data class StudyState(
    val balanceSec: Long = 0,
    val day: Int = 0,
    val usedTodaySec: Long = 0,
    val earnedTodaySec: Long = 0,
    val paused: Boolean = false,
    val lastTickMs: Long = 0,
)

/**
 * Reading the question out loud. [voice], [rate] and [pitch] use the same
 * strings edge-tts puts into the SSML, so the console can show exactly what is
 * sent: "+0%", "-15%", "+0Hz".
 */
@Serializable
data class StudyVoice(
    val enabled: Boolean = false,
    val voice: String = StudyVoices.DEFAULT,
    val rate: String = "+0%",
    val pitch: String = "+0Hz",
) {
    fun sanitized(): StudyVoice = copy(
        voice = if (StudyVoices.isKnown(voice)) voice else StudyVoices.DEFAULT,
        rate = rate.takeIf { RATE.matches(it) } ?: "+0%",
        pitch = pitch.takeIf { PITCH.matches(it) } ?: "+0Hz",
    )

    private companion object {
        val RATE = Regex("""^[+-]\d{1,3}%$""")
        val PITCH = Regex("""^[+-]\d{1,3}Hz$""")
    }
}

/** The voices the console offers. Unknown names fall back to [DEFAULT]. */
object StudyVoices {

    data class Option(val id: String, val label: String)

    const val DEFAULT = "zh-CN-XiaoxiaoNeural"

    val all: List<Option> = listOf(
        Option("zh-CN-XiaoxiaoNeural", "晓晓 · 普通话女声"),
        Option("zh-CN-XiaoyiNeural", "晓伊 · 普通话女声"),
        Option("zh-CN-YunxiNeural", "云希 · 普通话男声"),
        Option("zh-CN-YunjianNeural", "云健 · 普通话男声"),
        Option("zh-CN-liaoning-XiaobeiNeural", "晓北 · 东北话"),
        Option("zh-TW-HsiaoChenNeural", "曉臻 · 台湾国语"),
        Option("en-US-AriaNeural", "Aria · English"),
    )

    fun isKnown(id: String): Boolean = all.any { it.id == id }

    fun langOf(id: String): String = id.split("-").take(2).joinToString("-").ifEmpty { "zh-CN" }
}

/** Everything that lives in `filesDir/study.json`. */
@Serializable
data class StudyFile(
    val rules: StudyRules = StudyRules(),
    val voice: StudyVoice = StudyVoice(),
    val bank: List<Question> = emptyList(),
)
