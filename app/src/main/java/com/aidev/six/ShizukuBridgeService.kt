package com.aidev.six

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
object ShizukuBridgeService : BridgeService("ShizukuBridge") {

    private const val BRIDGE_DIR = ".aidev-shizuku-bridge"
    private const val REQUEST_DIR = "request"
    private const val RESULT_DIR = "result"

    private var requestDir: File? = null
    private var resultDir: File? = null

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

    private suspend fun handleRequest(requestDir: File, resultDir: File, processingFile: File) {
        val origName = processingFile.name.removeSuffix(".processing")
        val resFile = File(resultDir, origName)

        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("ShizukuBridge", "read processing file failed", it) }
            .getOrNull() ?: return
        val fields = content.lines().associate {
            val parts = it.split("=", limit = 2)
            parts[0] to if (parts.size > 1) parts[1] else ""
        }

        var type = fields["TYPE"] ?: ""
        if (type.isBlank()) {
            // 请求文件缺 TYPE 时按文件名兜底，避免被误判为 log 请求而返回 logcat（假成功）
            type = when {
                origName.startsWith("exec_") || origName.startsWith("hb_") -> "exec"
                else -> ""
            }
        }
        AIDevLogger.i("ShizukuBridge", "Processing: type=$type, file=$origName")

        when (type) {
            "exec" -> handleExecRequest(resFile, fields["COMMAND"] ?: "")
            else -> handleLogRequest(resFile, fields)
        }
        processingFile.delete()
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

    private suspend fun handleExecRequest(resFile: File, command: String) {
        if (!isCommandAllowed(command)) {
            atomicWriteText(resFile, "ERROR: 命令被安全策略拒绝（仅允许已知命令前缀或安全字符）\n")
            return
        }
        AIDevLogger.i("ShizukuBridge", "Executing: $command")
        val result = ShizukuLogcat.executeCommand(command)
        val output = buildString {
            if (result.exitCode == 0) {
                append(result.stdout.ifBlank { "命令执行成功（无输出）" })
            } else {
                appendLine("ERROR: exit=${result.exitCode}")
                if (result.stderr.isNotBlank()) appendLine("stderr: ${result.stderr}")
                if (result.stdout.isNotBlank()) appendLine("stdout: ${result.stdout}")
            }
        }
        atomicWriteText(resFile, output)
    }

    private suspend fun handleLogRequest(resFile: File, fields: Map<String, String>) {
        val packageName = fields["PACKAGE"] ?: "com.aidev.six"
        val lineCount = fields["LINES"]?.toIntOrNull() ?: 200
        val follow = fields["FOLLOW"]?.isNotEmpty() == true
        val level = fields["LEVEL"] ?: ""
        val tag = fields["TAG"] ?: ""
        val clear = fields["CLEAR"]?.isNotEmpty() == true

        if (clear) {
            ShizukuLogcat.clearLogBuffer()
        }

        if (follow) {
            atomicWriteText(resFile, "[持续监听中... 按 Ctrl+C 停止]\n")
            ShizukuLogcat.startLogStream(
                packageName = packageName,
                level = level,
                tag = tag,
                onLine = { line ->
                    runCatching { resFile.appendText("$line\n") }
                        .onFailure { AIDevLogger.w("ShizukuBridge", "append log line failed", it) }
                },
                onError = { err ->
                    runCatching { resFile.appendText("\nERROR: $err\n") }
                        .onFailure { AIDevLogger.w("ShizukuBridge", "append log error failed", it) }
                }
            )
        } else {
            val logs = try {
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
            atomicWriteText(resFile, logs)
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
