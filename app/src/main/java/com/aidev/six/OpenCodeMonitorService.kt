package com.aidev.six

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Build
import com.aidev.six.monitor.OpenCodeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode 监控前台服务（薄壳）。
 *
 * 实际 HTTP/SSE/通知逻辑已抽取到 [OpenCodeEngine]（[com.aidev.six.monitor.AIEngine] 的 OpenCode 实现）。
 * 本 Service 仅负责生命周期：前台通知、协程 scope、delegate 给引擎。
 */
class OpenCodeMonitorService : android.app.Service() {

    private var serviceScope: CoroutineScope? = null
    private var engine: OpenCodeEngine? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine = OpenCodeEngine(this)
        val engine = this.engine ?: return
        val notification = runCatching { engine.ensureChannelAndNotifyInitial() }.getOrNull()
        if (notification == null) {
            AIDevLogger.w(TAG, "initial notification failed (permission not granted?)")
            stopSelf()
            return
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure { e ->
            AIDevLogger.w(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        engine.startMonitoring(serviceScope!!)
    }

    override fun onDestroy() {
        engine?.cancelMonitoring(serviceScope ?: return)
        serviceScope?.cancel()
        serviceScope = null
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OCMonitor"
        private const val NOTIFICATION_ID = 4202

        private val isRunning = AtomicBoolean(false)

        fun start(context: Context) {
            if (!isRunning.compareAndSet(false, true)) {
                AIDevLogger.d(TAG, "already running, skipping")
                return
            }
            val intent = Intent(context, OpenCodeMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OpenCodeMonitorService::class.java))
            isRunning.set(false)
        }
    }
}
