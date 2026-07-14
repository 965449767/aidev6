package com.aidev.six.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aidev.six.chat.ChatPart
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.chat.sendCodingPrompt
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.PathConfig
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class TaskType(val label: String) {
    BUG("Bug"), COMPOSE("Compose"), SHELL("Shell"), GENERAL("通用")
}

private val TASK_ICONS = mapOf(
    TaskType.BUG to Icons.Rounded.BugReport,
    TaskType.COMPOSE to Icons.Rounded.AutoAwesome,
    TaskType.SHELL to Icons.Rounded.Terminal,
    TaskType.GENERAL to Icons.Rounded.Description,
)

private val TEMPLATES = mapOf(
    TaskType.BUG to Triple(
        "修复：<一句话描述 bug>",
        "定位根因并做最小改动修复；补充能复现的回归测试；不改变无关行为。",
        "复现步骤可关闭；核心路径无回归；相关单测通过。",
    ),
    TaskType.COMPOSE to Triple(
        "实现：<UI 功能描述>",
        "仅 Compose UI 层 + ViewModel（StateFlow）；遵循 rules/UI.md 设计 Token（颜色/间距/圆角/字体）；禁止自制基础控件。",
        "UI 符合设计系统、无布局溢出/字号越界；编译通过；关键交互可点测。",
    ),
    TaskType.SHELL to Triple(
        "实现：<shell 能力描述>",
        "脚本置于 app 私有目录，经 /system/bin/sh <script> 调用；不直执行脚本；处理错误与边界。",
        "脚本幂等、有错误处理与日志；在真机/PRoot 验证通过。",
    ),
    TaskType.GENERAL to Triple(
        "实现：<目标>",
        "明确改动范围与约束。",
        "满足验收标准、无回归、编译通过。",
    ),
)

