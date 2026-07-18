package com.aidev.six

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

abstract class BridgeService(private val tag: String) {

    @Volatile protected var scope: CoroutineScope? = null
    @Volatile protected var appCtx: Context? = null
    @Volatile private var socketServer: BridgeSocketServer? = null

    val isRunning: Boolean get() = scope != null
    val isSocketRunning: Boolean get() = socketServer?.isRunning == true

    /** 桥名；空字符串表示未接入 Socket（仅走文件轮询）。子类在后续阶段覆盖。 */
    open val bridgeName: String get() = ""

    /**
     * Socket 请求分发。默认返回 null（表示「已接收但无即时响应，结果经文件通道异步回传」）。
     * 简单桥（notify 等）可在后续阶段覆盖为同步返回响应帧。
     */
    open fun dispatch(frame: BridgeFrame): BridgeFrame? = null

    // 空闲退避：500ms → 1s → 2s → 3s（最大），有新请求时立即重置
    private var lastActiveMs = 0L
    private var currentDelayMs = 500L
    private val maxDelayMs = 3000L

    fun start(context: Context?, homeDir: File) {
        if (scope != null) return
        appCtx = context?.applicationContext ?: context
        onStart(homeDir)
        AIDevLogger.i(tag, "start polling")
        BridgeRegistry.register(this)
        startSocketIfEnabled()
        lastActiveMs = System.currentTimeMillis()
        currentDelayMs = 500L
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            while (isActive) {
                val hadWork = runCatching { poll() }.getOrElse {
                    AIDevLogger.w(tag, "poll failed", it)
                    false
                }
                if (hadWork) {
                    lastActiveMs = System.currentTimeMillis()
                    currentDelayMs = 500L
                } else {
                    val idleMs = System.currentTimeMillis() - lastActiveMs
                    currentDelayMs = if (idleMs > 1000) {
                        (currentDelayMs * 2).coerceAtMost(maxDelayMs)
                    } else {
                        500L
                    }
                }
                delay(currentDelayMs)
            }
        }
    }

    private fun startSocketIfEnabled() {
        val ctx = appCtx ?: return
        runCatching {
            if (PreferencesManager(ctx).bridgeSocketEnabled && bridgeName.isNotBlank()) {
                val pm = PreferencesManager(ctx)
                pm.syncTokenToAidevHome(PathConfig.aidevHome(ctx))
                val srv = BridgeSocketServer(
                    TcpBridgeTransport(
                        host = "127.0.0.1",
                        port = Constants.BRIDGE_SOCKET_PORT,
                        authToken = pm.bridgeToken
                    )
                )
                srv.start { frame -> BridgeRegistry.dispatch(frame) }
                socketServer = srv
                AIDevLogger.i(tag, "socket bridge started on 127.0.0.1:${Constants.BRIDGE_SOCKET_PORT}")
            }
        }.onFailure { AIDevLogger.w(tag, "start socket bridge failed", it) }
    }

    fun stop() {
        val s = scope
        scope = null
        appCtx = null
        s?.cancel()
        onStop()
        runCatching { socketServer?.stop() }
        socketServer = null
        BridgeRegistry.unregister(this)
    }

    protected open fun onStart(homeDir: File) {}
    protected open fun onStop() {}
    /** @return true 表示本轮有实际工作（新请求），false 表示空闲轮询 */
    protected abstract fun poll(): Boolean

    protected fun claimFile(dir: File, file: File): File? {
        val claimed = File(dir, "${file.name}.processing")
        return if (file.renameTo(claimed)) claimed else null
    }
}
