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
import androidx.compose.material.icons.rounded.ContentCopy
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
    var aiNote by remember { mutableStateOf<String?>(null) }
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
        aiNote = "已保存为任务（构建进化 可见）。到终端 OpenCode 把任务书贴给它即可开始编码。"
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
            OutlinedButton(onClick = { copyBrief() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.ContentCopy, "复制", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text(if (copied) "已复制" else "复制任务书")
            }
            OutlinedButton(onClick = { saveAsTask() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.CheckCircle, "保存为任务", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text("保存为任务")
            }
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
    }
}
