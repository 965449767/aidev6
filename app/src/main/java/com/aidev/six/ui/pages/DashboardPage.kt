package com.aidev.six.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aidev.six.PathConfig
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.ui.components.ActionCard
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.InfoCard
import com.aidev.six.ui.components.MetricChip
import com.aidev.six.ui.components.StatusCard
import com.aidev.six.ui.theme.Spacing
import java.io.File

/**
 * DevCenter 控制中心（Dashboard）：单屏、Card + 2 列 Grid，信息密度优先。
 * 见 rules/core/UI.md（Dashboard 优先、Card+Grid、只用 Token）。
 */
@Composable
fun DashboardPage(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tasks = remember {
        runCatching {
            AgentTaskStore.loadState(File(PathConfig.tasksDir(context), "agent-tasks.json"))
        }.getOrDefault(emptyList())
    }
    val taskCount = tasks.size
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
            StatusCard(label = "Tasks", value = taskCount.toString(), icon = Icons.Rounded.Assignment)
        }
        item {
            StatusCard(label = "Build", value = "—", icon = Icons.Rounded.Build)
        }
        item {
            StatusCard(label = "Crash", value = "—", icon = Icons.Rounded.BugReport)
        }
        item(span = { GridItemSpan(2) }) {
            AppSectionHeader("快捷操作")
        }
        item {
            ActionCard(
                title = "分析工程",
                subtitle = "模块 / 调用图",
                icon = Icons.Rounded.Analytics,
                onClick = { onExecuteCommand("aidev-index") },
            )
        }
        item {
            ActionCard(
                title = "生成任务",
                subtitle = "需求 → 计划",
                icon = Icons.Rounded.AddTask,
                onClick = { },
            )
        }
        item {
            ActionCard(
                title = "审查代码",
                subtitle = "diff 风险评审",
                icon = Icons.Rounded.RateReview,
                onClick = { },
            )
        }
        item {
            ActionCard(
                title = "调试",
                subtitle = "崩溃根因定位",
                icon = Icons.Rounded.BugReport,
                onClick = { onExecuteCommand("aidev-logcat") },
            )
        }
        item(span = { GridItemSpan(2) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                MetricChip(label = "Tasks", value = taskCount.toString(), modifier = Modifier.weight(1f))
                MetricChip(label = "Bugs", value = "—", modifier = Modifier.weight(1f))
                MetricChip(label = "Knowledge", value = "—", modifier = Modifier.weight(1f))
            }
        }
    }
}
