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
                ShizukuLogcat.fetchCrashLog(
                    lines = lines,
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

    /**
     * 从 logcat 中提取目标包的崩溃堆栈。
     * 关键点：① 剔除本工具自身日志（ShizukuLogcat 自污染）；② 定位 FATAL EXCEPTION 且其后
     * "Process: <pkg>" 与目标包匹配的块（有多个崩溃时取最后一个=最新）；③ 只收 AndroidRuntime 行，
     * 遇到无关日志即停，避免混入系统噪声。
     */
    private fun parseCrash(pkg: String, raw: String): CrashReport {
        val lines = raw.lineSequence()
            .filterNot { it.contains("ShizukuLogcat") || it.contains("---MAIN---") }
            .toList()

        // 所有 FATAL EXCEPTION 位置
        val fatalIdxs = lines.indices.filter { lines[it].contains("FATAL EXCEPTION") }
        // 优先选块内 Process: <pkg> 匹配的；否则取最后一个 FATAL
        val chosen = fatalIdxs.lastOrNull { start ->
            lines.subList(start, minOf(start + 4, lines.size)).any { it.contains("Process: $pkg") }
        } ?: fatalIdxs.lastOrNull()

        if (chosen != null) {
            val block = collectCrashBlock(lines, chosen)
            val header = block.firstOrNull { it.contains("Exception") || it.contains("Error") }
                ?: block.firstOrNull() ?: "FATAL EXCEPTION"
            return CrashReport(pkg, block, header.trim(), block.joinToString("\n").takeLast(4000))
        }

        // 兜底：无 FATAL，找该包相关的 Exception/Error 行
        val alt = lines.indexOfFirst {
            (it.contains("Exception") || it.contains("Error")) && it.contains("AndroidRuntime")
        }
        if (alt < 0) return CrashReport(pkg, emptyList(), "未检测到崩溃", raw.takeLast(2000))
        val block = collectCrashBlock(lines, alt)
        return CrashReport(pkg, block, block.firstOrNull()?.trim() ?: "crash", block.joinToString("\n").takeLast(4000))
    }

    /** 从 FATAL 行起，收集连续的 AndroidRuntime 堆栈行（threadtime 格式每行都含 "AndroidRuntime:"）。 */
    private fun collectCrashBlock(lines: List<String>, start: Int): List<String> {
        val out = ArrayList<String>()
        var i = start
        var gap = 0
        while (i < lines.size && out.size < 120) {
            val line = lines[i]
            if (line.contains("AndroidRuntime")) {
                out.add(line.substringAfter("AndroidRuntime:", line).trim().ifBlank { line.trim() })
                gap = 0
            } else if (out.isNotEmpty()) {
                // 允许极少量穿插，连续 2 行非 AndroidRuntime 视为堆栈结束
                if (++gap >= 2) break
            }
            i++
        }
        return out
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
