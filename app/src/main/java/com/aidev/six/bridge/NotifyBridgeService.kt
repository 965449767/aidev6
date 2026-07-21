package com.aidev.six.bridge

import com.aidev.six.AIDevCommandDispatcher
import com.aidev.six.AIDevLogger
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object NotifyBridgeService : BridgeService("NotifyBridge") {

    private const val BRIDGE_DIR = ".aidev-notify"

    @Volatile private var requestDir: File? = null

    override val bridgeName: String get() = "notify"

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also { it.mkdirs() }
    }

    override fun poll(): Boolean {
        val reqDir = requestDir ?: return false
        var hadWork = false
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            hadWork = true
            scope?.launch { handleRequest(claimed) }
        }
        return hadWork
    }

    /**
     * Socket 通道入口：payload 直接承载原有 JSON 文本，复用 [handleJson]。
     * 返回响应帧（status=ok）；失败返回 null（异常已被兜底，等同吞掉）。
     */
    override fun dispatch(frame: BridgeFrame): BridgeFrame? {
        val result = runCatching { handleJson(frame.payload) }
            .onFailure { AIDevLogger.w("NotifyBridge", "dispatch failed", it) }
            .getOrNull()
        return BridgeFrame("notify", frame.id, result ?: "ok")
    }

    private fun handleJson(content: String): String? {
        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("NotifyBridge", "parse json failed", it) }
            .getOrNull() ?: return null

        val title = json.optString("title", "AIDev Terminal")
        val msg = json.optString("message", "")
        val priority = json.optString("priority", "").takeIf { it.isNotBlank() }
        val ongoing = json.optBoolean("ongoing", false)
        val alertOnlyOnce = json.optBoolean("alert_only_once", false)

        val ctx = appCtx ?: return null
        AIDevCommandDispatcher.notify(ctx, title, msg, priority, ongoing, alertOnlyOnce)
        AIDevLogger.i("NotifyBridge", "notification sent: title=$title")
        return "ok"
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("NotifyBridge", "read processing file failed", it) }
            .getOrNull() ?: return

        handleJson(content)
        processingFile.delete()
    }
}
