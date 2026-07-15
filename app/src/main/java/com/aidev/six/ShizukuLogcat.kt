package com.aidev.six

import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

sealed class ShizukuState {
    object NotInstalled : ShizukuState()
    object NotRunning : ShizukuState()
    object NotAuthorized : ShizukuState()
    object Ready : ShizukuState()
}

/**
 * 通过 Shizuku 执行 logcat 获取应用日志。
 * 不需要 root 权限，不需要 USB 调试，只需要 Shizuku 已授权。
 */
object ShizukuLogcat {

    private const val TAG = "ShizukuLogcat"
    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cancelScope() {
        ioScope.cancel()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** 反射获取 Shizuku.newProcess 方法（新版中为 private） */
    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get newProcess method", e)
            null
        }
    }

    /** 检查是否可用 */
    fun isAvailable(): Boolean {
        return Shizuku.pingBinder() &&
               Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               newProcessMethod != null
    }

    /** 获取 Shizuku 状态描述 */
    fun statusText(): String = when {
        !Shizuku.pingBinder() -> "Shizuku 未运行"
        Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED -> "Shizuku 未授权"
        newProcessMethod == null -> "Shizuku API 不兼容"
        else -> "可用"
    }

    /**
     * 执行 logcat 命令获取日志。
     * @param packageName 应用包名，为空则获取所有日志
     * @param lines 获取最近多少行，默认 500
     * @param filters 额外的过滤标签，如 "AIDEV:*"
     * @param level 日志级别过滤，如 "ERROR", "WARN", "INFO", "DEBUG", "VERBOSE"
     * @param tag 按标签过滤，如 "ActivityManager"
     * @param callback 结果回调（在主线程）
     */
    fun fetchLog(
        packageName: String = "",
        lines: Int = 500,
        filters: List<String> = emptyList(),
        level: String = "",
        tag: String = "",
        callback: (Result<String>) -> Unit
    ) {
        ioScope.launch {
            try {
                val cmd = buildLogcatCommand(packageName, lines, filters, follow = false, level = level, tag = tag)
                Log.d(TAG, "fetchLog: $cmd")

                if (isAvailable()) {
                    val result = tryFetchViaShizuku(cmd)
                    if (result.isSuccess) {
                        callback(result)
                        return@launch
                    }
                    Log.w(TAG, "Shizuku logcat failed, trying Runtime.exec fallback: ${result.exceptionOrNull()?.message}")
                }

                val fallback = tryFetchViaRuntime(cmd)
                callback(fallback)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to fetch log", e)
                callback(Result.failure(e))
            }
        }
    }

    /**
     * 专用崩溃抓取：优先读 crash 缓冲区（进程崩溃后依然留存 FATAL EXCEPTION，无需 --pid），
     * 再拼上 main 缓冲区作补充。不使用会被 logcat 误当 tag:priority 的 filters，交由调用方解析。
     */
    fun fetchCrashLog(lines: Int = 2000, callback: (Result<String>) -> Unit) {
        ioScope.launch {
            try {
                val n = lines.coerceIn(200, 5000)
                val cmd = "logcat -d -v threadtime -b crash -t $n 2>/dev/null; " +
                    "echo '---MAIN---'; logcat -d -v threadtime -b main -t $n 2>/dev/null"
                val result = if (isAvailable()) {
                    tryFetchViaShizuku(cmd).recoverCatching { tryFetchViaRuntime(cmd).getOrThrow() }
                } else {
                    tryFetchViaRuntime(cmd)
                }
                callback(result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                callback(Result.failure(e))
            }
        }
    }

    private suspend fun tryFetchViaShizuku(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        val method = newProcessMethod
        if (method == null) return@withContext Result.failure(RuntimeException("Shizuku newProcess 方法不可用"))
        try {
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? java.lang.Process
            if (process == null) return@withContext Result.failure(RuntimeException("无法创建 Shizuku 进程"))
            val output = process.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val error = process.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "Shizuku logcat exit=$exitCode, error=$error")
                Result.failure(RuntimeException("logcat 失败 (exit=$exitCode): $error"))
            } else {
                Result.success(output)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun tryFetchViaRuntime(cmd: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val output = proc.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val error = proc.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly()
            val exitCode = proc.exitValue()
            if (exitCode != 0) {
                val msg = if (error.isNotBlank()) error else "logcat 失败 (exit=$exitCode)"
                Result.failure(RuntimeException(msg))
            } else {
                Result.success(output)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * 持续监听日志（流式输出）。
     * 返回的 Process 需要调用方管理生命周期。
     */
    fun startLogStream(
        packageName: String = "",
        filters: List<String> = emptyList(),
        level: String = "",
        tag: String = "",
        onLine: (String) -> Unit,
        onError: (String) -> Unit
    ): java.lang.Process? {
        if (!isAvailable()) {
            onError("Shizuku 不可用: ${statusText()}")
            return null
        }

        return try {
            val cmd = buildLogcatCommand(packageName, 0, filters, follow = true, level = level, tag = tag)
            Log.d(TAG, "Starting stream: $cmd")

            val method = newProcessMethod ?: run {
                onError("Shizuku newProcess 方法不可用")
                return null
            }
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? java.lang.Process

            if (process == null) {
                onError("无法创建 Shizuku 进程")
                return null
            }

            ioScope.launch {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { onLine(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Log stream ended", e)
                }
            }

            process
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start log stream", e)
            onError(e.message ?: "启动日志流失败")
            null
        }
    }

    /** 构建 logcat 命令 */
    private fun buildLogcatCommand(
        packageName: String,
        lines: Int,
        filters: List<String>,
        follow: Boolean = false,
        level: String = "",
        tag: String = ""
    ): String {
        val sb = StringBuilder("logcat")
        val safePkg = packageName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        val safeTag = tag.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == '*' }

        if (safePkg.isNotBlank()) {
            sb.append(" \$(P=\$(pidof $safePkg 2>/dev/null); [ -n \"\$P\" ] && echo \"--pid=\$P\")")
        }
        if (lines > 0) {
            sb.append(" -t $lines")
        }
        if (level.isNotEmpty()) {
            if (safeTag.isNotEmpty()) {
                sb.append(" $safeTag:$level *:S")
            } else {
                sb.append(" *:$level")
            }
        } else if (safeTag.isNotEmpty()) {
            sb.append(" $safeTag:V *:S")
        }
        filters.forEach { sb.append(" $it") }
        sb.append(if (follow) " -v threadtime" else " -d -v threadtime")

        return sb.toString()
    }

    /** 清空 logcat 缓冲区 */
    fun clearLogBuffer() {
        if (!isAvailable()) return
        ioScope.launch {
            try {
                val method = newProcessMethod ?: return@launch
                val process = method.invoke(
                    null, arrayOf("sh", "-c", "logcat -c"), null, null
                ) as? java.lang.Process
                process?.let { p ->
                    p.inputStream.bufferedReader().use { it.readText() }
                    p.errorStream.bufferedReader().use { it.readText() }
                    if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly()
                }
                Log.d(TAG, "Log buffer cleared")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to clear log buffer", e)
            }
        }
    }

    fun checkState(context: android.content.Context): ShizukuState {
        val installed = runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        if (!installed) return ShizukuState.NotInstalled

        if (!Shizuku.pingBinder()) return ShizukuState.NotRunning

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShizukuState.NotAuthorized
        }

        return ShizukuState.Ready
    }

    suspend fun executeCommand(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "executeCommand: $cmd")
        val shizukuResult = tryExecuteViaShizuku(cmd)
        if (shizukuResult.exitCode != -1) return@withContext shizukuResult

        Log.w(TAG, "Shizuku exec failed (exit=${shizukuResult.exitCode}), trying Runtime.exec fallback")
        val fallback = tryExecuteViaRuntime(cmd)
        if (fallback.exitCode != -1) return@withContext fallback

        val combined = shizukuResult.stdout + shizukuResult.stderr
        if (combined.isNotBlank()) return@withContext shizukuResult

        shizukuResult
    }

    private suspend fun tryExecuteViaShizuku(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        val method = newProcessMethod
        if (method == null) {
            return@withContext ShellResult("", "Shizuku API 不兼容", -1)
        }
        var process: java.lang.Process? = null
        try {
            withTimeout(60_000L) {
                process = method.invoke(
                    null,
                    arrayOf("sh", "-c", cmd),
                    null,
                    null
                ) as? java.lang.Process

                if (process == null) {
                    return@withTimeout ShellResult("", "无法创建 Shizuku 进程", -1)
                }

                val stdout = process.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val stderr = process.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Shizuku process did not finish in 30s, destroying")
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                val exitCode = process.exitValue()
                Log.d(TAG, "Shizuku exec result: exitCode=$exitCode")
                ShellResult(stdout, stderr, exitCode)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Shizuku exec timed out after 60s: $cmd")
            ShellResult("", "命令执行超时（60 秒）", -1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed", e)
            ShellResult("", "Shizuku 执行异常: ${e.message ?: "未知错误"}", -1)
        } finally {
            // Ensure the Shizuku process is always reaped, even on timeout/exception
            // (createProcess runs inside withTimeout, so a timeout would otherwise leak it).
            try {
                process?.destroyForcibly()
                process?.waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun tryExecuteViaRuntime(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stdout = proc.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val stderr = proc.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly()
            val exitCode = proc.exitValue()
            Log.d(TAG, "Runtime exec result: exitCode=$exitCode")
            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            Log.e(TAG, "Runtime exec failed", e)
            ShellResult("", "Runtime 执行异常: ${e.message ?: "未知错误"}", -1)
        }
    }

    fun executeFireAndForget(cmd: String) {
        val method = newProcessMethod ?: return
        Log.d(TAG, "Fire-and-forget: $cmd")
        ioScope.launch {
            try {
                val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? java.lang.Process
                if (p != null) {
                    p.inputStream.bufferedReader().use { it.readText() }
                    p.errorStream.bufferedReader().use { it.readText() }
                    if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Fire-and-forget failed", e)
            }
        }
    }

    fun pmInstallErrorHint(result: ShellResult): String {
        val stderr = result.stderr
        val stdout = result.stdout
        return when {
            result.exitCode == 0 -> "安装成功"
            stderr.contains("INSTALL_FAILED_ALREADY_EXISTS") -> "应用已存在，可尝试卸载后重装"
            stderr.contains("INSUFFICIENT_STORAGE") -> "存储空间不足"
            stderr.contains("INVALID_APK") -> "APK 文件无效或损坏"
            stderr.contains("NO_MATCHING_ABIS") -> "APK 架构与此设备不兼容"
            stderr.contains("PERMISSION_MODEL_DOWNGRADE") -> "权限限制，请尝试手动安装"
            stderr.contains("USER_RESTRICTED") -> "用户限制，请检查工作资料或多用户设置"
            stderr.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> "已安装版本更高，降级被拒绝"
            else -> "安装失败 (exit=${result.exitCode})，请尝试手动安装"
        }
    }
}
