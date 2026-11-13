package cn.alone.hamitv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import cn.alone.hamitv.system.AdbKeeper

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
        AdbKeeper.start(this)
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
        // The security centre may clear adb_enabled later than our first screen.
        AdbKeeper.ensure(this)
        if (AgentService.isRunning) return
        runCatching { AgentService.start(this) }
            .onFailure { Log.e(TAG, "cannot start the agent", it) }
    }

    companion object {
        private const val TAG = "HamitvApp"
    }
}
