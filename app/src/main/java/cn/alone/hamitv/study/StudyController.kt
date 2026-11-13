package cn.alone.hamitv.study

import android.content.Context

/**
 * Shared entry point for everything that touches the study account: the web
 * console, ESPHome entities and (later) the accessibility watcher. Reads are
 * lock-free snapshots, writes are synchronized and persisted immediately.
 */
class StudyController(
    context: Context,
    private val store: StudyStore = StudyStore(context),
) {

    @Volatile
    private var data: StudyFile = store.load()

    @Volatile
    private var state: StudyState = store.loadState()

    val rules: StudyRules get() = data.rules

    val bank: List<Question> get() = data.bank

    val bankSize: Int get() = data.bank.size

    fun state(): StudyState = state

    /** State with the day counters rolled forward, for display without writing them back. */
    fun snapshot(nowMs: Long = System.currentTimeMillis()): StudyState = roll(nowMs)

    fun isLocked(nowMs: Long = System.currentTimeMillis()): Boolean =
        StudyLedger.isLocked(roll(nowMs), rules)

    fun balanceMinutes(nowMs: Long = System.currentTimeMillis()): Int =
        (roll(nowMs).balanceSec / 60).toInt()

    fun pickGroup(size: Int = rules.groupSize): List<Question> = StudyLedger.pick(data.bank, size)

    @Synchronized
    fun setRules(updated: StudyRules): StudyRules {
        data = data.copy(rules = updated.sanitized())
        store.save(data)
        return data.rules
    }

    @Synchronized
    fun setPaused(paused: Boolean): StudyState {
        state = StudyLedger.setPaused(state, paused, System.currentTimeMillis())
        store.saveState(state)
        return state
    }

    @Synchronized
    fun unlock(minutes: Int = rules.unlockMin): StudyState {
        state = StudyLedger.grant(state, minutes, rules, System.currentTimeMillis())
        store.saveState(state)
        return state
    }

    @Synchronized
    fun replaceBank(items: List<Question>): Int {
        data = data.copy(bank = items.filter { it.isValid() }.distinctBy { it.id })
        store.save(data)
        return data.bank.size
    }

    @Synchronized
    fun addQuestion(question: Question): Boolean {
        if (!question.isValid()) return false
        data = data.copy(bank = data.bank.filterNot { it.id == question.id } + question)
        store.save(data)
        return true
    }

    @Synchronized
    fun deleteQuestion(id: String): Boolean {
        val next = data.bank.filterNot { it.id == id }
        if (next.size == data.bank.size) return false
        data = data.copy(bank = next)
        store.save(data)
        return true
    }

    /**
     * Enough to tell "switched off" from "switched on but blind": the console
     * and Home Assistant both show these so a broken watcher is visible instead
     * of looking like a well-behaved child.
     */
    fun diagnostics(nowMs: Long = System.currentTimeMillis()): Map<String, String> {
        val current = roll(nowMs)
        return mapOf(
            "enabled" to rules.enabled.toString(),
            "paused" to current.paused.toString(),
            "locked" to StudyLedger.isLocked(current, rules).toString(),
            "bank" to data.bank.size.toString(),
            "lastTickAgeSeconds" to if (current.lastTickMs <= 0) "-1" else ((nowMs - current.lastTickMs) / 1000).toString(),
        )
    }

    /** Day counters rolled forward for display without writing them back. */
    private fun roll(nowMs: Long): StudyState {
        val day = StudyLedger.today(nowMs)
        val current = state
        return if (day == current.day) current
        else current.copy(day = day, usedTodaySec = 0, earnedTodaySec = 0)
    }
}
