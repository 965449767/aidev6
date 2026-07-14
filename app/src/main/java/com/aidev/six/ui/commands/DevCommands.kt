package com.aidev.six.ui.commands

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Router

enum class CommandCategory { ANALYZE, GENERATE, DIAGNOSE, DEBUG }

data class DevCommand(
    val id: String,
    val cmd: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: CommandCategory,
)

/** 单一命令源：Dashboard 与 宇宙A 共用，避免入口重复、命令字符串散落。 */
val DEV_COMMANDS: List<DevCommand> = listOf(
    DevCommand("index", "aidev-index", "分析工程", "模块 / 调用图 · aidev-index", Icons.Rounded.Analytics, CommandCategory.ANALYZE),
    DevCommand("gen", "aidev-gen", "生成组件", "Activity/Fragment/VM · aidev-gen", Icons.Rounded.AutoAwesome, CommandCategory.GENERATE),
    DevCommand("doctor", "aidev-doctor", "环境诊断", "构建与运行环境 · aidev-doctor", Icons.Rounded.HealthAndSafety, CommandCategory.DIAGNOSE),
    DevCommand("logcat", "aidev-logcat", "调试", "崩溃根因定位 · aidev-logcat", Icons.Rounded.BugReport, CommandCategory.DEBUG),
    DevCommand("ports", "list-listen-ports", "监听端口", "查看当前本地服务端口", Icons.Rounded.Router, CommandCategory.DIAGNOSE),
    DevCommand("net", "check-dev-env\naidev-net-explain", "检测环境", "AI/Web 通信与工具链", Icons.Rounded.NetworkCheck, CommandCategory.DIAGNOSE),
    DevCommand("agentlog", "aidev-agent-log", "OpenCode 日志", "查看 OpenCode 任务输出", Icons.Rounded.Description, CommandCategory.DEBUG),
)

fun commandsByCategory(vararg cats: CommandCategory): List<DevCommand> {
    val set = cats.toSet()
    return DEV_COMMANDS.filter { it.category in set }
}
