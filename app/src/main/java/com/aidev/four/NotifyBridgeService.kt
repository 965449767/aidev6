package com.aidev.four

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object NotifyBridgeService : BridgeService("NotifyBridge") {

    private const val BRIDGE_DIR = ".aidev-notify"

    private var requestDir: File? = null

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also { it.mkdirs() }
    }

    override fun poll() {
        val reqDir = requestDir ?: return
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            scope?.launch { handleRequest(claimed) }
        }
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("NotifyBridge", "read processing file failed", it) }
            .getOrNull() ?: return

        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("NotifyBridge", "parse json failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val title = json.optString("title", "AIDev Terminal")
        val msg = json.optString("message", "")
        val priority = json.optString("priority", "").takeIf { it.isNotBlank() }
        val ongoing = json.optBoolean("ongoing", false)
        val alertOnlyOnce = json.optBoolean("alert_only_once", false)

        val ctx = appCtx ?: run { processingFile.delete(); return }
        AIDevCommandDispatcher.notify(ctx, title, msg, priority, ongoing, alertOnlyOnce)

        processingFile.delete()
        AIDevLogger.i("NotifyBridge", "notification sent: title=$title")
    }
}
