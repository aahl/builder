package cn.alone.hamitv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the agent back after a reboot, and after an update replaces the APK:
 * `adb install -r` kills the process, and without this the TV would stay dead
 * until someone opened the app by hand.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> runCatching { AgentService.start(context) }
        }
    }
}