@Composable
fun PromptBuilderPage(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(TaskType.BUG) }
    var goal by remember { mutableStateOf(TEMPLATES[TaskType.BUG]!!.first) }
    var scopeText by remember { mutableStateOf(TEMPLATES[TaskType.BUG]!!.second) }
    var acceptance by remember { mutableStateOf(TEMPLATES[TaskType.BUG]!!.third) }
    var background by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiNote by remember { mutableStateOf<String?>(null) }
    var aiRaw by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun applyTemplate(t: TaskType) {
        val (g, s, a) = TEMPLATES[t]!!
        goal = g
        scopeText = s
        acceptance = a
    }

    fun buildBrief(): String = buildString {
        appendLine("# 任务书（${type.label}）")
        if (background.isNotBlank()) {
            appendLine()
            appendLine("## 背景")
            appendLine(background.trim())
        }
        appendLine()
        appendLine("## Goal")
        appendLine(goal.trim())
        appendLine()
        appendLine("## Scope")
        appendLine(scopeText.trim())
        appendLine()
        appendLine("## Acceptance")
        appendLine(acceptance.trim())
    }

    fun copyBrief() {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("task-brief", buildBrief()))
        copied = true
    }

    fun saveAsTask() {
        scope.launch {
            aiBusy = false
            copied = false
            val brief = buildBrief()
            val id = "task-${System.currentTimeMillis()}"
            val name = goal.trim().take(40).ifBlank { "未命名任务" }
            val rec = AgentTaskRecord(
                definition = AgentTaskDefinition(
                    id = id,
                    name = name,
                    description = brief,
                    command = "",
                    workingDirectory = PathConfig.workspaceDir(context).absolutePath,
                    tags = listOf("coding"),
                ),
                status = AgentTaskStatus.PENDING,
                startedAt = System.currentTimeMillis(),
            )
            val file = File(PathConfig.tasksDir(context), "agent-tasks.json")
            runCatching { AgentTaskStore.upsertTask(file, rec) }
            val prompt = buildString {
                appendLine("请按以下任务书实现代码：")
                appendLine(brief)
            }
            val result = sendCodingPrompt(context, "任务: $name", prompt, "/workspace")
            aiNote = "已保存为任务（构建进化 可见），并已把指令发给 OpenCode：$result"
        }
    }

    fun aiOptimize() {
        scope.launch {
            aiBusy = true
            aiNote = null
            aiRaw = null
            copied = false
            val client = OpenCodeClient("http://127.0.0.1:${OpenCodeServerManager.PORT}")
            val healthy = runCatching { client.health() }.getOrDefault(false)
            if (!healthy) {
                aiNote = "OpenCode 未运行或未响应（端口 ${OpenCodeServerManager.PORT}）。请先启动 OpenCode：在 AI 对话中拉起，或终端执行 `opencode serve --port ${OpenCodeServerManager.PORT}`。"
                aiBusy = false
                return@launch
            }
            val session = client.createSession("Prompt Builder") ?: run {
                aiNote = "创建 OpenCode 会话失败。"
                aiBusy = false
                return@launch
            }
            val prompt = buildString {
                appendLine("你是 Android 开发任务规划助手。基于任务类型与背景，产出结构化任务书，严格按下述三段 Markdown 标题输出：")
                appendLine("## Goal")
                appendLine("## Scope")
                appendLine("## Acceptance")
                appendLine()
                appendLine("任务类型：${type.label}")
                appendLine("当前草稿：")
                appendLine("Goal: ${goal.trim()}")
                appendLine("Scope: ${scopeText.trim()}")
                appendLine("Acceptance: ${acceptance.trim()}")
                if (background.isNotBlank()) appendLine("背景：${background.trim()}")
                appendLine()
                appendLine("请优化并补全三段内容，使其可经 skills/plan 直接落地。")
            }
            val ok = client.sendPromptAsync(session.id, prompt, null, null, null)
            if (!ok) {
                aiNote = "提交失败（OpenCode 未确认）。"
                aiBusy = false
                return@launch
            }
            repeat(40) {
                if (!isActive) return@repeat
                delay(1500)
                val text = client.listMessages(session.id).lastOrNull { it.role == "assistant" }
                    ?.parts?.filterIsInstance<ChatPart.Text>()?.joinToString("\n") { it.text }?.trim()
                if (!text.isNullOrBlank()) {
                    val g = extractSection(text, listOf("Goal", "目标"))
                    val s = extractSection(text, listOf("Scope", "范围"))
                    val a = extractSection(text, listOf("Acceptance", "验收"))
                    if (g != null && s != null && a != null) {
                        goal = g
                        scopeText = s
                        acceptance = a
                        aiNote = "AI 已优化任务书（Goal/Scope/Acceptance 已更新）。"
                    } else {
                        aiRaw = text
                        aiNote = "AI 已返回，但未识别标准三段结构，原文见下方，可手动采用。"
                    }
                    return@repeat
                }
            }
            if (aiNote == null) aiNote = "已提交 AI 优化，请在 OpenCode 会话查看回复。"
            aiBusy = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.s16).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
            Text("任务书生成器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            TaskType.entries.forEach { t ->
                val icon = TASK_ICONS[t]!!
                val selected = t == type
                OutlinedButton(
                    onClick = { type = t; applyTemplate(t) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.button),
                ) {
                    Icon(icon, null, modifier = Modifier.size(Spacing.s16), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.s4))
                    Text(t.label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedTextField(
            value = background,
            onValueChange = { background = it },
            label = { Text("背景（可选）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
        )
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Goal · 目标") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
        )
        OutlinedTextField(
            value = scopeText,
            onValueChange = { scopeText = it },
            label = { Text("Scope · 范围与约束") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
        )
        OutlinedTextField(
            value = acceptance,
            onValueChange = { acceptance = it },
            label = { Text("Acceptance · 验收标准") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 6,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            OutlinedButton(onClick = { aiOptimize() }, enabled = !aiBusy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.AutoAwesome, "AI 优化", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text(if (aiBusy) "优化中…" else "AI 优化任务书")
            }
            OutlinedButton(onClick = { copyBrief() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.CheckCircle, "复制", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text(if (copied) "已复制" else "复制任务书")
            }
        }
        OutlinedButton(onClick = { saveAsTask() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Send, "保存为任务", modifier = Modifier.size(Spacing.s16))
            Spacer(Modifier.width(Spacing.s4))
            Text("保存为任务并交给 AI 编码")
        }

        aiNote?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(Radius.card),
            ) {
                Text(it, modifier = Modifier.padding(Spacing.s12), style = MaterialTheme.typography.bodySmall)
            }
        }

        aiRaw?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(Radius.card),
            ) {
                Column(Modifier.padding(Spacing.s16), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Text("AI 返回原文", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun extractSection(text: String, aliases: List<String>): String? {
    for (a in aliases) {
        val r = Regex("##\\s*${Regex.escape(a)}\\s*\\n(.*?)(?=\\n##\\s|\\z)", RegexOption.DOT_MATCHES_ALL)
        val m = r.find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!m.isNullOrBlank()) return m
    }
    return null
}
