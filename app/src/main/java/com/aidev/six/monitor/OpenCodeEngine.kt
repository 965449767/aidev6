package com.aidev.six.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aidev.six.AIDevApp
import com.aidev.six.AIDevCommandDispatcher
import com.aidev.six.AIDevLogger
import com.aidev.six.Constants
import com.aidev.six.OpenCodeActionReceiver
import com.aidev.six.PathConfig
import com.aidev.six.ShellActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * [AIEngine] 的 OpenCode 实现。
 *
 * 把原 [com.aidev.six.OpenCodeMonitorService] 中的 HTTP/SSE/通知逻辑搬迁至此，
 * 使监控 Service 退化为薄壳。行为保持：SSE 协议解析、MonitorState 状态机、
 * 通知文案/中止按钮、轮询节奏均与重构前一致。
 *
 * baseUrl 解析：优先读取 PRoot 写入的端口文件 `${aidevHome}/.aidev-opencode-port`
 * （OpenCode 落在非 4096 端口时由 aidev-opencode 脚本写出），回退 [Constants.OPENCODE_BASE_URL]。
 */
class OpenCodeEngine(private val host: Context) : AIEngine {

    private val notificationContext: Context get() = host
    private val sessionStates = ConcurrentHashMap<String, String>()
    @Volatile private var overallState = MonitorState.DISCONNECTED

    override val baseUrl: String by lazy { resolveBaseUrl() }

    private fun resolveBaseUrl(): String {
        val fallback = Constants.OPENCODE_BASE_URL
        return runCatching {
            val portFile = File(PathConfig.aidevHome(host), ".aidev-opencode-port")
            if (portFile.isFile && portFile.canRead()) {
                val port = portFile.readText().trim().toIntOrNull()
                if (port != null && port > 0) {
                    AIDevLogger.d(TAG, "resolved OpenCode port from file: $port")
                    "http://127.0.0.1:$port"
                } else fallback
            } else fallback
        }.getOrDefault(fallback)
    }

