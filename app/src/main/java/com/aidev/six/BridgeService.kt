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

    val isRunning: Boolean get() = scope != null

    // 空闲退避：500ms → 1s → 2s → 3s（最大），有新请求时立即重置
    private var lastActiveMs = 0L
    private var currentDelayMs = 500L
    private val maxDelayMs = 3000L

    fun start(context: Context?, homeDir: File) {
        if (scope != null) return
        appCtx = context?.applicationContext ?: context
        onStart(homeDir)
        AIDevLogger.i(tag, "start polling")
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
                    currentDelayMs = if (idleMs > 5000) {
                        (currentDelayMs * 2).coerceAtMost(maxDelayMs)
                    } else {
                        500L
                    }
                }
                delay(currentDelayMs)
            }
        }
    }

    fun stop() {
        val s = scope
        scope = null
        appCtx = null
        s?.cancel()
        onStop()
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
