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
 * Foreground detection for when the accessibility service is not bound.
 *
 * Usage stats need `appops set <pkg> GET_USAGE_STATS allow` once. The sample is
 * taken on a background thread and cached, because the ticker runs on the main
 * looper and querying every 15 s there would show up as jank on a TV.
 *
 * This only restores the *charging* half. Without the accessibility service the
 * quiz cannot be opened from the background, so the gate fails open: the balance
 * still drains, nothing interrupts the child.
 */
class StudyUsage(private val context: Context) {

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var querying = false

    private var lastQueryAt = 0L

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
                val pkg = query()
                querying = false
                if (pkg != null) {
                    cached = pkg
                    cachedAt = SystemClock.elapsedRealtime()
                }
            }
        }
        return cached?.takeIf { SystemClock.elapsedRealtime() - cachedAt <= STALE_MS }
    }

    private fun query(): String? = runCatching {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - EVENT_WINDOW_MS, now)
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
        // No event in the window (fresh boot, idle set): fall back to the most
        // recently used app that was used recently enough to still be plausible.
        manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now)
            ?.filter { it.lastTimeUsed > 0 && now - it.lastTimeUsed <= STALE_MS }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }.getOrNull()

    private companion object {
        const val EVENT_WINDOW_MS = 15 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val QUERY_MS = 15_000L
        const val STALE_MS = 10 * 60 * 1000L
    }
}
