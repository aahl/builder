package cn.alone.hamitv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.esp.EspServer
import cn.alone.hamitv.net.localIpv4
import cn.alone.hamitv.tv.CommandExecutor
import cn.alone.hamitv.web.ConfigServer

/**
 * Hosts the embedded web UI and the ESPHome API so both keep running while the
 * TV shows something else.
 *
 * Staying alive is mostly the foreground notification plus the restart paths
 * below: the system restarts a sticky service after a low-memory kill, and
 * [onTaskRemoved] covers the user dismissing the task. Nothing survives a
 * force-stop, which is why the console points at the TV's autostart settings
 * instead of pretending otherwise.
 */
class AgentService : Service() {

    private lateinit var store: ConfigStore
    private lateinit var server: ConfigServer
    private lateinit var esp: EspServer

    private var lastIp: String = ""

    private val connectivity: ConnectivityManager? by lazy {
        getSystemService(ConnectivityManager::class.java)
    }

    /**
     * mDNS is bound to the address the app had when it registered, so a new IP
     * (DHCP renew, Wi-Fi switch) needs a fresh record or HA stops discovering
     * the TV. The TCP listeners bind 0.0.0.0 and do not care.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()

        override fun onLost(network: Network) = onNetworkChanged()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            onNetworkChanged()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        store = ConfigStore(this)
        esp = EspServer(this, store, CommandExecutor(this)) { Log.d(TAG, it) }
        server = ConfigServer(this, store, esp)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(localIpv4()))
        server.start()
        runCatching { esp.start() }
            .onFailure { Log.e(TAG, "esp server failed to start on ${store.espPort}", it) }

        lastIp = localIpv4()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "cannot watch the network", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The task was dismissed. A sticky service is not restarted for that, so
     * schedule one shortly after; a foreground PendingIntent keeps the
     * background-start restriction from rejecting it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = PendingIntent.getForegroundService(
            this,
            RESTART_REQUEST,
            Intent(this, AgentService::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            getSystemService(AlarmManager::class.java)
                ?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1_000, restart)
        }.onFailure { Log.w(TAG, "cannot schedule the restart", it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        esp.stop()
        server.stop()
        super.onDestroy()
    }

    private fun onNetworkChanged() {
        val ip = localIpv4()
        if (ip == lastIp) return
        lastIp = ip
        Log.d(TAG, "network changed, ip=$ip")
        runCatching { esp.refreshAdvertise() }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(ip))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the configuration UI and ESPHome API running"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(ip: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("HA MiTV")
            .setContentText("配置台 http://$ip:${store.webPort}")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_REQUEST = 2

        /** Lets the console tell "opened by hand" from "started by the launcher". */
        @Volatile
        var isRunning = false
            private set

        fun start(context: android.content.Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java),
            )
        }
    }
}
