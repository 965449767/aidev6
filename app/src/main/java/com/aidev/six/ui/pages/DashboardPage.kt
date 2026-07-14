package com.aidev.six.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import com.aidev.six.PathConfig
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.context.ContextManager
import com.aidev.six.data.KnowledgeBaseRepository
import com.aidev.six.ui.components.ActionCard
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.InfoCard
import com.aidev.six.ui.components.MetricChip
import com.aidev.six.ui.components.StatusCard
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DevCenter 控制中心（Dashboard）：单屏、Card + 2 列 Grid，信息密度优先。
 * 见 rules/core/UI.md（Dashboard 优先、Card+Grid、只用 Token）。
 * 数据均为实时读取（fail-safe，读取失败回退占位），不阻塞主流程。
 */
@Composable
fun DashboardPage(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val home = PathConfig.aidevHome(context)

    val tasks = remember {
        runCatching {
            AgentTaskStore.loadState(File(PathConfig.tasksDir(context), "agent-tasks.json"))
        }.getOrDefault(emptyList())
    }
    val taskCount = tasks.size

    val metrics = remember {
        runCatching {
            val file = File(File(home, ".aidev-metrics"), "events.jsonl")
            if (file.exists()) {
                file.readLines().mapNotNull { line ->
                    runCatching { JSONObject(line) }.getOrNull()
                }.filter { it.optString("event") == "build" }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }
    val buildCount = metrics.size
    val lastBuildOk = metrics.lastOrNull()?.optBoolean("ok", true) ?: true

    val crashCount = remember {
        runCatching { File(home, ".aidev-crash-bridge").listFiles()?.size ?: 0 }.getOrDefault(0)
    }
    val knowledgeCount = remember {
        runCatching { KnowledgeBaseRepository(context).loadKnowledgeBase().sumOf { it.items.size } }.getOrDefault(0)
    }

    val aiRunning = remember { OpenCodeServerManager.isRunning() }

    val workflowPending = tasks.count { it.status == AgentTaskStatus.PENDING }
    val workflowRunning = tasks.count { it.status == AgentTaskStatus.RUNNING }
    val workflowSuccess = tasks.count { it.status == AgentTaskStatus.SUCCEEDED }
    val workflowFailed = tasks.count { it.status == AgentTaskStatus.FAILED }
    val workflowActiveStage = tasks.firstOrNull { it.status == AgentTaskStatus.RUNNING }?.let { stageOf(it.status) }
        ?: tasks.firstOrNull()?.let { stageOf(it.status) } ?: 0
    val workflowHasFailed = workflowFailed > 0

    // Context Manager（M3-1）：SQLite 代码索引统计，后台重建，fail-safe
    var indexStats by remember { mutableStateOf<ContextManager.IndexStats?>(null) }
    var reindexing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { indexStats = ContextManager(context).stats() }
        }
    }
    fun reindex() {
        if (reindexing) return
        reindexing = true
        scope.launch(Dispatchers.IO) {
            runCatching { indexStats = ContextManager(context).indexProject(indexRoot(context)) }
            reindexing = false
        }
    }
    val symbolTotal = indexStats?.total ?: 0

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        contentPadding = PaddingValues(Spacing.s16),
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("总览", "DevCenter 控制中心")
        }
        item(span = { GridItemSpan(2) }) {
            InfoCard(
                title = "Android Terminal Pro",
                subtitle = "当前项目 · Kotlin + Jetpack Compose · 单模块",
                icon = Icons.Rounded.SmartToy,
            )
        }
        item {
            StatusCard(
                label = "AI",
                value = if (aiRunning) "Ready" else "Stopped",
                icon = Icons.Rounded.SmartToy,
                accent = if (aiRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            )
        }
        item {
            StatusCard(
                label = "Tasks",
                value = taskCount.toString(),
                icon = Icons.Rounded.Assignment,
            )
        }
        item {
            StatusCard(
                label = "Build",
                value = if (buildCount > 0) buildCount.toString() else "—",
                icon = Icons.Rounded.Build,
                accent = if (buildCount == 0) MaterialTheme.colorScheme.outline
                else if (lastBuildOk) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        item {
            StatusCard(
                label = "Crash",
                value = crashCount.toString(),
                icon = Icons.Rounded.BugReport,
                accent = if (crashCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("快捷操作")
        }
        item {
            ActionCard(
                title = "分析工程",
                subtitle = "模块 / 调用图 · aidev-index",
                icon = Icons.Rounded.Analytics,
                onClick = { onExecuteCommand("aidev-index") },
            )
        }
        item {
            ActionCard(
                title = "生成组件",
                subtitle = "Activity/Fragment/VM · aidev-gen",
                icon = Icons.Rounded.AutoAwesome,
                onClick = { onExecuteCommand("aidev-gen") },
            )
        }
        item {
            ActionCard(
                title = "环境诊断",
                subtitle = "构建与运行环境 · aidev-doctor",
                icon = Icons.Rounded.HealthAndSafety,
                onClick = { onExecuteCommand("aidev-doctor") },
            )
        }
        item {
            ActionCard(
                title = "调试",
                subtitle = "崩溃根因定位 · aidev-logcat",
                icon = Icons.Rounded.BugReport,
                onClick = { onExecuteCommand("aidev-logcat") },
            )
        }
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("代码索引", "Context Manager · SQLite")
        }
        item {
            StatusCard(
                label = "符号",
                value = if (symbolTotal > 0) symbolTotal.toString() else "—",
                icon = Icons.Rounded.AutoAwesome,
                accent = if (symbolTotal > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            )
        }
        item {
            ActionCard(
                title = if (reindexing) "索引中…" else "重新索引",
                subtitle = "扫描项目源树",
                icon = Icons.Rounded.Refresh,
                onClick = { reindex() },
            )
        }
        item { MetricChip(label = "类", value = (indexStats?.classes ?: 0).toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "函数", value = (indexStats?.functions ?: 0).toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "组件", value = (indexStats?.components ?: 0).toString(), modifier = Modifier.fillMaxWidth()) }
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("实时指标")
        }
        item { MetricChip(label = "Tasks", value = taskCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Builds", value = buildCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Crashes", value = crashCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Knowledge", value = knowledgeCount.toString(), icon = Icons.Rounded.Book, modifier = Modifier.fillMaxWidth()) }
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("工作流引擎", "Task→Plan→Coding→Review→Verify→Git→Release")
        }
        item(span = { GridItemSpan(2) }) {
            PipelineRow(activeStage = workflowActiveStage, failed = workflowHasFailed)
        }
        item { MetricChip(label = "待办", value = workflowPending.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "运行", value = workflowRunning.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "成功", value = workflowSuccess.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "失败", value = workflowFailed.toString(), modifier = Modifier.fillMaxWidth()) }
        items(tasks.take(6), key = { it.definition.id }) { task -> WorkflowTaskCard(task) }
    }
}

/** 索引目标：优先 PRoot 开发家目录，回退到 aidev home。 */
private fun indexRoot(context: Context): File {
    val home = PathConfig.aidevHome(context)
    return listOf(
        File(home, "ubuntu-rootfs/home"),
        File(home, "ubuntu-rootfs"),
        home,
    ).first { it.isDirectory }
}

// ——— 工作流引擎（M3-4）：自我进化闭环可视化 ———

private val PIPELINE = listOf("Task", "Plan", "Coding", "Review", "Verify", "Git", "Release")

/** 把任务状态映射为流水线已完成阶段数（1..7）。 */
private fun stageOf(status: AgentTaskStatus): Int = when (status) {
    AgentTaskStatus.PENDING -> 1
    AgentTaskStatus.RUNNING -> 4
    AgentTaskStatus.SUCCEEDED -> 7
    AgentTaskStatus.FAILED -> 3
    AgentTaskStatus.CANCELLED -> 2
}

private fun formatTs(ts: Long): String =
    if (ts == 0L) "—" else runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts)) }.getOrDefault("—")

@Composable
private fun PipelineRow(activeStage: Int, failed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PIPELINE.forEachIndexed { i, stage ->
            val done = i < activeStage
            val current = i == activeStage - 1
            val color = when {
                current && failed -> MaterialTheme.colorScheme.error
                done || current -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Card(
                shape = RoundedCornerShape(Radius.button),
                colors = CardDefaults.cardColors(
                    containerColor = if (done || current) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(
                    stage,
                    modifier = Modifier.padding(horizontal = Spacing.s8, vertical = Spacing.s4),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done || current) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun WorkflowTaskCard(task: com.aidev.six.agent.AgentTaskRecord) {
    val status = task.status
    val stage = stageOf(status)
    val failed = status == AgentTaskStatus.FAILED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.s12)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(task.definition.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        AgentTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.secondary
                        AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                        AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(Spacing.s8))
            LinearProgressIndicator(
                progress = { stage / 7f },
                modifier = Modifier.fillMaxWidth(),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(Spacing.s4))
            Text(formatTs(task.lastUpdatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
