package cn.alone.hamitv.study

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    private val usage = StudyUsage(app)

    private var running = false
    private var lastSaveAt = 0L
    private var lastLaunchAt = 0L
    private var lastPublished = ""
    private var lastRearmAt = 0L
    private var screenReceiverRegistered = false

    /**
     * Xiaomi clears the accessibility grant in standby, and ADB with it. There is
     * no callback for "the grant was revoked", so look again a few seconds after
     * the screen lights.
     */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.postDelayed({
                if (running && rules.enabled && !StudyWatcherService.connected) {
                    Log.i(TAG, "screen on and the watcher is gone; re-arming")
                    StudyWatcherService.rearmAsync(app)
                }
            }, SCREEN_ON_GRACE_MS)
        }
    }

    /** True from the moment a quiz is shown until it is passed or abandoned. */
    @Volatile
    private var quizSession = false
    private var lastRelaunchAt = 0L
    private var relaunchBudgetStart = 0L
    private var relaunchCount = 0

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

    /** What the last finished group did, for the console and Home Assistant. */
    @Volatile
    var lastResult: String = store.loadLastResult()
        private set

    val rules: StudyRules get() = data.rules

    val voice: StudyVoice get() = data.voice

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
        registerScreenReceiver()
        // Firmwares drop the accessibility binding after an update while the
        // setting still lists us; re-arming re-creates the binding.
        handler.postDelayed({
            if (rules.enabled && !StudyWatcherService.connected) StudyWatcherService.rearmAsync(app)
        }, START_GRACE_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        unregisterScreenReceiver()
        store.saveState(state)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(screenOn, filter)
            }
            screenReceiverRegistered = true
        }.onFailure { Log.w(TAG, "cannot watch the screen", it) }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { app.unregisterReceiver(screenOn) }
        screenReceiverRegistered = false
    }

    /** A window change happened; re-check right away instead of waiting for the next tick. */
    fun onForeground(pkg: String) {
        if (!running) return
        // While a quiz is open, leaving it brings it straight back. The check is
        // "the page is no longer visible", not "another package is in front":
        // HOME lands on our own launcher, so the package name would be ours.
        if (quizSession && !StudyActivity.visible) scheduleRelaunch("left")
        tick()
    }

    fun onQuizShown() {
        quizSession = true
        relaunchCount = 0
        relaunchBudgetStart = 0
    }

    /** Passed or abandoned: stop holding the screen. */
    fun onQuizFinished() {
        quizSession = false
    }

    /** The page went to the background without finishing; this is usually HOME. */
    fun onQuizPaused() = scheduleRelaunch("paused")

    /**
     * Bring the quiz back after the child left it. Runs through the accessibility
     * service because a background activity start from this service would be
     * refused. The budget keeps a set that rejects the launch from spinning.
     */
    @Synchronized
    private fun scheduleRelaunch(reason: String) {
        if (!quizSession) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRelaunchAt < RELAUNCH_GAP_MS) return
        val screenOn = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        if (!screenOn) return
        if (now - relaunchBudgetStart > RELAUNCH_WINDOW_MS) {
            relaunchBudgetStart = now
            relaunchCount = 0
        }
        if (relaunchCount >= RELAUNCH_BUDGET) {
            Log.w(TAG, "quiz relaunch budget spent, waiting a minute")
            return
        }
        lastRelaunchAt = now
        relaunchCount++
        Log.i(TAG, "quiz left ($reason), bringing it back")
        handler.postDelayed({
            if (quizSession && !StudyActivity.visible) {
                StudyWatcherService.launch(app, "relaunch:$reason")
            }
        }, RELAUNCH_DELAY_MS)
    }

    @Synchronized
    private fun tick() {
        val now = System.currentTimeMillis()
        val screenOn = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        val pkg = StudyWatcherService.foregroundPackage ?: usage.current()
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
        guardWatcher()
        maybeGate(pkg, now)
    }

    /**
     * A watcher that is bound but keeps reporting an app usage stats disagrees
     * with is deaf: the system still lists it, yet no window events arrive.
     */
    private fun guardWatcher() {
        if (!rules.enabled || !StudyWatcherService.connected) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRearmAt < REARM_COOLDOWN_MS) return
        val watcherPkg = StudyWatcherService.foregroundPackage ?: return
        val usagePkg = usage.current() ?: return
        if (usagePkg == watcherPkg) return
        if (now - StudyWatcherService.lastEventAt < EVENT_STALE_MS) return
        lastRearmAt = now
        Log.i(TAG, "watcher looks deaf ($watcherPkg vs usage $usagePkg); re-arming")
        StudyWatcherService.rearmAsync(app)
    }

    /** Push to HA only when something HA can see actually changed. */
    private fun publishIfChanged() {
        val key = "${state.balanceSec / 60}|${state.usedTodaySec / 60}|${state.earnedTodaySec / 60}|" +
            "${state.paused}|${StudyLedger.isLocked(state, rules)}|${rules.enabled}|$lastResult"
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
        // Turning it off must not leave a quiz on screen.
        if (wasEnabled && !data.rules.enabled) StudyActivity.closeIfOpen()
        store.save(data)
        publishIfChanged()
        return data.rules
    }

    @Synchronized
    fun setVoice(updated: StudyVoice): StudyVoice {
        data = data.copy(voice = updated.sanitized())
        store.save(data)
        return data.voice
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
        val passed = correct * 100 / total >= rules.passPercent
        if (passed) {
            state = StudyLedger.grant(state, rules.unlockMin, rules, System.currentTimeMillis())
            store.saveState(state)
        }
        lastResult = if (passed) "通过 $correct/$total，+${rules.unlockMin} 分钟" else "未通过 $correct/$total"
        store.saveLastResult(lastResult)
        publishIfChanged()
        return passed
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
            "foreground" to (StudyWatcherService.foregroundPackage ?: usage.current() ?: "-"),
            "usageAccess" to usage.hasAccess().toString(),
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
        const val RELAUNCH_GAP_MS = 900L
        const val RELAUNCH_DELAY_MS = 350L
        const val RELAUNCH_WINDOW_MS = 60_000L
        const val RELAUNCH_BUDGET = 12
        const val START_GRACE_MS = 8_000L
        const val SCREEN_ON_GRACE_MS = 4_000L
        const val REARM_COOLDOWN_MS = 10 * 60_000L
        const val EVENT_STALE_MS = 120_000L
    }
}
