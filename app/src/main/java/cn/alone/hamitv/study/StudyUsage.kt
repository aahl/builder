package cn.alone.hamitv.study

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * The real foreground app, from usage stats.
 *
 * The accessibility stream cannot answer this: a window that is no longer in
 * front still fires state changes, and on this TV the video app keeps doing so
 * while the child sits on the launcher. Taking "last window event" as the
 * foreground then charges time on the desktop and pops the quiz there.
 *
 * Usage stats need `appops set <pkg> GET_USAGE_STATS allow` once. Queries run on
 * a background thread and only cover the window since the previous check, so a
 * tick every couple of seconds stays cheap. The accessibility package is still
 * used when usage stats are unavailable.
 */
class StudyUsage(private val context: Context) {

    @Volatile
    private var cached: String? = null

    @Volatile
    private var querying = false

    private var lastQueryAt = 0L

    /** Wall clock of the end of the last query window. */
    private var cursor = 0L

    /**
     * Called on a worker thread whenever the answer changes. The ticker runs every
     * 15 s; without this, switching to the launcher would leave the old app cached
     * long enough to charge it and pop the quiz on the desktop.
     */
    @Volatile
    var onChanged: ((String) -> Unit)? = null

    fun hasAccess(): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /** Last known foreground package, refreshed at most every [QUERY_MS]. */
    fun current(): String? {
        val now = SystemClock.elapsedRealtime()
        if (!querying && now - lastQueryAt >= QUERY_MS) {
            lastQueryAt = now
            querying = true
            thread(name = "study-usage", isDaemon = true) {
                try {
                    val pkg = query()
                    if (pkg != null && pkg != cached) {
                        cached = pkg
                        onChanged?.invoke(pkg)
                    }
                } finally {
                    querying = false
                }
            }
        }
        return cached
    }

    private fun query(): String? = runCatching {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Incremental: only the window since the last look. The first look after a
        // start covers hours, because the app may have been open the whole time
        // without a new foreground event.
        val from = if (cursor > 0) cursor - 2_000 else now - INITIAL_LOOKBACK_MS
        cursor = now
        val events = manager.queryEvents(from, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        var latestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = event.packageName ?: continue
            if (event.timeStamp >= latestAt) {
                latest = pkg
                latestAt = event.timeStamp
            }
        }
        if (latest != null) return@runCatching latest
        if (cached != null) return@runCatching null
        // Nothing in the window and nothing known yet (fresh boot, a set that logs
        // no events): fall back to the most recently used app, if still plausible.
        manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now)
            ?.filter { it.lastTimeUsed > 0 && now - it.lastTimeUsed <= STALE_MS }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }.getOrNull()

    private companion object {
        const val INITIAL_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val QUERY_MS = 2_000L
        const val STALE_MS = 10 * 60 * 1000L
    }
}
