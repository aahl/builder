package cn.alone.hamitv

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import cn.alone.hamitv.config.ConfigStore

/**
 * Starts the agent as soon as one of our screens is visible.
 *
 * The home screen is now ours (see the :launcher module), so being resumed *is*
 * the boot signal: the system starts that activity even for a package the
 * vendor security centre left in the stopped state, where `BOOT_COMPLETED`
 * would never arrive.
 *
 * `Application.onCreate` is deliberately not used: with targetSdk 34 a cold
 * start still counts as a background start at that point, and API 31+ throws
 * `ForegroundServiceStartNotAllowedException` for `startForegroundService`.
 * The first `onActivityResumed` is guaranteed to be a foreground one.
 */
class HamitvApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ensureAgent()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Idempotent: the service ignores a second start while it is already up. */
    private fun ensureAgent() {
        if (AgentService.isRunning) return
        runCatching { AgentService.start(this) }
            .onFailure { Log.e(TAG, "cannot start the agent", it) }
        applyKeepAdb(this)
    }

    companion object {
        private const val TAG = "HamitvApp"

        /**
         * Xiaomi's security centre closes network ADB while it scans packages at
         * boot, which is what makes `adb` die about a minute after power-on. The
         * flag behind the Settings toggle is the ordinary `adb_enabled`, but
         * writing it needs WRITE_SECURE_SETTINGS, and a sideloaded app only gets
         * that from `adb shell pm grant com.ts.launcher <permission>`. Silent
         * when the switch is off or the grant never happened.
         */
        fun applyKeepAdb(context: Context) {
            val store = ConfigStore(context)
            if (!store.keepAdb) return
            val granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "keep_adb is on but WRITE_SECURE_SETTINGS was never granted")
                return
            }
            runCatching { Settings.Global.putInt(context.contentResolver, "adb_enabled", 1) }
                .onSuccess { Log.i(TAG, "re-enabled adb after boot") }
                .onFailure { Log.w(TAG, "cannot re-enable adb", it) }
        }
    }
}
