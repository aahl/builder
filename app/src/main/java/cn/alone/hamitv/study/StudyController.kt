package cn.alone.hamitv.study

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Shared entry point for everything that touches the study account: the web
 * console, ESPHome entities and (later) the accessibility watcher. Reads are
 * lock-free snapshots, writes are synchronized and persisted immediately.
 */
class StudyController(
    context: Context,
    private val store: StudyStore = StudyStore(context),
) {

    private val app: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var lastSaveAt = 0L
    private var lastLaunchAt = 0L
    private var lastPublished = ""

    /** Set by the service so Home Assistant sees changes without polling. */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

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

    /** Called by the agent service once its foreground notification is up. */
    fun start() {
        if (running) return
        running = true
        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        store.saveState(state)
    }

    /** A window change happened; re-check right away instead of waiting for the next tick. */
    fun onForeground(pkg: String) {
        if (!running) return
        tick()
    }

    @Synchronized
    private fun tick() {
        val now = System.currentTimeMillis()
        val screenOn = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        val pkg = StudyWatcherService.foregroundPackage
        val active = screenOn && !StudyActivity.visible &&
            StudyLedger.countable(pkg, exemptPackages())
        val before = state
        val next = StudyLedger.step(before, now, active, rules)
        state = next
        val lockFlipped = StudyLedger.isLocked(before, rules) != StudyLedger.isLocked(next, rules)
        if (lockFlipped || now - lastSaveAt >= SAVE_INTERVAL_MS) {
            store.saveState(next)
            lastSaveAt = now
        }
        publishIfChanged()
        maybeGate(pkg, now)
    }

    /** Push to HA only when something HA can see actually changed. */
    private fun publishIfChanged() {
        val key = "${state.balanceSec / 60}|${state.paused}|${StudyLedger.isLocked(state, rules)}"
        if (key == lastPublished) return
        lastPublished = key
        onStateChanged?.invoke()
    }

    private fun maybeGate(pkg: String?, now: Long) {
        if (StudyActivity.visible) return
        if (now - lastLaunchAt < LAUNCH_GAP_MS) return
        if (!StudyLedger.shouldGate(state, rules, pkg, exemptPackages())) return
        if (!StudyWatcherService.connected) {
            Log.w(TAG, "quiz due but the watcher is not connected")
            return
        }
        if (StudyWatcherService.launch(app, "auto")) lastLaunchAt = now
    }

    /**
     * Apps that never cost time and never get the quiz: ourselves, the system UI
     * the remote's volume bar lives in, the settings app, and the launcher. HOME
     * therefore still works — this gate keeps a child from watching, not from
     * leaving.
     */
    fun exemptPackages(): Set<String> = buildSet {
        add(app.packageName)
        add("android")
        add("com.android.systemui")
        add("com.android.settings")
        add("com.android.tv.settings")
        add("com.gathin.tvhome")
        addAll(rules.exempt)
    }

    @Synchronized
    fun setRules(updated: StudyRules): StudyRules {
        val wasEnabled = data.rules.enabled
        data = data.copy(rules = updated.sanitized())
        // Turning the gate on starts a fresh clock; the gap while it was off is not charged.
        if (!wasEnabled && data.rules.enabled) state = state.copy(lastTickMs = 0)
        store.save(data)
        publishIfChanged()
        return data.rules
    }

    @Synchronized
    fun setPaused(paused: Boolean): StudyState {
        state = StudyLedger.setPaused(state, paused, System.currentTimeMillis())
        store.saveState(state)
        publishIfChanged()
        return state
    }

    @Synchronized
    fun unlock(minutes: Int = rules.unlockMin): StudyState {
        state = StudyLedger.grant(state, minutes, rules, System.currentTimeMillis())
        store.saveState(state)
        publishIfChanged()
        return state
    }

    /**
     * Close a finished group: award time when the ratio clears the bar, otherwise
     * leave the account alone so the child can simply try another group.
     */
    @Synchronized
    fun recordGroup(correct: Int, total: Int): Boolean {
        if (total <= 0) return false
        if (correct * 100 / total < rules.passPercent) return false
        state = StudyLedger.grant(state, rules.unlockMin, rules, System.currentTimeMillis())
        store.saveState(state)
        publishIfChanged()
        return true
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
            "foreground" to (StudyWatcherService.foregroundPackage ?: "-"),
            "quizVisible" to StudyActivity.visible.toString(),
            "running" to running.toString(),
        )
    }

    /** Day counters rolled forward for display without writing them back. */
    private fun roll(nowMs: Long): StudyState {
        val day = StudyLedger.today(nowMs)
        val current = state
        return if (day == current.day) current
        else current.copy(day = day, usedTodaySec = 0, earnedTodaySec = 0)
    }

    private companion object {
        const val TAG = "StudyController"
        const val TICK_MS = 15_000L
        const val SAVE_INTERVAL_MS = 30_000L
        const val LAUNCH_GAP_MS = 10_000L
    }
}
