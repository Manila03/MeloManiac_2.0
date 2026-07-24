package com.melomaniac.app.download

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.melomaniac.app.MainActivity
import com.melomaniac.app.util.AppLog

/**
 * Foreground service so downloads keep network access with screen off / app backgrounded.
 * Work still runs in [DownloadQueue]; this only elevates the process.
 */
class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireWakeLock()
        AppLog.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "stop requested")
                stopForegroundInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT)?.ifBlank { null } ?: DEFAULT_TEXT
                val notification = buildNotification(text)
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
                AppLog.i(TAG, "foreground: $text")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        markStopped()
        AppLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun stopForegroundInternal() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Descargas",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progreso de descargas en segundo plano"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MeloManiac descargando")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeloManiac:Download").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // max 4h safety
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.melomaniac.app.download.START"
        const val ACTION_UPDATE = "com.melomaniac.app.download.UPDATE"
        const val ACTION_STOP = "com.melomaniac.app.download.STOP"
        const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "Descargando…"

        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        fun startOrUpdate(context: Context, text: String = DEFAULT_TEXT) {
            val app = context.applicationContext
            val action = if (running) ACTION_UPDATE else ACTION_START
            val intent = Intent(app, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(app, intent)
                running = true
            } catch (e: Exception) {
                AppLog.e(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(context: Context) {
            if (!running) return
            val app = context.applicationContext
            val intent = Intent(app, DownloadService::class.java).apply { action = ACTION_STOP }
            try {
                // startService is enough to deliver STOP; FGS already running
                app.startService(intent)
            } catch (e: Exception) {
                AppLog.w(TAG, "stop via startService failed: ${e.message}")
                runCatching {
                    app.stopService(Intent(app, DownloadService::class.java))
                }
            }
            running = false
        }

        fun markStopped() {
            running = false
        }
    }
}
