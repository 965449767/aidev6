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

    protected var scope: CoroutineScope? = null
    protected var appCtx: Context? = null

    val isRunning: Boolean get() = scope != null

    fun start(context: Context?, homeDir: File) {
        if (scope != null) return
        appCtx = context?.applicationContext ?: context
        onStart(homeDir)
        AIDevLogger.i(tag, "start polling")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope!!.launch {
            while (isActive) {
                runCatching { poll() }.onFailure { AIDevLogger.w(tag, "poll failed", it) }
                delay(500)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        appCtx = null
        onStop()
    }

    protected open fun onStart(homeDir: File) {}
    protected open fun onStop() {}
    protected abstract fun poll()

    protected fun claimFile(dir: File, file: File): File? {
        val claimed = File(dir, "${file.name}.processing")
        return if (file.renameTo(claimed)) claimed else null
    }
}
