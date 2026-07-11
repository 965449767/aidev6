package com.aidev.six.ui.pages

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.aidev.six.PathConfig
import com.aidev.six.ProjectCommands
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskPlan
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskStep
import com.aidev.six.agent.AgentTaskTemplate
import com.aidev.six.agent.AgentTaskRunner
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.agent.BuildRequestTracker
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.delay
import java.io.File
import com.aidev.six.PreferencesManager

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
    val buildTracker = remember { BuildRequestTracker() }
    val taskRecords = remember { mutableStateListOf<AgentTaskRecord>() }
    val selectedTaskId = remember { mutableStateOf<String?>(null) }
    val upsertRecord: (AgentTaskRecord) -> Unit = { record ->
        taskRecords.removeAll { it.definition.id == record.definition.id }
        taskRecords.add(0, record)
    }

    // 实时反映 agent-tasks.json：构建进度由 BuildBridgeService 统一写入该文件（无论请求来自
    // 手动按钮还是宇宙 A 终端的 aidev-build-request），这里轮询磁盘即可让两条路径过程一致可见。
    // 对「卡死」的 RUNNING（超过 90s 未更新，说明进程已随应用重启被打断）就地收敛为失败。
    LaunchedEffect(taskStateFile) {
        fun reconcile(): List<AgentTaskRecord> {
            val now = System.currentTimeMillis()
            val loaded = AgentTaskStore.loadState(taskStateFile)
            var mutated = false
            val fixed = loaded.map { rec ->
                if (rec.status == AgentTaskStatus.RUNNING && now - rec.lastUpdatedAt > 90_000L) {
                    mutated = true
                    rec.copy(
                        status = AgentTaskStatus.FAILED,
                        exitCode = -1,
                        finishedAt = now,
                        log = rec.log + "\n\n✖ 构建被中断（长时间无进度更新），请点击「重试」重新提交。"
                    )
                } else rec
            }
            if (mutated) AgentTaskStore.saveState(taskStateFile, fixed)
            return fixed
        }
        taskRecords.clear()
        taskRecords.addAll(reconcile())
        while (true) {
            delay(1200)
            val disk = reconcile()
            val signature = disk.map { it.definition.id to it.lastUpdatedAt }
            if (signature != taskRecords.map { it.definition.id to it.lastUpdatedAt }) {
                taskRecords.clear()
                taskRecords.addAll(disk)
            }
        }
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
        AppActionRow("Android 闭环", "按构建→测试的顺序执行一组开发步骤", onClick = {
            val plan = AgentTaskPlan.fromTemplate(
                name = "Android 闭环",
                description = "构建并验证 Android 项目",
                template = AgentTaskTemplate(
                    id = "android-loop-${System.currentTimeMillis()}",
                    name = "Android 闭环",
                    description = "构建并验证 Android 项目",
                    steps = listOf(
                        AgentTaskStep("构建", "./gradlew assembleDebug"),
                        AgentTaskStep("测试", "./gradlew test")
                    )
                )
            )
            taskRunner.runPlan(
                plan = plan,
                workingDirectory = PathConfig.workspaceDir(context).absolutePath,
                stateFile = taskStateFile,
                tags = listOf("android", "loop")
            ) { record ->
                taskRecords.removeAll { it.definition.id == record.definition.id }
                taskRecords.add(0, record)
            }
        })
        HorizontalDivider()
        if (taskRecords.isEmpty()) {
            InfoNote("暂无 Agent 任务记录")
        } else {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    "任务记录 ${taskRecords.size} 条",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "清空全部",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable {
                        taskRecords.clear()
                        selectedTaskId.value = null
                        AgentTaskStore.clearTasks(taskStateFile)
                    },
                )
            }
            taskRecords.forEach { record ->
                AgentTaskRow(
                    task = record,
                    isSelected = selectedTaskId.value == record.definition.id,
                    onToggle = { selectedTaskId.value = if (selectedTaskId.value == record.definition.id) null else record.definition.id },
                    onDelete = {
                        taskRecords.removeAll { it.definition.id == record.definition.id }
                        if (selectedTaskId.value == record.definition.id) selectedTaskId.value = null
                        AgentTaskStore.removeTask(taskStateFile, record.definition.id)
                    },
                    onRetry = {
                        // 构建/自进化任务由 BuildRequestTracker（文件桥）驱动，不能用 taskRunner 跑 shell，
                        // 否则点击无反应。按标签路由到正确的执行器。
                        if (record.definition.tags.any { it == "build" || it == "self-evolution" }) {
                            buildTracker.submit(
                                context = context,
                                project = projectOf(record),
                                stateFile = taskStateFile,
                                autonomous = PreferencesManager(context).selfEvolutionAutonomous,
                                onUpdate = upsertRecord
                            )
                        } else {
                            val definition = record.definition.copy(id = "agent-retry-${System.currentTimeMillis()}")
                            taskRunner.runTask(definition, taskStateFile) { task ->
                                taskRecords.removeAll { it.definition.id == task.definition.id }
                                taskRecords.add(0, task)
                            }
                        }
                    },
                    onCancel = {
                        // 构建任务在 BuildBridgeService（文件桥+PRoot 进程）里跑，taskRunner 管不到，
                        // 必须直接杀对应请求的 Gradle/PRoot 进程。id 形如 build-<ts>。
                        if (record.definition.tags.any { it == "build" || it == "self-evolution" }) {
                            com.aidev.six.BuildBridgeService.cancel(record.definition.id.removePrefix("build-"))
                        } else {
                            taskRunner.cancelTask(record.definition.id)
                        }
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
        val crashState = remember { mutableStateOf(lastCrashSummary(context)) }
    val autonomousOn = remember { mutableStateOf(PreferencesManager(context).selfEvolutionAutonomous) }
        // 提交后轮询 tracker 的最新崩溃回流（F04：闭环回流实时可见）
        LaunchedEffect(Unit) {
            while (true) {
                val latest = buildTracker.latestCrash
                if (latest.isNotBlank() && latest != crashState.value) crashState.value = latest
                delay(1000)
            }
        }
        if (crashState.value.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            InfoNote("最近崩溃回流: ${crashState.value}")
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自我进化自治模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "开启后崩溃自动触发下一轮构建（需宇宙 A 自动改码），闭环自转",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = autonomousOn.value,
                onCheckedChange = {
                    autonomousOn.value = it
                    PreferencesManager(context).selfEvolutionAutonomous = it
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // 改码模型选择：额度耗尽时 opencode 会静默空返回（不报错），无法自动识别，
        // 故让用户看对话内容自行判断，并在此手动切换模型。
        val modelExpanded = remember { mutableStateOf(false) }
        val selectedModel = remember { mutableStateOf(PreferencesManager(context).selfEvolutionModel) }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("改码模型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "宇宙 A 用哪个免费模型改码；额度耗尽请看下方对话后切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Box {
                Text(
                    text = selectedModel.value.removePrefix("opencode/") + " ▾",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { modelExpanded.value = true }
                        .padding(8.dp)
                )
                DropdownMenu(
                    expanded = modelExpanded.value,
                    onDismissRequest = { modelExpanded.value = false }
                ) {
                    com.aidev.six.Constants.SELF_EVOLUTION_MODELS.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.removePrefix("opencode/")) },
                            onClick = {
                                selectedModel.value = model
                                PreferencesManager(context).selfEvolutionModel = model
                                writeSelfEvolutionModel(context, model)
                                modelExpanded.value = false
                            }
                        )
                    }
                }
            }
        }
        // 首次进入即把当前选择落盘，保证守护读得到
        LaunchedEffect(Unit) { writeSelfEvolutionModel(context, selectedModel.value) }

        // 改码对话实时查看：守护把 opencode 每轮改码的完整对话写共享盘，这里滚动展示。
        val convExpanded = remember { mutableStateOf(false) }
        val convLog = remember { mutableStateOf(readConversationLog(context)) }
        LaunchedEffect(Unit) {
            while (true) {
                val latest = readConversationLog(context)
                if (latest != convLog.value) convLog.value = latest
                delay(1500)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { convExpanded.value = !convExpanded.value }.padding(vertical = 4.dp)
        ) {
            Text(
                "改码对话",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (convLog.value.isBlank()) "暂无" else if (convExpanded.value) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (convExpanded.value) {
            Text(
                text = convLog.value.ifBlank { "尚无改码对话。触发一次崩溃并运行 aidev-self-evolution 后，这里会显示 OpenCode 的提示与回复；若某模型只显示空回复即为额度耗尽，请上方切换模型。" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(rememberScrollState())
            )
        }

        Spacer(Modifier.height(8.dp))

        // 构建项目选择：workspace 下可能有多个 OpenCode 项目，让用户挑要编译哪个，
        // 不再硬编码默认 MyAndroidProject。
        val projectList = remember { listProjects(context) }
        val projectExpanded = remember { mutableStateOf(false) }
        val selectedProject = remember {
            mutableStateOf(projectList.firstOrNull { it == "MyAndroidProject" } ?: projectList.first())
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("构建项目", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "选择 workspace 下要编译的项目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Box {
                Text(
                    text = selectedProject.value + " ▾",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { projectExpanded.value = true }
                        .padding(8.dp)
                )
                DropdownMenu(
                    expanded = projectExpanded.value,
                    onDismissRequest = { projectExpanded.value = false }
                ) {
                    projectList.forEach { proj ->
                        DropdownMenuItem(
                            text = { Text(proj) },
                            onClick = {
                                selectedProject.value = proj
                                projectExpanded.value = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        AppActionRow("提交构建请求", "在宇宙 B 编译所选项目并安装/拉起", onClick = {
            buildTracker.submit(
                context = context,
                project = selectedProject.value,
                stateFile = taskStateFile,
                autonomous = autonomousOn.value,
                onUpdate = upsertRecord
            )
        })
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

private fun writeSelfEvolutionModel(context: Context, model: String) {
    runCatching {
        val dir = File(context.filesDir, "home/workspace/.aidev-loop")
        dir.mkdirs()
        File(dir, "se-config.json").writeText("{\"model\": \"$model\"}\n")
    }
}

private fun readConversationLog(context: Context, maxChars: Int = 6000): String {
    val f = File(context.filesDir, "home/workspace/.aidev-loop/conversation.log")
    if (!f.isFile) return ""
    return runCatching {
        val t = f.readText()
        if (t.length > maxChars) "…（省略较早内容）\n" + t.takeLast(maxChars) else t
    }.getOrDefault("")
}

/** 枚举 workspace 下的可构建项目（含 settings.gradle(.kts) 或 gradlew 的目录）。空则回退默认名。 */
private fun listProjects(context: Context): List<String> {
    val ws = PathConfig.workspaceDir(context)
    val dirs = ws.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.filter {
            File(it, "settings.gradle.kts").isFile ||
                File(it, "settings.gradle").isFile ||
                File(it, "gradlew").isFile
        }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()
    return if (dirs.isEmpty()) listOf("MyAndroidProject") else dirs
}

/** 从构建任务记录里还原提交时的项目名（命令形如 `aidev-build-request --project <name>`）。 */
private fun projectOf(record: AgentTaskRecord): String {
    val cmd = record.definition.command
    val after = cmd.substringAfter("--project ", "")
    return after.trim().ifBlank { "MyAndroidProject" }
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

@Composable
private fun AgentTaskRow(
    task: AgentTaskRecord,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
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
                if (task.status == AgentTaskStatus.RUNNING) {
                    val currentPhase = task.steps.firstOrNull { it.status == AgentTaskStatus.RUNNING }?.name
                        ?: task.steps.lastOrNull { it.status == AgentTaskStatus.SUCCEEDED }?.name
                    if (!currentPhase.isNullOrBlank()) {
                        Text(
                            text = "当前阶段：$currentPhase",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    text = if (isSelected) "收起" else "详情",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onToggle).padding(6.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "重试",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onRetry).padding(6.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "删除",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
                )
            }
        }
        if (task.definition.description.isNotBlank()) {
            Text(task.definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (isSelected) {
            if (task.steps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                task.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. ${step.name} · ${stepStatusLabel(step.status)}" +
                            if (step.exitCode >= 0) " (exit=${step.exitCode})" else "",
                        color = when (step.status) {
                            AgentTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.secondary
                            AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                            AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
                Text("exit=${task.exitCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun stepStatusLabel(status: AgentTaskStatus): String = when (status) {
    AgentTaskStatus.PENDING -> "待执行"
    AgentTaskStatus.RUNNING -> "执行中"
    AgentTaskStatus.SUCCEEDED -> "已完成"
    AgentTaskStatus.FAILED -> "失败"
    AgentTaskStatus.CANCELLED -> "已取消"
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
