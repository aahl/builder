package cn.alone.hamitv.study

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlin.concurrent.thread

/**
 * Watches which app is in front. It is the only component allowed to start the
 * quiz page from the background: an accessibility service is exempt from the
 * background activity start restriction that would silently swallow the same
 * intent from a foreground service.
 *
 * It reads window state changes only — never window content.
 */
class StudyWatcherService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foregroundPackage = pkg
        lastEventAt = SystemClock.elapsedRealtime()
        StudyRuntime.get(this).onForeground(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        instance = null
        connected = false
    }

    companion object {
        const val TAG = "StudyWatcher"
        private const val REARM_GAP_MS = 1_500L

        @Volatile
        var connected: Boolean = false
            private set

        /** Last package that took the foreground, or null before the first window event. */
        @Volatile
        var foregroundPackage: String? = null
            private set

        @Volatile
        var lastEventAt: Long = 0
            private set

        @Volatile
        private var instance: StudyWatcherService? = null

        /** True when the service is in the system's enabled list, even if it is not bound right now. */
        fun enabled(context: Context): Boolean = try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.name.endsWith(".StudyWatcherService") }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the accessibility list", e)
            false
        }

        /**
         * Two-phase re-arm, the trick Aerial uses: write ourselves out of the list,
         * wait, re-read it, then add ourselves back. Writing the same string back
         * does not change the value, so the system never rebinds — that is why the
         * first version of this failed. Re-reading keeps entries other owners added
         * in the meantime. Needs WRITE_SECURE_SETTINGS.
         */
        private val rearming = java.util.concurrent.atomic.AtomicBoolean(false)

        fun rearmAsync(context: Context) {
            val app = context.applicationContext
            if (!rearming.compareAndSet(false, true)) return
            thread(name = "study-rearm", isDaemon = true) {
                try {
                    // Unconditional: the problem case is "listed and bound on paper,
                    // yet no events arrive", where enabled() already says true.
                    rearm(app)
                } catch (e: Exception) {
                    Log.w(TAG, "cannot re-arm the watcher", e)
                } finally {
                    rearming.set(false)
                }
            }
        }

        /** Blocking; call on a worker thread. */
        fun rearm(context: Context): Boolean {
            val app = context.applicationContext
            val resolver = app.contentResolver
            val me = ComponentName(app, StudyWatcherService::class.java).flattenToString()
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = Settings.Secure.getString(resolver, key).orEmpty()
            val entries = current.split(':').filter { it.isNotBlank() }
            if (me in entries) {
                Settings.Secure.putString(resolver, key, entries.filter { it != me }.joinToString(":"))
                Thread.sleep(REARM_GAP_MS)
            }
            val fresh = Settings.Secure.getString(resolver, key).orEmpty()
            val next = fresh.split(':').filter { it.isNotBlank() }.toMutableList()
            if (me !in next) next.add(me)
            Settings.Secure.putString(resolver, key, next.joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Thread.sleep(800)
            val ok = enabled(app)
            Log.i(TAG, "re-armed the watcher: $ok")
            return ok
        }

        /** Bring the quiz page up. Returns false when the service is not connected. */
        fun launch(context: Context, reason: String): Boolean {
            val service = instance ?: return false
            val intent = Intent(service, StudyActivity::class.java)
                // No CLEAR_TOP: with singleInstance it can finish and recreate the
                // page, which resets the group the child is halfway through.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(StudyActivity.EXTRA_REASON, reason)
            return try {
                service.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "cannot open the quiz", e)
                false
            }
        }
    }
}
