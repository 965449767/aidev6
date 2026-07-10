package com.aidev.six

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * 自我进化闭环的崩溃回传桥。
 *
 * 宿主安装并拉起新构建的 App 后，OpenCode（宇宙 A）可通过 `aidev-crash-report` 工具，
 * 或写入请求文件 `home/.aidev-crash-bridge/req-<id>.json`：
 *   { "package": "<目标包名>", "lines": 1000 }
 * 本服务用 Shizuku 抓取该包 logcat，过滤 FATAL EXCEPTION / 崩溃堆栈，
 * 写成标准 MCP 风格 JSON 到 `home/.aidev-mcp/crash-<ts>.json`（并维护 latest.json），
 * 供 OpenCode 读取后自我修正 —— 完成「写码→编译→运行→崩溃→进化」闭环。
 */
object CrashReportBridgeService : BridgeService("CrashReportBridge") {

    private const val BRIDGE_DIR = ".aidev-crash-bridge"
    private const val MCP_DIR = ".aidev-mcp"

    private var requestDir: File? = null

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also { it.mkdirs() }
        File(homeDir, MCP_DIR).mkdirs()
    }

    override fun poll() {
        val reqDir = requestDir ?: return
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            scope?.launch { handleRequest(claimed) }
        }
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("CrashReportBridge", "read request failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("CrashReportBridge", "parse json failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val ctx = appCtx ?: run { processingFile.delete(); return }
        val pkg = json.optString("package", "").ifBlank { "" }
        val lines = json.optInt("lines", 1000).coerceIn(100, 5000)

        if (pkg.isBlank()) {
            finish(ctx, processingFile, false, "缺少 package 字段", "")
            return
        }

        val report = kotlin.runCatching {
            suspendCancellableCoroutine<CrashReport> { cont ->
                ShizukuLogcat.fetchLog(
                    packageName = pkg,
                    lines = lines,
                    filters = listOf("FATAL EXCEPTION", "AndroidRuntime", "Exception", "Error", "crash", "ANR"),
                    callback = { res ->
                        res.onSuccess { raw -> cont.resume(parseCrash(pkg, raw)) }
                        res.onFailure { e -> cont.resume(CrashReport(pkg, emptyList(), "logcat 抓取失败: ${e.message}", "")) }
                    }
                )
            }
        }.getOrElse { CrashReport(pkg, emptyList(), "异常: ${it.message}", "") }

        val reportJson = buildReportJson(report)
        val mcpDir = File(PathConfig.aidevHome(ctx), MCP_DIR)
        mcpDir.mkdirs()
        val outFile = File(mcpDir, "crash-${System.currentTimeMillis()}.json")
        val latest = File(mcpDir, "latest.json")
        runCatching { outFile.writeText(reportJson) }
        runCatching { latest.writeText(reportJson) }

        val ok = report.stack.isNotEmpty()
        val msg = if (ok) "捕获到崩溃堆栈（${report.stack.size} 行）" else "未捕获到崩溃（应用可能正常运行）"
        AIDevLogger.i("CrashReportBridge", "crash report for $pkg ok=$ok -> ${outFile.name}")
        finish(ctx, processingFile, true, msg, outFile.name)
    }

    private fun parseCrash(pkg: String, raw: String): CrashReport {
        val lines = raw.lineSequence().toList()
        val idx = lines.indexOfFirst { it.contains("FATAL EXCEPTION") }
        if (idx < 0) {
            // 退而求其次：查找第一个 Exception/Error 行
            val alt = lines.indexOfFirst { it.contains("Exception") || it.contains("Error") }
            if (alt < 0) return CrashReport(pkg, emptyList(), "未检测到崩溃", raw.takeLast(2000))
            val block = lines.subList(alt, minOf(alt + 60, lines.size))
            return CrashReport(pkg, block.map { it.trim() }, block.firstOrNull()?.trim() ?: "crash", raw.takeLast(2000))
        }
        val block = lines.subList(idx, minOf(idx + 80, lines.size))
        return CrashReport(pkg, block.map { it.trim() }, block.firstOrNull()?.trim() ?: "FATAL EXCEPTION", raw.takeLast(2000))
    }

    private fun buildReportJson(report: CrashReport): String {
        val stackArr = org.json.JSONArray().apply { report.stack.forEach { put(it) } }
        return JSONObject().apply {
            put("type", "mcp/crash-report")
            put("package", report.pkg)
            put("time", System.currentTimeMillis())
            put("fatal", report.fatal)
            put("stack", stackArr)
            put("raw", report.raw)
        }.toString(2)
    }

    private fun finish(ctx: Context, reqFile: File, success: Boolean, message: String, fileName: String) {
        val result = JSONObject().apply {
            put("success", success)
            put("message", message)
            put("file", fileName)
            put("time", System.currentTimeMillis())
        }
        runCatching { File(requestDir, "result-${reqFile.nameWithoutExtension}.json").writeText(result.toString(2)) }
        reqFile.delete()
        notify(ctx, if (success) "崩溃报告已生成" else "崩溃报告", message, priority = "high")
    }

    private fun notify(ctx: Context, title: String, msg: String, priority: String) {
        runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
    }

    private data class CrashReport(
        val pkg: String,
        val stack: List<String>,
        val fatal: String,
        val raw: String
    )
}
