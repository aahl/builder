package cn.alone.hamitv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.esp.EspServer
import cn.alone.hamitv.net.localIpv4
import cn.alone.hamitv.tv.CommandExecutor
import cn.alone.hamitv.web.ConfigServer

/**
 * Hosts the embedded web UI (and later the ESPHome API server) so it keeps
 * running while the TV is showing something else.
 */
class AgentService : Service() {

    private lateinit var store: ConfigStore
    private lateinit var server: ConfigServer
    private lateinit var esp: EspServer

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        esp = EspServer(this, store, CommandExecutor(this)) { Log.d(TAG, it) }
        server = ConfigServer(this, store, esp)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        server.start()
        runCatching { esp.start() }
            .onFailure { Log.e(TAG, "esp server failed to start on ${store.espPort}", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        esp.stop()
        server.stop()
        super.onDestroy()
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

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("HA MiTV")
            .setContentText("配置台 http://${localIpv4()}:${store.webPort}")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 1

        fun start(context: android.content.Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java),
            )
        }
    }
}
