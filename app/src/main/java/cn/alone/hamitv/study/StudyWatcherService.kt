package cn.alone.hamitv.study

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

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

        /** Bring the quiz page up. Returns false when the service is not connected. */
        fun launch(context: Context, reason: String): Boolean {
            val service = instance ?: return false
            val intent = Intent(service, StudyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
