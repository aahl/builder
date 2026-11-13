package cn.alone.hamitv.study

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * The whole time-keeping policy, kept free of Android so it can be unit tested
 * on the JVM. Callers feed it the current foreground package and get the next
 * state back; nothing here starts a service or touches storage.
 */
object StudyLedger {

    /** A single tick never debits more than this, so a process that was frozen overnight cannot empty the balance. */
    const val MAX_TICK_SEC = 60L

    /** Local date as yyyyMMdd, the value stored in [StudyState.day]. */
    fun today(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }

    /**
     * Advance the account by the time since the last tick. [active] is true only
     * when the screen is on and the frontmost app is something the parent did
     * not exempt; answering the quiz never costs time.
     */
    fun step(state: StudyState, nowMs: Long, active: Boolean, rules: StudyRules): StudyState {
        if (!rules.enabled) return state.copy(lastTickMs = nowMs)
        var next = state
        val day = today(nowMs)
        if (day != next.day) next = next.copy(day = day, usedTodaySec = 0, earnedTodaySec = 0)
        if (!active || next.paused) return next.copy(lastTickMs = nowMs)
        // First tick after a start, or a clock that moved backwards: start counting from now.
        if (next.lastTickMs <= 0 || nowMs <= next.lastTickMs) return next.copy(lastTickMs = nowMs)
        val elapsedMs = (nowMs - next.lastTickMs).coerceAtMost(MAX_TICK_SEC * 1000)
        val debitSec = elapsedMs / 1000
        if (debitSec <= 0) return next.copy(lastTickMs = next.lastTickMs + elapsedMs)
        return next.copy(
            balanceSec = (next.balanceSec - debitSec).coerceAtLeast(0),
            usedTodaySec = next.usedTodaySec + debitSec,
            // Keep the sub-second remainder so 15 s ticks do not slowly lose time.
            lastTickMs = next.lastTickMs + debitSec * 1000,
        )
    }

    /** Locked means the quiz must be up before the child can keep watching. */
    fun isLocked(state: StudyState, rules: StudyRules): Boolean =
        rules.enabled && !state.paused &&
            (state.balanceSec <= 0 || state.usedTodaySec >= rules.dailyCapMin * 60L)

    fun shouldGate(state: StudyState, rules: StudyRules, pkg: String?, exempt: Set<String>): Boolean =
        isLocked(state, rules) && countable(pkg, exempt)

    /** Time left that can still be earned today; the daily cap is shared by watching and unlocking. */
    fun earnableSec(state: StudyState, rules: StudyRules): Long =
        (rules.dailyCapMin * 60L - state.earnedTodaySec).coerceAtLeast(0)

    /**
     * Add time after a passed group. Trims to whatever is left of today's cap, so
     * a child cannot bank hours in one afternoon.
     */
    fun grant(state: StudyState, minutes: Int, rules: StudyRules, nowMs: Long): StudyState {
        val day = today(nowMs)
        var next = state
        if (day != next.day) next = next.copy(day = day, usedTodaySec = 0, earnedTodaySec = 0)
        if (minutes <= 0) return next
        val add = (minutes.toLong() * 60).coerceAtMost(earnableSec(next, rules))
        if (add <= 0) return next
        return next.copy(balanceSec = next.balanceSec + add, earnedTodaySec = next.earnedTodaySec + add)
    }

    fun setPaused(state: StudyState, paused: Boolean, nowMs: Long): StudyState =
        state.copy(paused = paused, lastTickMs = if (paused) nowMs else 0)

    fun countable(pkg: String?, exempt: Set<String>): Boolean =
        !pkg.isNullOrBlank() && pkg !in exempt

    /** A fresh group, in random order, without repeating an item inside the group. */
    fun pick(bank: List<Question>, size: Int, random: Random = Random.Default): List<Question> {
        val usable = bank.filter { it.isValid() }
        if (usable.isEmpty()) return emptyList()
        return usable.shuffled(random).take(size.coerceIn(1, usable.size))
    }
}
