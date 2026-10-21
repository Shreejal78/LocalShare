package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.network.NetworkUtils
import com.example.server.LocalHttpServer
import com.example.server.ServerManager

class SharingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "localshare_server_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val EXTRA_URL = "extra_url"

        @Volatile
        var activeServerManager: ServerManager? = null

        @Volatile
        var activeHttpServer: LocalHttpServer? = null

        @Volatile
        var activeInstance: SharingForegroundService? = null

        fun startService(
            context: Context,
            url: String,
            serverManager: ServerManager? = null
        ) {
            if (serverManager != null) {
                activeServerManager = serverManager
            }
            val intent = Intent(context, SharingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SharingForegroundService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
            context.startService(intent)
        }
    }

    var httpServer: LocalHttpServer?
        get() = activeHttpServer ?: activeServerManager?.currentHttpServer
        set(value) {
            activeHttpServer = value
        }

    fun stopAllTunnels() {
        activeServerManager?.stopAllTunnels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER || intent?.action == ACTION_STOP) {
            shutdownServerCompletely()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            // Mandatory Wi-Fi or Hotspot check before running foreground service
            if (!NetworkUtils.isWifiOrHotspotActive(applicationContext)) {
                activeServerManager?.stopServer()
                shutdownServerCompletely()
                return START_NOT_STICKY
            }

            val url = intent.getStringExtra(EXTRA_URL) ?: "http://localhost:8080"
            val notification = buildNotification(url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    private fun shutdownServerCompletely() {
        try {
            // 1. Stop the embedded HTTP server instance
            httpServer?.stop()
            httpServer = null

            // 2. Terminate any active tunnels
            stopAllTunnels()

            // Update ServerManager to update StateFlow and trigger dashboard UI back to "Stopped"
            activeServerManager?.stopServer()

            // 3. Remove notification and stop foreground status
            stopForeground(STOP_FOREGROUND_REMOVE)

            // 4. Terminate the service
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) {
            activeInstance = null
        }
        shutdownServerCompletely()
    }

    private fun buildNotification(serverUrl: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SharingForegroundService::class.java).apply {
            action = ACTION_STOP_SERVER
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalShare is running")
            .setContentText("Accessible at $serverUrl")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LocalShare File Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while local file server is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
