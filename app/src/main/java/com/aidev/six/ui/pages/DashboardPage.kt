package com.aidev.six.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aidev.six.PathConfig
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.data.KnowledgeBaseRepository
import com.aidev.six.ui.components.ActionCard
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.InfoCard
import com.aidev.six.ui.components.MetricChip
import com.aidev.six.ui.components.StatusCard
import com.aidev.six.ui.theme.Spacing
import org.json.JSONObject
import java.io.File

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
            AppSectionHeader("实时指标")
        }
        item { MetricChip(label = "Tasks", value = taskCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Builds", value = buildCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Crashes", value = crashCount.toString(), modifier = Modifier.fillMaxWidth()) }
        item { MetricChip(label = "Knowledge", value = knowledgeCount.toString(), icon = Icons.Rounded.Book, modifier = Modifier.fillMaxWidth()) }
    }
}
