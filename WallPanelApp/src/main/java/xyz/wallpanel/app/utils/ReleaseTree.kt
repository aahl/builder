package xyz.wallpanel.app.utils

import android.util.Log
import timber.log.Timber

/**
 * Lightweight release logging tree.
 *
 * Replaces the former Crashlytics tree so the app builds and runs on devices
 * without Google Play Services (e.g. the Viomi fridge panel). Warnings and
 * errors are written to Logcat instead of being reported to Firebase.
 */
class ReleaseTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        if (priority < Log.WARN) {
            return
        }
        Log.println(priority, tag ?: "WallPanel", message)
        throwable?.printStackTrace()
    }
}
