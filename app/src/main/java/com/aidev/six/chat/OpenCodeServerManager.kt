package com.aidev.six.chat

import android.content.Context
import android.util.Log
import com.aidev.six.PathConfig
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 管理本机 opencode serve 后端（宇宙 A，固定端口 4096）。
 *
 * 聊天页进入时调用 [ensureRunning] 幂等拉起：若 4096 已健康直接复用（与终端 TUI 共享同一
 * 后端，attach 模型）；否则在宇宙 A 内后台启动 `opencode serve --port 4096 --hostname
 * 127.0.0.1`（cwd=/workspace），随后轮询 /global/health 直到就绪。
 *
 * 失败时把可诊断的原因写入 [lastDiagnostic]，ChatPanel 直接展示给用户（例如宇宙A 未就绪、
 * opencode 未安装、serve 进程输出的报错）。
 */
object OpenCodeServerManager {
    private const val TAG = "OpenCodeServer"
    const val PORT = 4096

    @Volatile private var process: Process? = null
    @Volatile var lastDiagnostic: String = ""
        private set

    private val client = OpenCodeClient()
    private val output = StringBuilder()

    /** @return true 表示后端已就绪（健康）。失败原因见 [lastDiagnostic]。 */
    suspend fun ensureRunning(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        lastDiagnostic = ""
        // 1) 已有后端（终端 TUI / 之前起的 serve）→ 直接复用
        if (client.health()) return@withContext true

        // 2) 宇宙 A 就绪性检查
        val rootfs = PathConfig.agentRootfs(ctx)
        if (!File(rootfs, ".aidev-rootfs-ready").isFile) {
            lastDiagnostic = "宇宙A（Ubuntu）未初始化。请先在【终端】完成环境部署（deploy-dev-env / 安装 Ubuntu），再回到 AI 对话。"
            return@withContext false
        }

        // 3) 拉起 serve
        startProcess(ctx)
        // 轮询健康：serve 冷启动可能较慢
        repeat(30) {
            delay(1000)
            if (client.health()) return@withContext true
            if (process?.isAlive == false) {
                // 进程提前退出，多半是 opencode 未安装或启动即报错
                lastDiagnostic = buildDiagnostic()
                return@withContext false
            }
        }
        if (client.health()) return@withContext true
        lastDiagnostic = buildDiagnostic().ifBlank {
            "opencode serve 启动超时（30s）。请在终端运行 `opencode serve --port $PORT` 手动确认是否可用。"
        }
        false
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun buildDiagnostic(): String {
        val tail = synchronized(output) { output.toString().takeLast(600).trim() }
        return when {
            tail.contains("not found", true) || tail.contains("command not found", true) ->
                "宇宙A 内未安装 opencode。请在【终端】运行 install-aitool 安装后重试。"
            tail.isNotBlank() -> "opencode serve 启动失败：\n$tail"
            else -> "opencode serve 未能启动（无输出）。请在终端手动运行 `opencode serve --port $PORT` 排查。"
        }
    }

    private fun startProcess(ctx: Context) {
        if (process?.isAlive == true) return
        synchronized(output) { output.setLength(0) }
        val workspace = PathConfig.workspaceDir(ctx).apply { mkdirs() }
        val opts = ProotLauncher.Options(
            rootfs = PathConfig.agentRootfs(ctx).absolutePath,
            cwd = "/workspace",
            binds = listOf(ProotLauncher.ProotBind(workspace.absolutePath, "/workspace")),
            env = mapOf(
                "PATH" to "/root/.opencode/bin:/host-home/dev-env/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME" to "/root",
            ),
            redirectErrorStream = true,
        )
        // 若 opencode 不在 PATH，sh 会输出 "command not found" 并退出，便于诊断。
        val cmd = "command -v opencode >/dev/null 2>&1 || { echo 'opencode: command not found'; exit 127; }; " +
            "exec opencode serve --port $PORT --hostname 127.0.0.1"
        process = runCatching { ProotLauncher.start(ctx, cmd, opts) }
            .onFailure {
                Log.e(TAG, "opencode serve 启动失败", it)
                lastDiagnostic = "无法启动宇宙A 进程：${it.message}"
            }
            .getOrNull()
        // 后台读取 serve 输出用于诊断（不阻塞）
        process?.let { p ->
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            synchronized(output) { output.appendLine(line) }
                            line = r.readLine()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }
}
