package cn.alone.hamitv.study

import android.service.notification.NotificationListenerService
import android.util.Log
import cn.alone.hamitv.AgentService

/**
 * Notification access is the one grant the system keeps across a force stop and
 * re-binds on its own, so it doubles as a revival channel: when the listener
 * connects, start the agent again and re-arm the accessibility service. It reads
 * no notification content.
 *
 * One-time grant:
 * `adb shell cmd notification allow_listener com.ts.launcher/cn.alone.hamitv.study.StudyRevivalService`
 */
class StudyRevivalService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected; reviving the agent")
        runCatching { AgentService.start(this) }
            .onFailure { Log.w(TAG, "cannot start the agent from the listener", it) }
        StudyWatcherService.rearmAsync(this)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
    }

    private companion object {
        const val TAG = "StudyRevival"
    }
}
