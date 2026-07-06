package com.aidev.four

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification())
            }.onFailure { e ->
                AIDevLogger.w(TAG, "startForeground failed", e)
                stopSelf()
            }
        } else {
            AIDevLogger.w(TAG, "POST_NOTIFICATIONS not granted, skipping startForeground")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIDevTerminal:server-wakelock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { e -> AIDevLogger.w(TAG, "Failed to acquire wake lock", e) }
        }
        if (wifiLock?.isHeld != true) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    val wifiMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    wifiLock = wm.createWifiLock(wifiMode, "AIDevTerminal:server-wifilock").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }.onFailure { e -> AIDevLogger.w(TAG, "Failed to acquire wifi lock", e) }
        }
    }

    private fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            AIDevLogger.w(TAG, "Failed to release wifi lock", e)
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            AIDevLogger.w(TAG, "Failed to release wake lock", e)
        }
        wifiLock = null
        wakeLock = null
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AIDev 后台常驻",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持终端、下载任务、AI 服务和本机 Web 服务尽可能稳定运行"
                    setShowBadge(false)
                }
            )
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AIDev Terminal 正在常驻")
                .setContentText("保持 Linux/AI/Web 服务后台运行")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AIDev Terminal 正在常驻")
                .setContentText("保持 Linux/AI/Web 服务后台运行")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "aidev_keepalive"
        private const val NOTIFICATION_ID = 4201
        private val isRunning = AtomicBoolean(false)

        fun start(context: Context) {
            if (!isRunning.compareAndSet(false, true)) {
                AIDevLogger.d(TAG, "KeepAliveService already running, skipping start")
                return
            }
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    context.startForegroundService(intent)
                } else {
                    AIDevLogger.w(TAG, "POST_NOTIFICATIONS not granted, starting as background service")
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
            isRunning.set(false)
        }
    }
}
