package cn.alone.hamitv.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cn.alone.hamitv.config.ConfigStore
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps network ADB on after boot when the owner opts in, the trimmed version of
 * Aerial's GuardSetup.
 *
 * Xiaomi's security centre switches adbd off while it scans packages at boot.
 * Writing `adb_enabled = 1` only helps while the flag is 0; when the flag already
 * says 1 but adbd stopped listening, nothing changes, and the flag has to go
 * 0 -> 1 to restart the daemon. So:
 *
 *  - watch `adb_enabled` and put it back when it is cleared;
 *  - within the first three minutes after boot, probe 127.0.0.1:5555 and, when
 *    the daemon is gone but the flag claims it is on, walk the flag 0 -> 1 once;
 *  - when ADB is switched off while the TV is being used, take it as the owner's
 *    choice and stop fighting: keep_adb is turned off in the console.
 *
 * Needs WRITE_SECURE_SETTINGS, which only `adb shell pm grant <package> ...`
 * can give a sideloaded app. Silent when the switch is off or the grant is missing.
 */
object AdbKeeper {
    private const val TAG = "HamitvAdb"
    private const val PORT = 5555
    private const val BOOT_WINDOW_MS = 3 * 60_000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-keeper").apply { isDaemon = true }
    }
    private val dancing = AtomicBoolean(false)
    private val danced = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    private var started = false

    private val adbObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val context = appContext ?: return
            worker.execute { settingChanged(context) }
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ensure(context)
        }
    }

    /** Called once from Application.onCreate: watch the setting from now on. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
            false,
            adbObserver,
        )
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(screenOn, filter)
        }
        // The security centre may kill adbd without touching the flag, so look again during the
        // boot window instead of trusting the one check at start-up.
        longArrayOf(45_000L, 90_000L, 150_000L).forEach { delay ->
            handler.postDelayed({ ensure(app) }, delay)
        }
    }

    /** Queues one check: on app start, on resume, on screen-on, and after the console switch. */
    fun ensure(context: Context) {
        val app = context.applicationContext
        worker.execute { ensureBlocking(app) }
    }

    private fun settingChanged(context: Context) {
        val store = ConfigStore(context)
        if (!store.keepAdb) return
        val enabled = adbEnabled(context)
        if (enabled == 0) {
            val interactive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isInteractive == true
            if (interactive && SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
                store.keepAdb = false
                Log.i(TAG, "ADB switched off while the TV is in use; keep_adb is off now")
                return
            }
        }
        ensureBlocking(context)
    }

    private fun ensureBlocking(context: Context) {
        if (dancing.get()) return
        if (!ConfigStore(context).keepAdb) return
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "keep_adb is on but WRITE_SECURE_SETTINGS was never granted")
            return
        }
        val resolver = context.contentResolver
        val current = runCatching {
            Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0)
        Log.d(TAG, "adb_enabled=$current")
        if (current == 0) {
            runCatching { Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1) }
                .onSuccess { Log.i(TAG, "adb_enabled was 0; set to 1") }
                .onFailure { Log.w(TAG, "cannot set adb_enabled", it) }
            return
        }
        ensureListening(context)
    }

    /**
     * adb_enabled = 1 does not mean adbd listens: after boot some firmware leaves it
     * out of TCP mode, and only a 0 -> 1 transition of the setting restarts it.
     * Android 11+ uses wireless debugging on its own port, so 5555 is closed by design:
     * skip the probe there. Only in the first minutes after boot, and at most once.
     */
    private fun ensureListening(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS || danced.get()) return
        if (listening()) {
            Log.d(TAG, "adbd is listening on $PORT")
            return
        }
        synchronized(this) {
            if (danced.getAndSet(true)) return
            if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
            dancing.set(true)
            try {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                    Thread.sleep(1_000)
                    Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                    Log.i(TAG, "adbd was not listening; toggled adb_enabled 0 -> 1")
                }.onFailure { Log.w(TAG, "cannot toggle adb_enabled", it) }
            } finally {
                dancing.set(false)
            }
        }
    }

    /** adbd answering on the TV itself; a refusal means it is not in TCP mode. */
    private fun listening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 400) }
        true
    } catch (_: Exception) {
        false
    }

    private fun adbEnabled(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
    }.getOrDefault(0)
}
