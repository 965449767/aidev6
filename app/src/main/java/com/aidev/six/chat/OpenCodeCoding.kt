package com.aidev.six.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aidev.six.AIDevLogger
import com.aidev.six.LoopTrace
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.PathConfig
import java.io.File

/**
 * 把编码指令（自由写代码 / 修复 / 任务书）发给 OpenCode：
 * 优先注入终端 TUI（用户在终端运行 opencode 的真实端口），失败回退到当前最活跃会话。
 * 无论成功与否都把命令存盘 + 复制剪贴板，保证可用。
 */
suspend fun sendCodingPrompt(
    context: Context,
    title: String,
    prompt: String,
    workingDir: String? = null,
    provider: String? = null,
    model: String? = null,
): String {
    LoopTrace.section("Coding: $title")
    val backendOk = OpenCodeServerManager.ensureRunning(context)
    if (!backendOk) return "OpenCode 后端未就绪：${OpenCodeServerManager.lastDiagnostic}"
    val tuiPort = runCatching {
        File(PathConfig.aidevHome(context), ".aidev-opencode-port").readText().trim().toIntOrNull()
    }.getOrNull()?.takeIf { it in 1..65535 } ?: 4096
    val client = OpenCodeClient("http://127.0.0.1:$tuiPort")
    if (!workingDir.isNullOrBlank()) client.directory = workingDir
    val cmdFile = File("/sdcard/self-evolution-prompt-${title.hashCode().toUInt()}.txt")
    runCatching { cmdFile.writeText(prompt) }
    runCatching { copyToClipboard(context, prompt) }
    LoopTrace.log("Coding", "已存命令到 $cmdFile 并复制剪贴板")
    val appendResp = client.appendTuiPrompt(prompt)
    if (appendResp != null) {
        val submitResp = client.submitTuiPrompt()
        return "已把指令填入终端 OpenCode 命令行（端口 $tuiPort，append=$appendResp, submit=$submitResp），请查看终端（必要时回车提交）。命令已存 $cmdFile 并复制剪贴板"
    }
    val sessions = client.listSessions()
    val target = sessions.firstOrNull() ?: client.createSession(title)
        ?: return "OpenCode 后端无可用会话，命令已存 $cmdFile 并复制剪贴板，请手动粘贴到终端 OpenCode"
    val ok = client.sendPromptAsync(target.id, prompt, provider, model, null)
    val base = if (ok) "已发送指令到 OpenCode 会话「${target.title}」(端口 $tuiPort)" else "发送失败"
    return "$base。命令已存 $cmdFile 并复制剪贴板，可直接粘贴到终端 OpenCode"
}

private fun copyToClipboard(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        ?.setPrimaryClip(ClipData.newPlainText("AIDev", text))
}
