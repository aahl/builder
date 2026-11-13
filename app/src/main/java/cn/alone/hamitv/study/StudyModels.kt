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

/** Everything that lives in `filesDir/study.json`. */
@Serializable
data class StudyFile(
    val rules: StudyRules = StudyRules(),
    val bank: List<Question> = emptyList(),
)
