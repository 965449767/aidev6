package com.aidev.six.ui.pages

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.aidev.six.PathConfig
import com.aidev.six.ProjectCommands
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskRunner
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.navigation.DialogType
import com.aidev.six.navigation.LocalDialogManager
import com.aidev.six.ui.components.AppActionRow
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.AppSectionTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun ServerPanel(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dialogManager = LocalDialogManager.current

    val batteryIgnored = remember { batteryIgnored(context) }
    val taskCount = remember { taskCount(context) }
    val ubuntuInstalled = remember { ubuntuInstalled(context) }
    val opencodeInstalled = remember { opencodeInstalled(context) }

    val taskStateFile = remember(context) { File(PathConfig.tasksDir(context), "agent-tasks.json") }
    val taskRunner = remember { AgentTaskRunner() }
    val taskRecords = remember { mutableStateListOf<AgentTaskRecord>() }
    val selectedTaskId = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(taskStateFile) {
        taskRecords.clear()
        taskRecords.addAll(AgentTaskStore.loadState(taskStateFile))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AppSectionHeader("服务器中心", "移动 Linux 服务器状态与 AI 服务入口")

        Spacer(Modifier.height(8.dp))

        AppSectionTitle("运行状态")
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusRow("电池优化", if (batteryIgnored) "已忽略" else "受限制", batteryIgnored, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusRow("任务记录", "$taskCount 个", taskCount > 0, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusRow("Ubuntu", if (ubuntuInstalled) "可用" else "未安装", ubuntuInstalled, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusRow("OpenCode", if (opencodeInstalled) "可能已安装" else "未检测到", opencodeInstalled, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        AppSectionTitle("服务操作")
        AppActionRow("监听端口", "查看当前本地服务端口", onClick = { onExecuteCommand("list-listen-ports") })
        HorizontalDivider()
        AppActionRow("访问诊断", "检查 127.0.0.1 服务", onClick = { onExecuteCommand("check-local-server 3000") })
        HorizontalDivider()
        AppActionRow("任务中心", "查看服务日志和任务", onClick = { onExecuteCommand("aidev-agent-log") })
        HorizontalDivider()
        AppActionRow("SFTP 传输", "远程文件传输管理", onClick = { dialogManager.show(DialogType.SFtpTransfer) })

        Spacer(Modifier.height(8.dp))

        AppSectionTitle("AI 助手")
        Row(modifier = Modifier.fillMaxWidth()) {
            AppActionRow("安装 OpenCode", "调用官方安装入口", onClick = { onExecuteCommand("install-aitool") }, modifier = Modifier.weight(1f), compact = true)
            Spacer(Modifier.width(8.dp))
            AppActionRow("检测环境", "AI/Web 通信与工具链", onClick = { onExecuteCommand("check-dev-env\naidev-net-explain") }, modifier = Modifier.weight(1f), compact = true)
        }
        HorizontalDivider()
        AppActionRow("OpenCode 日志", "查看 OpenCode 任务输出", onClick = { onExecuteCommand("aidev-agent-log") })

        Spacer(Modifier.height(8.dp))

        AppSectionTitle("Agent 开发任务")
        val projectTemplates = remember(context) { ProjectCommands.taskTemplates(PathConfig.workspaceDir(context)) }
        projectTemplates.forEachIndexed { index, template ->
            AppActionRow(template.name, template.description, onClick = {
                val definition = AgentTaskDefinition(
                    id = "agent-task-${System.currentTimeMillis()}",
                    name = template.name,
                    description = template.description,
                    command = template.command,
                    workingDirectory = PathConfig.workspaceDir(context).absolutePath,
                    tags = template.tags
                )
                taskRunner.runTask(definition, taskStateFile) { record ->
                    taskRecords.removeAll { it.definition.id == record.definition.id }
                    taskRecords.add(0, record)
                }
            })
            if (index < projectTemplates.lastIndex) HorizontalDivider()
        }
        HorizontalDivider()
        if (taskRecords.isEmpty()) {
            InfoNote("暂无 Agent 任务记录")
        } else {
            taskRecords.forEach { record ->
                AgentTaskRow(
                    task = record,
                    isSelected = selectedTaskId.value == record.definition.id,
                    onToggle = { selectedTaskId.value = if (selectedTaskId.value == record.definition.id) null else record.definition.id },
                    onRetry = {
                        val definition = record.definition.copy(id = "agent-retry-${System.currentTimeMillis()}")
                        taskRunner.runTask(definition, taskStateFile) { task ->
                            taskRecords.removeAll { it.definition.id == task.definition.id }
                            taskRecords.add(0, task)
                        }
                    },
                    onCancel = {
                        taskRunner.cancelTask(record.definition.id)
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        AppSectionTitle("自我进化闭环")
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusRow("宇宙 A (OpenCode)", if (ubuntuInstalled) "就绪" else "未安装", ubuntuInstalled, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusRow("宇宙 B (编译器)", if (compilerInstalled(context)) "就绪" else "未安装", compilerInstalled(context), Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        InfoNote("共享盘 workspace: ${PathConfig.workspaceDir(context).absolutePath}")
        val buildResult = remember { lastBuildResult(context) }
        if (buildResult.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            InfoNote("最近构建: $buildResult")
        }
        val crash = remember { lastCrashSummary(context) }
        if (crash.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            InfoNote("最近崩溃: $crash")
        }
        Spacer(Modifier.height(8.dp))
        AppActionRow("提交构建请求", "在宇宙 B 编译默认项目并安装/拉起", onClick = { submitBuildRequest(context, "MyAndroidProject") })
        HorizontalDivider()
        AppActionRow("查看崩溃报告", "读取最新 MCP 崩溃报告", onClick = { onExecuteCommand("aidev-crash-report") })

        Spacer(Modifier.height(16.dp))

        AppSectionTitle("设计原则")
        InfoCard("服务类任务不依赖前台终端页面")
        InfoNote("用 task-run 启动下载、AI 后端、Web 前端和构建任务，从任务中心查看日志。")
        InfoNote("同机浏览器访问 127.0.0.1；局域网访问需服务监听 0.0.0.0，HyperOS 不能限制后台网络。")
        InfoNote("AI 能力逐步集中至此页面，避免继续散落在设置和命令菜单中。")

        Spacer(Modifier.height(24.dp))
    }
}

private fun batteryIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ubuntuInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/.aidev-rootfs-ready").exists()

private fun taskCount(context: Context): Int =
    File(context.filesDir, "home/tasks").listFiles { f -> f.name.endsWith(".meta") }?.size ?: 0

private fun opencodeInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/root/.opencode/bin/opencode").exists() ||
        File(context.filesDir, "home/.opencode/bin/opencode").exists()

private fun compilerInstalled(context: Context): Boolean =
    File(context.filesDir, "home/compiler_rootfs/.aidev-rootfs-ready").exists()

private fun lastBuildResult(context: Context): String {
    val dir = File(context.filesDir, "home/.aidev-build-bridge")
    val f = dir.listFiles { _, n -> n.startsWith("result-") && n.endsWith(".json") }
        ?.maxByOrNull { it.lastModified() } ?: return ""
    return runCatching { org.json.JSONObject(f.readText()).optString("message", "") }.getOrNull() ?: ""
}

private fun lastCrashSummary(context: Context): String {
    val f = File(context.filesDir, "home/.aidev-mcp/latest.json")
    if (!f.isFile) return ""
    return runCatching {
        val j = org.json.JSONObject(f.readText())
        val stack = j.optJSONArray("stack")
        "fatal=${j.optString("fatal", "")} (${stack?.length() ?: 0} 行)"
    }.getOrNull() ?: ""
}

private fun submitBuildRequest(context: Context, project: String) {
    val dir = File(context.filesDir, "home/.aidev-build-bridge").apply { mkdirs() }
    val id = System.currentTimeMillis()
    val json = org.json.JSONObject().apply {
        put("id", "$id")
        put("project", project)
        put("flavor", "debug")
        put("autoInstall", true)
        put("autoLaunch", true)
    }
    runCatching { File(dir, "req-$id.json").writeText(json.toString()) }
}

@Composable
private fun AgentTaskRow(
    task: AgentTaskRecord,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.definition.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = when (task.status) {
                        AgentTaskStatus.PENDING -> "待执行"
                        AgentTaskStatus.RUNNING -> "执行中"
                        AgentTaskStatus.SUCCEEDED -> "已完成"
                        AgentTaskStatus.FAILED -> "失败"
                        AgentTaskStatus.CANCELLED -> "已取消"
                    },
                    color = when (task.status) {
                        AgentTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.secondary
                        AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                        AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    text = if (isSelected) "收起" else "详情",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onToggle),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "重试",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
        }
        if (task.definition.description.isNotBlank()) {
            Text(task.definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (isSelected) {
            Spacer(Modifier.height(4.dp))
            Text(task.log.ifBlank { "暂无输出" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (task.status == AgentTaskStatus.RUNNING) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onCancel),
                )
            }
            if (task.exitCode >= 0) {
                Spacer(Modifier.height(4.dp))
                Text("exit=$${task.exitCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, positive: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

@Composable
private fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}