    fun startMonitoring(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                if (isServerUp()) {
                    overallState = MonitorState.IDLE
                    updateNotification()
                    startSseConnection(scope)
                    break
                }
                delay(3000)
            }
        }
    }

    override fun startMonitoring() = Unit // 由宿主 Service 在自身 scope 内调用带 scope 的版本

    override fun stopMonitoring() = Unit // 由宿主 Service 取消 scope 子任务

    fun cancelMonitoring(scope: CoroutineScope) {
        scope.coroutineContext.cancelChildren()
    }

    override fun isServerUp(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl/global/health")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun startSseConnection(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                var conn: HttpURLConnection? = null
                var reader: BufferedReader? = null
                try {
                    val url = URL("$baseUrl/event")
                    conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.setRequestProperty("Cache-Control", "no-cache")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 30_000

                    reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        if (!isActive) break
                        val l = line ?: continue

                        if (l.startsWith("data:")) {
                            val raw = l.removePrefix("data:").trim()
                            if (raw.isEmpty()) continue
                            try {
                                val json = JSONObject(raw)
                                dispatchEvent(json)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                AIDevLogger.w(TAG, "parse error: ${e.message} raw=$raw")
                            }
                        }
                    }
                    AIDevLogger.d(TAG, "SSE connection closed, reconnecting...")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!isActive) break
                    AIDevLogger.w(TAG, "SSE error: ${e.message}")
                } finally {
                    runCatching { reader?.close() }
                    runCatching { conn?.disconnect() }
                }
                overallState = MonitorState.DISCONNECTED
                updateNotification()
                delay(5000)
            }
        }
    }

    private fun dispatchEvent(json: JSONObject) {
        val eventType = json.optString("type", "")

        when (eventType) {
            "server.connected" -> {
                AIDevLogger.d(TAG, "server.connected")
                sessionStates.clear()
                overallState = MonitorState.IDLE
                updateNotification()
                pollActiveSessions()
            }
            "server.heartbeat" -> {
                // heartbeat every 10s, no action needed
            }
            "session.status" -> handleSessionStatus(json)
            "session.idle" -> handleSessionIdle(json)
            else -> {
                AIDevLogger.d(TAG, "ignored event: $eventType")
            }
        }
    }

    private fun handleSessionStatus(json: JSONObject) {
        val properties = json.optJSONObject("properties") ?: return
        val sid = properties.optString("sessionID", "")
        if (sid.isEmpty()) return

        val status = properties.optJSONObject("status")
        val state = status?.optString("type", "") ?: ""

        when (state) {
            "busy", "retry" -> {
                sessionStates[sid] = state
                AIDevLogger.d(TAG, "session $sid -> $state")
                recomputeOverallState()
            }
            "idle" -> {
                sessionStates.remove(sid)
                AIDevLogger.d(TAG, "session $sid -> idle")
                recomputeOverallState()
            }
            else -> {
                AIDevLogger.d(TAG, "session.status unknown state: $state")
            }
        }
    }

    private fun handleSessionIdle(json: JSONObject) {
        val properties = json.optJSONObject("properties") ?: return
        val sid = properties.optString("sessionID", "")
        if (sid.isEmpty()) return

        sessionStates.remove(sid)
        AIDevLogger.d(TAG, "session.idle $sid")
        recomputeOverallState()
    }

    private fun recomputeOverallState() {
        val newState = if (sessionStates.any { (_, s) -> s == "busy" || s == "retry" }) {
            MonitorState.BUSY
        } else {
            MonitorState.IDLE
        }
        if (newState != overallState) {
            overallState = newState
            AIDevLogger.d(TAG, "overall state -> $newState")
            updateNotification()
            if (newState == MonitorState.BUSY && AIDevApp.getCurrentActivity() == null) {
                fireHeadsUp("AI代理正在思考", "OpenCode 会话进行中")
            }
            if (newState == MonitorState.IDLE && AIDevApp.getCurrentActivity() == null) {
                fireHeadsUp("对话完成", "所有会话已空闲")
            }
        } else {
            updateNotification()
        }
    }

    override fun pollActiveSessions() {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/session/active")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val map = JSONObject(body)
                var hasRunning = false
                for (key in map.keys()) {
                    val entry = map.optJSONObject(key)
                    val t = entry?.optString("type", "")
                    if (t == "running") {
                        sessionStates[key] = "busy"
                        hasRunning = true
                    }
                }
                if (hasRunning) {
                    AIDevLogger.d(TAG, "active sessions found: ${sessionStates.size}")
                    recomputeOverallState()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AIDevLogger.d(TAG, "poll active failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    override fun abortSession(sessionId: String) {
        runCatching {
            val url = URL("$baseUrl/session/${android.net.Uri.encode(sessionId)}/abort")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.use { os ->
                os.write("{}".toByteArray())
                os.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            AIDevLogger.d(TAG, "abort $sessionId -> $code")
        }.onFailure {
            AIDevLogger.w(TAG, "abort failed: ${it.message}")
        }
    }

    private fun fireHeadsUp(title: String, msg: String) {
        AIDevCommandDispatcher.notify(notificationContext, title, msg, priority = "high", alertOnlyOnce = true)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = notificationContext.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "OpenCode 监控", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Agent 运行状态实时监控"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    fun ensureChannelAndNotifyInitial(): Notification {
        ensureChannel()
        return buildNotification()
    }

    fun updateNotification() {
        val nm = notificationContext.getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    fun buildNotification(): Notification {
        val isForeground = AIDevApp.getCurrentActivity() != null

        val builder = NotificationCompat.Builder(notificationContext, CHANNEL_ID)

        val viewPendingIntent = PendingIntent.getActivity(
            notificationContext, 0,
            Intent(notificationContext, ShellActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("shell_tab", ShellActivity.TAB_TERMINAL)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        when (overallState) {
            MonitorState.DISCONNECTED -> {
                builder.setContentTitle("OpenCode 未运行")
                    .setContentText("等待 opencode 启动…")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(true)
            }
            MonitorState.IDLE -> {
                builder.setContentTitle("OpenCode 监控中")
                    .setContentText("Agent 空闲")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setProgress(0, 0, false)
            }
            MonitorState.BUSY -> {
                builder.setContentTitle("OpenCode")
                    .setContentText("AI代理正在思考…")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setProgress(0, 0, true)
                    .setOnlyAlertOnce(true)

                val sid = sessionStates.entries.firstOrNull { (_, s) -> s == "busy" || s == "retry" }?.key
                if (sid != null) {
                    val abortIntent = PendingIntent.getBroadcast(
                        notificationContext, 1,
                        Intent(notificationContext, OpenCodeActionReceiver::class.java).apply {
                            action = OpenCodeActionReceiver.ACTION_ABORT
                            putExtra("session_id", sid)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", abortIntent)
                }

                builder.addAction(android.R.drawable.ic_menu_view, "查看", viewPendingIntent)
            }
        }

        builder.setContentIntent(viewPendingIntent)

        if (isForeground) {
            builder.setSilent(true)
        }

        return builder.build()
    }

    private enum class MonitorState {
        DISCONNECTED, IDLE, BUSY
    }

    companion object {
        private const val TAG = "OCMonitor"
        private const val CHANNEL_ID = "opencode_monitor"
        private const val NOTIFICATION_ID = 4202
    }
}
