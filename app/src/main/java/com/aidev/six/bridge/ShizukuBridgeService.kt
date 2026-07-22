package com.aidev.six.bridge

import com.aidev.six.AIDevLogger
import com.aidev.six.shizuku.ShizukuLogcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
object ShizukuBridgeService : BridgeService("ShizukuBridge") {

    private const val BRIDGE_DIR = ".aidev-shizuku-bridge"
    private const val REQUEST_DIR = "request"
    private const val RESULT_DIR = "result"

    @Volatile private var requestDir: File? = null
    @Volatile private var resultDir: File? = null
    @Volatile private var activeFollowProcess: java.lang.Process? = null
    private const val FOLLOW_LOG_MAX = 2 * 1024 * 1024

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
             it.name.startsWith("exec_") || it.name.startsWith("hb_") ||
             it.name.startsWith("install_")) &&
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
            "install" -> runBlocking { computeInstall(fields["APK_PATH"] ?: "") }
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
                origName.startsWith("install_") -> "install"
                else -> ""
            }
        }
        AIDevLogger.i("ShizukuBridge", "Processing: type=$type, file=$origName")

        val out = when (type) {
            "exec" -> computeExec(fields["COMMAND"] ?: "")
            "install" -> computeInstall(fields["APK_PATH"] ?: "")
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

    /**
     * 前缀白名单：仅允许以这些前缀开头的命令（P0-2 收紧）。
     * 覆盖安全设备内省/控制动词；危险动词（reboot/rm/mv/dd/mount/chmod 等）一律不在列表内。
     * 新增 ls（只读列举 /data/anr、/data/tombstones 等，供 aidev-anr/aidev-tombstone）。
     */
    private val ALLOWED_COMMAND_PREFIXES = listOf(
        "pm ", "input ", "svc ", "dumpsys ", "cmd ", "cp ", "am ", "monkey ",
        "settings ", "wm ", "getprop", "setprop", "logcat", "date ", "reset ", "ls "
    )

    /** 只读动词后允许接的安全管道下游（仅用于截断/过滤，不执行任意程序）。 */
    private val SAFE_PIPE_READERS = setOf(
        "head", "tail", "grep", "awk", "sed", "wc", "tr", "sort", "cut", "cat", "strings"
    )

    /**
     * 校验管道拆分后的每一段：首段须匹配前缀白名单；后续段只允许安全只读读取器，
     * 且不得再出现任何注入元字符。这样 dumpsys ... | head -40 可放行，
     * 而 am start ... ; rm -rf / 之类仍被拒。
     */
    private fun isPipelineAllowed(command: String): Boolean {
        val segments = command.split('|')
        if (segments.isEmpty()) return false
        // 第一段：必须是白名单前缀动词
        val head = segments.first().trim()
        val headOk = ALLOWED_COMMAND_PREFIXES.any { p ->
            val base = p.trimEnd()
            head == base || head.startsWith(p)
        }
        if (!headOk) return false
        // 后续段：仅允许安全读取器，且整段不得含其余注入元字符
        val remainingMetachars = charArrayOf(';', '&', '$', '`', '(', ')', '<', '>', '\n', '\\')
        for (i in 1 until segments.size) {
            val seg = segments[i].trim()
            if (seg.isEmpty()) return false
            val reader = seg.split(Regex("\\s+")).first()
            if (reader !in SAFE_PIPE_READERS) return false
            if (seg.any { it in remainingMetachars }) return false
        }
        return true
    }

    /** 注入类 shell 元字符：无论前缀是否合法，出现即拒绝（防 am start ... ; rm -rf / 等注入）。 */
    private val FORBIDDEN_SHELL_METACHARS = charArrayOf(';', '&', '$', '`', '(', ')', '<', '>', '\n', '\\')

    internal fun isCommandAllowed(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return false
        // 含管道时按安全管道规则校验（允许 dumpsys|head 等只读内省）
        if ('|' in trimmed) return isPipelineAllowed(trimmed)
        val matchesPrefix = ALLOWED_COMMAND_PREFIXES.any { p ->
            val base = p.trimEnd()
            trimmed == base || trimmed.startsWith(p)
        }
        if (!matchesPrefix) return false
        if (trimmed.any { it in FORBIDDEN_SHELL_METACHARS }) return false
        return true
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

    /**
     * 安装 APK：
     * 1. 检查 Shizuku 是否已授权，未授权则提前报错
     * 2. 复制 APK 到 /data/local/tmp/（shell 进程可写，system_server 可读，绕过 FUSE SELinux 限制）
     * 3. pm install -r -d --user 0（简单路径安装，不依赖 session，避免 HyperOS 弹窗阻塞）
     * 4. 清理临时文件（; rm -f 确保无论 pm 成功/失败均执行）
     */
    private suspend fun computeInstall(apkPath: String): String = withContext(Dispatchers.IO) {
        val file = File(apkPath)
        if (!file.isFile) return@withContext "ERROR: 文件不存在: $apkPath"

        if (!ShizukuLogcat.isAvailable()) {
            return@withContext "ERROR: Shizuku 未授权或不可用（${ShizukuLogcat.statusText()}），无法执行特权安装"
        }

        val absPath = file.absolutePath.replace("'", "'\\''")
        val tmpName = "aidev-install-${file.name.hashCode()}-${System.nanoTime()}.apk"
        val tmpPath = "/data/local/tmp/$tmpName"
        val size = file.length()
        AIDevLogger.i("ShizukuBridge", "Installing APK: $apkPath ($size bytes)")

        // cp + pm install + cleanup（用 ; 确保即使 pm 失败也清理临时文件）
        val cmd = "cp '$absPath' '$tmpPath' && pm install -r -d --user 0 '$tmpPath'; rm -f '$tmpPath'"

        val result = ShizukuLogcat.executeCommand(cmd)
        buildString {
            if (result.exitCode == 0) {
                append("→ 安装成功")
                if (result.stdout.isNotBlank()) appendLine("\n${result.stdout.take(500)}")
            } else {
                appendLine("ERROR: 安装失败 (exit=${result.exitCode})")
                appendLine(ShizukuLogcat.pmInstallErrorHint(result))
                if (result.stderr.isNotBlank()) appendLine("stderr: ${result.stderr.take(1000)}")
                if (result.stdout.isNotBlank()) appendLine("stdout: ${result.stdout.take(1000)}")
            }
        }
    }

    private suspend fun computeLogToFile(resFile: File, fields: Map<String, String>): String {
        val follow = fields["FOLLOW"]?.isNotEmpty() == true
        if (follow) {
            atomicWriteText(resFile, "[持续监听中... 按 Ctrl+C 停止]\n")
            // 停掉上一个 follow 流，避免 Shizuku 进程与协程无限累积。
            activeFollowProcess?.destroyForcibly()
            activeFollowProcess = ShizukuLogcat.startLogStream(
                packageName = fields["PACKAGE"] ?: "com.aidev.six",
                level = fields["LEVEL"] ?: "",
                tag = fields["TAG"] ?: "",
                onLine = { line -> appendCapped(resFile, "$line\n") },
                onError = { err -> appendCapped(resFile, "\nERROR: $err\n") }
            )
            return "[持续监听中... 按 Ctrl+C 停止]\n"
        }
        return fetchLogs(fields)
    }

    private fun appendCapped(file: File, text: String) {
        runCatching {
            file.appendText(text)
            // 防止 follow 日志文件无限增长：超过上限时截取最后 2000 行。
            // 使用 readLines（仅超限时触发一次），避免原 readText().takeLast() 每行全量读入内存。
            if (file.length() > FOLLOW_LOG_MAX) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(2000).joinToString("\n"))
            }
        }.onFailure { AIDevLogger.w("ShizukuBridge", "append log line failed", it) }
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
