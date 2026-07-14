package com.aidev.six

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
object ShizukuBridgeService : BridgeService("ShizukuBridge") {

    private const val BRIDGE_DIR = ".aidev-shizuku-bridge"
    private const val REQUEST_DIR = "request"
    private const val RESULT_DIR = "result"

    private var requestDir: File? = null
    private var resultDir: File? = null

    override val bridgeName: String get() = "shizuku"

    override fun onStart(homeDir: File) {
        val bridgeDir = File(homeDir, BRIDGE_DIR)
        requestDir = File(bridgeDir, REQUEST_DIR).also { it.mkdirs() }
        resultDir = File(bridgeDir, RESULT_DIR).also { it.mkdirs() }
    }

    override fun poll(): Boolean {
        val reqDir = requestDir ?: return false
        val resDir = resultDir ?: return false
        var hadWork = false
        reqDir.listFiles()?.filter {
            (it.name.startsWith("log_") || it.name.startsWith("camera_") ||
             it.name.startsWith("exec_") || it.name.startsWith("hb_")) &&
            !it.name.endsWith(".processing")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            hadWork = true
            scope?.launch { handleRequest(reqDir, resDir, claimed) }
        }
        return hadWork
    }

    /**
     * Socket 通道入口：payload 承载原 KEY=VALUE 文本，复用 [computeExec]/[computeLog]，
     * 把结果直接放在响应帧里返回（即时响应，不走 result 文件）。
     * 注意：本函数非 suspend，桥接处理线程为普通线程，故用 [runBlocking] 驱动内部 suspend 逻辑。
     */
    override fun dispatch(frame: BridgeFrame): BridgeFrame? {
        val result = runCatching { computePayload(frame.payload) }
            .onFailure { AIDevLogger.w("ShizukuBridge", "dispatch failed", it) }
            .getOrNull()
        return BridgeFrame("shizuku", frame.id, result ?: "ERROR: 处理失败")
    }

    private fun computePayload(content: String): String {
        val fields = parseFields(content)
        var type = fields["TYPE"] ?: ""
        if (type.isBlank()) {
            // 无 TYPE 时默认按 exec（socket 路径无文件名可推断，exec 为主用场景）
            type = "exec"
        }
        AIDevLogger.i("ShizukuBridge", "dispatch type=$type")
        return when (type) {
            "exec" -> runBlocking { computeExec(fields["COMMAND"] ?: "") }
            else -> runBlocking { computeLog(fields) }
        }
    }

    private suspend fun handleRequest(requestDir: File, resultDir: File, processingFile: File) {
        val origName = processingFile.name.removeSuffix(".processing")
        val resFile = File(resultDir, origName)

        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("ShizukuBridge", "read processing file failed", it) }
            .getOrNull() ?: return
        val fields = parseFields(content)

        var type = fields["TYPE"] ?: ""
        if (type.isBlank()) {
            // 请求文件缺 TYPE 时按文件名兜底，避免被误判为 log 请求而返回 logcat（假成功）
            type = when {
                origName.startsWith("exec_") || origName.startsWith("hb_") -> "exec"
                else -> ""
            }
        }
        AIDevLogger.i("ShizukuBridge", "Processing: type=$type, file=$origName")

        val out = when (type) {
            "exec" -> computeExec(fields["COMMAND"] ?: "")
            else -> computeLogToFile(resFile, fields)
        }
        atomicWriteText(resFile, out)
        processingFile.delete()
    }

    private fun parseFields(content: String): Map<String, String> =
        content.lines().associate {
            val parts = it.split("=", limit = 2)
            parts[0] to if (parts.size > 1) parts[1] else ""
        }

    private val ALLOWED_COMMAND_PREFIXES = listOf(
        "pm ", "input ", "svc ", "dumpsys ", "cmd ", "cp ", "am ", "monkey "
    )

    private fun isCommandAllowed(command: String): Boolean {
        val trimmed = command.trimStart()
        if (ALLOWED_COMMAND_PREFIXES.any { trimmed.startsWith(it) }) return true
        val allowedChars = setOf('/', '-', '_', '.', ' ', '=', '@', ':', '~', ';')
        return trimmed.all { it.isLetterOrDigit() || it in allowedChars }
    }

    private suspend fun computeExec(command: String): String {
        if (!isCommandAllowed(command)) {
            return "ERROR: 命令被安全策略拒绝（仅允许已知命令前缀或安全字符）\n"
        }
        AIDevLogger.i("ShizukuBridge", "Executing: $command")
        val result = ShizukuLogcat.executeCommand(command)
        return buildString {
            if (result.exitCode == 0) {
                append(result.stdout.ifBlank { "命令执行成功（无输出）" })
            } else {
                appendLine("ERROR: exit=${result.exitCode}")
                if (result.stderr.isNotBlank()) appendLine("stderr: ${result.stderr}")
                if (result.stdout.isNotBlank()) appendLine("stdout: ${result.stdout}")
            }
        }
    }

    private suspend fun computeLogToFile(resFile: File, fields: Map<String, String>): String {
        val follow = fields["FOLLOW"]?.isNotEmpty() == true
        if (follow) {
            atomicWriteText(resFile, "[持续监听中... 按 Ctrl+C 停止]\n")
            ShizukuLogcat.startLogStream(
                packageName = fields["PACKAGE"] ?: "com.aidev.six",
                level = fields["LEVEL"] ?: "",
                tag = fields["TAG"] ?: "",
                onLine = { line ->
                    runCatching { resFile.appendText("$line\n") }
                        .onFailure { AIDevLogger.w("ShizukuBridge", "append log line failed", it) }
                },
                onError = { err ->
                    runCatching { resFile.appendText("\nERROR: $err\n") }
                        .onFailure { AIDevLogger.w("ShizukuBridge", "append log error failed", it) }
                }
            )
            return "[持续监听中... 按 Ctrl+C 停止]\n"
        }
        return fetchLogs(fields)
    }

    private suspend fun computeLog(fields: Map<String, String>): String = fetchLogs(fields)

    private suspend fun fetchLogs(fields: Map<String, String>): String {
        val packageName = fields["PACKAGE"] ?: "com.aidev.six"
        val lineCount = fields["LINES"]?.toIntOrNull() ?: 200
        val level = fields["LEVEL"] ?: ""
        val tag = fields["TAG"] ?: ""
        val clear = fields["CLEAR"]?.isNotEmpty() == true
        if (clear) ShizukuLogcat.clearLogBuffer()
        return try {
            withTimeout(30_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    ShizukuLogcat.fetchLog(
                        packageName = packageName,
                        lines = lineCount,
                        level = level,
                        tag = tag
                    ) { result ->
                        result.onSuccess { logs ->
                            cont.resume(logs, onCancellation = null)
                        }.onFailure { e ->
                            AIDevLogger.e("ShizukuBridge", "fetchLog failed", e)
                            cont.resume("ERROR: ${e.message}\n", onCancellation = null)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            "ERROR: 请求超时\n"
        }
    }

    private fun atomicWriteText(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(text)
            tmp.renameTo(file)
        }.onFailure { AIDevLogger.w("ShizukuBridge", "atomicWriteText: ${file.name} failed", it) }
    }
}
