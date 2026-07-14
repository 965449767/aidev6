package com.aidev.six.ui.pages

import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import com.aidev.six.PathConfig
import com.aidev.six.PreferencesManager
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskRunner
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.agent.BuildRequestTracker
import com.aidev.six.BuildPreflight
import com.aidev.six.agent.DeployRequestTracker
import com.aidev.six.navigation.DialogType
import com.aidev.six.navigation.DialogManagerState
import com.aidev.six.navigation.LocalDialogManager
import com.aidev.six.ui.components.AppSectionHeader
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.aidev.six.Constants
import com.aidev.six.AIDevLogger
import com.aidev.six.LoopTrace
import com.aidev.six.DeployBridgeService
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import java.io.File

import org.json.JSONObject

private val TAB_LABELS = listOf("总览", "宇宙 A", "宇宙 B", "调试", "设备")

private data class NavItem(val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem("总览", Icons.Rounded.Dashboard),
    NavItem("宇宙 A", Icons.Rounded.SmartToy),
    NavItem("宇宙 B", Icons.Rounded.AccountTree),
    NavItem("调试", Icons.Rounded.BugReport),
    NavItem("设备", Icons.Rounded.PhoneAndroid),
)

@Composable
fun ServerPanel(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dialogManager = LocalDialogManager.current
    val taskRunner = remember { AgentTaskRunner }
    val buildTracker = remember { BuildRequestTracker() }
    val selectedTab = remember { mutableIntStateOf(0) }
    var showGitReview by remember { mutableStateOf(false) }
    var showPromptBuilder by remember { mutableStateOf(false) }

    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    @Composable
    fun renderTab(tab: Int) {
        when (tab) {
            0 -> DashboardPage(onExecuteCommand, onOpenGitReview = { showGitReview = true }, onOpenPromptBuilder = { showPromptBuilder = true })
            1 -> UniverseATab(onExecuteCommand, dialogManager)
            2 -> UniverseBTab(onExecuteCommand, taskRunner, buildTracker, dialogManager)
            3 -> DebugCenterPage(onExecuteCommand)
            4 -> AdbExplorerPage()
        }
    }

    if (showGitReview) {
        GitReviewPage(onBack = { showGitReview = false })
    } else if (showPromptBuilder) {
        PromptBuilderPage(onBack = { showPromptBuilder = false })
    } else if (isWide) {
        Row(modifier = modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                NAV_ITEMS.forEachIndexed { index, item ->
                    NavigationRailItem(
                        selected = selectedTab.intValue == index,
                        onClick = { selectedTab.intValue = index },
                        icon = { Icon(item.icon, null) },
                        label = { Text(item.label) },
                    )
                }
            }
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AppSectionHeader("服务器中心", "移动 Linux 服务器状态与 AI 服务入口")
                Crossfade(targetState = selectedTab.intValue, label = "server-tab") { tab -> renderTab(tab) }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            AppSectionHeader("服务器中心", "移动 Linux 服务器状态与 AI 服务入口")

            TabRow(selectedTabIndex = selectedTab.intValue, containerColor = MaterialTheme.colorScheme.surface) {
                TAB_LABELS.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab.intValue == index,
                        onClick = { selectedTab.intValue = index },
                        text = { Text(label) }
                    )
                }
            }

            Crossfade(targetState = selectedTab.intValue, label = "server-tab") { tab -> renderTab(tab) }
        }
    }
}

// ─────────────────── 宇宙 A：AI / Agent 环境 ───────────────────

@Composable
private fun UniverseATab(
    onExecuteCommand: (String) -> Unit,
    dialogManager: com.aidev.six.navigation.DialogManagerState,
) {
    val context = LocalContext.current

    val batteryIgnored = remember { batteryIgnored(context) }
    val opencodeInstalled = remember { opencodeInstalled(context) }

    // 改码模型
    val modelExpanded = remember { mutableStateOf(false) }
    val selectedModel = remember { mutableStateOf(PreferencesManager(context).selfEvolutionModel) }
    LaunchedEffect(Unit) { writeSelfEvolutionModel(context, selectedModel.value) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(Spacing.s8))

        // ── 环境状态 ──
        SectionCard(title = "环境状态") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = "电池优化 · " + if (batteryIgnored) "已忽略" else "受限制",
                    tone = if (batteryIgnored) StatusTone.Ok else StatusTone.Warn
                )
                StatusChip(
                    text = "OpenCode · " + if (opencodeInstalled) "已安装" else "未安装",
                    tone = if (opencodeInstalled) StatusTone.Ok else StatusTone.Neutral
                )
            }
            if (!opencodeInstalled) {
                Spacer(Modifier.height(Spacing.s12))
                Button(
                    onClick = { onExecuteCommand("curl -fsSL https://opencode.ai/install | bash") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("安装 OpenCode")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "curl -fsSL https://opencode.ai/install | bash",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(Spacing.s16))

        // ── 改码模型 ──
        SectionCard(title = "改码模型") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择改码模型", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "宇宙 A 用哪个免费模型改码；额度耗尽请看对话后切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Spacing.s12))
                Box {
                    OutlinedButton(onClick = { modelExpanded.value = true }) {
                        Text(selectedModel.value.removePrefix("opencode/") + " ▾")
                    }
                    DropdownMenu(expanded = modelExpanded.value, onDismissRequest = { modelExpanded.value = false }) {
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
        }

        Spacer(Modifier.height(Spacing.s16))

        // ── 诊断与工具 ──
        SectionCard(title = "诊断与工具") {
            ListRow(
                label = "监听端口",
                desc = "查看当前本地服务端口",
                onClick = { onExecuteCommand("list-listen-ports") }
            )
            HorizontalDivider()
            ListRow(
                label = "检测环境",
                desc = "AI/Web 通信与工具链",
                onClick = { onExecuteCommand("check-dev-env\naidev-net-explain") }
            )
            HorizontalDivider()
            ListRow(
                label = "OpenCode 日志",
                desc = "查看 OpenCode 任务输出",
                onClick = { onExecuteCommand("aidev-agent-log") }
            )
            HorizontalDivider()
            ListRow(
                label = "SFTP 传输",
                desc = "远程文件传输管理",
                onClick = { dialogManager.show(DialogType.SFtpTransfer) }
            )
        }

        Spacer(Modifier.height(Spacing.s24))
    }
}

// ─────────────────── 宇宙 B：编译 / 构建环境 ───────────────────

@Composable
private fun UniverseBTab(
    onExecuteCommand: (String) -> Unit,
    taskRunner: AgentTaskRunner,
    buildTracker: BuildRequestTracker,
    dialogManager: DialogManagerState,
) {
    val context = LocalContext.current
    val taskStateFile = remember(context) { File(PathConfig.tasksDir(context), "agent-tasks.json") }
    val taskRecords = remember { mutableStateListOf<AgentTaskRecord>() }
    val selectedTaskId = remember { mutableStateOf<String?>(null) }
    val showClearConfirm = remember { mutableStateOf(false) }
    val deleteTarget = remember { mutableStateOf<AgentTaskRecord?>(null) }
    val cancelTarget = remember { mutableStateOf<AgentTaskRecord?>(null) }

    val submitting = remember { mutableStateOf(false) }
    LaunchedEffect(submitting.value) {
        if (submitting.value) { delay(5000); submitting.value = false }
    }

    val scope = rememberCoroutineScope()
    val fixSending = remember { mutableStateOf(false) }
    val fixMsg = remember { mutableStateOf("") }
    val healthMessages = remember { mutableStateListOf<String>() }
    val healthLoading = remember { mutableStateOf(false) }

    val upsertRecord: (AgentTaskRecord) -> Unit = { record ->
        taskRecords.removeAll { it.definition.id == record.definition.id }
        taskRecords.add(0, record)
    }

    if (taskRecords.isEmpty()) {
        runCatching { taskRecords.addAll(AgentTaskStore.loadState(taskStateFile)) }
    }

    // 任务列表轮询
    LaunchedEffect(taskStateFile) {
        fun reconcile(): List<AgentTaskRecord> {
            val now = System.currentTimeMillis()
            val loaded = runCatching { AgentTaskStore.loadState(taskStateFile) }.getOrDefault(emptyList())
            var mutated = false
            val fixed = loaded.map { rec ->
                if (rec.status == AgentTaskStatus.RUNNING && now - rec.lastUpdatedAt > 90_000L) {
                    mutated = true
                    rec.copy(status = AgentTaskStatus.FAILED, exitCode = -1, finishedAt = now,
                        log = rec.log + "\n\n✖ 构建被中断（长时间无进度更新），请点击「重试」重新提交。")
                } else rec
            }
            if (mutated) runCatching { AgentTaskStore.saveState(taskStateFile, fixed) }
            return fixed
        }
        taskRecords.clear()
        taskRecords.addAll(reconcile())
        while (true) {
            delay(1200)
            runCatching {
                val disk = reconcile()
                val sig = disk.map { it.definition.id to it.lastUpdatedAt }
                if (sig != taskRecords.map { it.definition.id to it.lastUpdatedAt }) {
                    taskRecords.clear(); taskRecords.addAll(disk)
                }
            }
        }
    }

    // 弹窗
    if (showClearConfirm.value) {
        ConfirmDialog("清空全部任务", "确认清空所有 ${taskRecords.size} 条任务记录？此操作不可撤销。",
            onConfirm = { taskRecords.clear(); selectedTaskId.value = null; runCatching { AgentTaskStore.clearTasks(taskStateFile) }; showClearConfirm.value = false },
            onDismiss = { showClearConfirm.value = false })
    }
    deleteTarget.value?.let { t ->
        ConfirmDialog("删除任务", "确认删除「${t.definition.name}」？",
            onConfirm = { taskRecords.removeAll { it.definition.id == t.definition.id }; if (selectedTaskId.value == t.definition.id) selectedTaskId.value = null; runCatching { AgentTaskStore.removeTask(taskStateFile, t.definition.id) }; deleteTarget.value = null },
            onDismiss = { deleteTarget.value = null })
    }
    cancelTarget.value?.let { t ->
        ConfirmDialog("取消任务", "确认取消「${t.definition.name}」？正在运行的进程将被终止。",
            onConfirm = {
                when {
                    t.definition.tags.contains("deploy") -> com.aidev.six.DeployBridgeService.cancel(t.definition.id.removePrefix("deploy-"))
                    t.definition.tags.any { it == "build" || it == "self-evolution" } -> com.aidev.six.BuildBridgeService.cancel(t.definition.id.removePrefix("build-"))
                    else -> taskRunner.cancelTask(t.definition.id)
                }
                cancelTarget.value = null
            },
            onDismiss = { cancelTarget.value = null })
    }

    // 项目列表
    val projectList = remember { mutableStateOf(listProjects(context)) }
    LaunchedEffect(Unit) {
        while (true) { delay(10_000); runCatching { val n = listProjects(context); if (n != projectList.value) projectList.value = n } }
    }
    val projectExpanded = remember { mutableStateOf(false) }
    val selectedProject = remember { mutableStateOf(projectList.value.firstOrNull { it == "MyAndroidProject" } ?: projectList.value.firstOrNull() ?: "MyAndroidProject") }

    val showExportDialog = remember { mutableStateOf(false) }
    val showImportDialog = remember { mutableStateOf(false) }

    if (showExportDialog.value) {
        ProjectExportDialog(
            projectName = selectedProject.value,
            defaultOutDir = File(PreferencesManager(context).externalAidevDir, "exports"),
            onDismiss = { showExportDialog.value = false },
        )
    }
    if (showImportDialog.value) {
        ProjectImportDialog(
            onDismiss = { showImportDialog.value = false },
            onImported = { projectList.value = listProjects(context) },
        )
    }

    // 部署状态（安装/拉起按钮的可用产物）
    val deployTracker = remember { DeployRequestTracker() }
    val deploySubmitting = remember { mutableStateOf(false) }
    LaunchedEffect(deploySubmitting.value) {
        if (deploySubmitting.value) { delay(5000); deploySubmitting.value = false }
    }
    val deployArtifact = remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                val a = lastBuildArtifact(context, selectedProject.value)
                if (a != deployArtifact.value) deployArtifact.value = a
            }
            delay(3000)
        }
    }

    // 闭环状态
    val buildResult = remember { mutableStateOf(lastBuildResult(context)) }
    val crashState = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            runCatching {
                val b = lastBuildResult(context); if (b != buildResult.value) buildResult.value = b
                val c = buildTracker.latestCrash; if (c.isNotBlank() && c != crashState.value) crashState.value = c
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(Spacing.s8))

        // ── 环境状态 ──
        SectionCard(title = "环境状态") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = "Ubuntu rootfs · " + if (ubuntuInstalled(context)) "可用" else "未安装",
                    tone = if (ubuntuInstalled(context)) StatusTone.Ok else StatusTone.Neutral
                )
                StatusChip(
                    text = "编译器 · " + if (compilerInstalled(context)) "就绪" else "未安装",
                    tone = if (compilerInstalled(context)) StatusTone.Ok else StatusTone.Neutral
                )
            }
        }

        Spacer(Modifier.height(Spacing.s16))

        // ── 构建 ──
        SectionCard(title = "构建") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择要编译的项目", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("workspace 下可构建的项目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(Spacing.s12))
                Box {
                    OutlinedButton(onClick = { projectExpanded.value = true }) {
                        Text(selectedProject.value + " ▾")
                    }
                    DropdownMenu(expanded = projectExpanded.value, onDismissRequest = { projectExpanded.value = false }) {
                        projectList.value.forEach { proj ->
                            DropdownMenuItem(text = { Text(proj) }, onClick = { selectedProject.value = proj; projectExpanded.value = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s12))
            OutlinedButton(
                onClick = { dialogManager.show(DialogType.ProjectScaffold) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("新建项目（脚手架 + 开发前可视化预览）")
            }
            Spacer(Modifier.height(Spacing.s12))
            Button(
                onClick = {
                    if (submitting.value) return@Button
                    submitting.value = true
                    val id = "build-${System.currentTimeMillis()}"
                    upsertRecord(AgentTaskRecord(
                        definition = AgentTaskDefinition(id = id, name = "构建 ${selectedProject.value}",
                            description = "宇宙 B 编译 → 产出 APK（安装/拉起由部署黑盒接力）",
                            command = "aidev-build-request --project ${selectedProject.value}",
                            workingDirectory = PathConfig.workspaceDir(context).absolutePath,
                            tags = listOf("build", "self-evolution")),
                        status = AgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis(),
                        log = "已提交构建请求，等待宇宙 B 调度…"))
                    buildTracker.submit(context = context, project = selectedProject.value, stateFile = taskStateFile,
                        autonomous = PreferencesManager(context).selfEvolutionAutonomous,
                        onUpdate = { r -> upsertRecord(r); if (r.status != AgentTaskStatus.RUNNING) submitting.value = false })
                },
                enabled = !submitting.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (submitting.value) "提交中…" else "提交构建请求")
            }
            Spacer(Modifier.height(Spacing.s8))
            val hasBuild = remember(selectedProject.value) {
                File(PathConfig.workspaceDir(context), "${selectedProject.value}/app/build").isDirectory
            }
            InfoNote(
                if (hasBuild)
                    "检测到上次构建产物：默认走增量编译（更快）。若改了依赖 / gradle / SDK 版本，建议先 clean 再构建。"
                else
                    "首次构建：走全量编译（会下载 Gradle 分发与依赖，首次较慢）。"
            )
            Spacer(Modifier.height(Spacing.s8))
            OutlinedButton(
                onClick = {
                    if (healthLoading.value) return@OutlinedButton
                    healthLoading.value = true
                    healthMessages.clear()
                    val projDir = File(PathConfig.workspaceDir(context), selectedProject.value)
                    scope.launch(Dispatchers.IO) {
                        val msgs = mutableListOf<String>()
                        val appGradle = File(projDir, "app/build.gradle.kts")
                        if (appGradle.isFile) {
                            val rootGradle = File(projDir, "build.gradle.kts").takeIf { it.isFile }?.readText() ?: ""
                            msgs += BuildPreflight.inspect(appGradle.readText(), rootGradle).messages
                        }
                        msgs += BuildPreflight.inspectSourceCode(projDir)
                        val pre = BuildPreflight.checkPreconditions(projDir)
                        msgs += pre.hardErrors + pre.warnings
                        healthMessages.addAll(
                            if (msgs.isEmpty()) listOf("✓ 体检通过：未发现明显兼容性问题") else msgs
                        )
                        healthLoading.value = false
                    }
                },
                enabled = !healthLoading.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (healthLoading.value) "体检中…" else "项目体检（旧项目兼容性检查）")
            }
            if (healthMessages.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s8))
                Surface(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(10.dp)) {
                        healthMessages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

            Spacer(Modifier.height(Spacing.s8))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showExportDialog.value = true },
                    modifier = Modifier.fillMaxWidth(0.48f),
                ) { Text("导出源码(AI文档)") }
                OutlinedButton(
                    onClick = { showImportDialog.value = true },
                    modifier = Modifier.fillMaxWidth(0.48f),
                ) { Text("导入项目") }
            }

        Spacer(Modifier.height(Spacing.s16))

        // ── 部署到设备 ──
        SectionCard(title = "部署到设备") {
            if (deployArtifact.value == null) {
                InfoNote("请先成功构建（产出 APK）后再部署")
            } else {
                val (apk, pkg) = deployArtifact.value!!
                val submitDeploy: (Boolean) -> Unit = { launch ->
                    if (!deploySubmitting.value) {
                        deploySubmitting.value = true
                        val id = System.currentTimeMillis().toString()
                        upsertRecord(
                            AgentTaskRecord(
                                definition = AgentTaskDefinition(id = "deploy-$id", name = "部署 $pkg",
                                    description = "aidev-deploy 安装${if (launch) "并拉起" else ""} ($pkg)",
                                    command = "aidev-deploy --apk $apk --pkg $pkg${if (launch) " --launch" else " --no-launch"}",
                                    workingDirectory = PathConfig.workspaceDir(context).absolutePath,
                                    tags = listOf("deploy", "self-evolution")),
                                status = AgentTaskStatus.RUNNING, startedAt = System.currentTimeMillis(),
                                log = "已提交部署请求，等待执行…"
                            )
                        )
                        deployTracker.submit(context = context, id = id, apk = apk, pkg = pkg, launch = launch,
                            onDone = { deploySubmitting.value = false })
                    }
                }
                Button(
                    onClick = { submitDeploy(true) },
                    enabled = !deploySubmitting.value,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("安装并拉起")
                }
                Spacer(Modifier.height(Spacing.s8))
                OutlinedButton(
                    onClick = { submitDeploy(false) },
                    enabled = !deploySubmitting.value,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (deploySubmitting.value) "部署中…" else "仅安装")
                }
            }
        }

        Spacer(Modifier.height(Spacing.s16))

        // ── 任务 ──
        SectionCard(title = "任务") {
            if (taskRecords.isEmpty()) {
                InfoNote("暂无任务记录")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("共 ${taskRecords.size} 条", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text("清空全部", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { showClearConfirm.value = true })
                }
                taskRecords.forEach { record ->
                    AgentTaskRow(task = record, isSelected = selectedTaskId.value == record.definition.id,
                        onToggle = { selectedTaskId.value = if (selectedTaskId.value == record.definition.id) null else record.definition.id },
                        onDelete = { deleteTarget.value = record },
                        onRetry = {
                            if (record.definition.tags.any { it == "build" || it == "self-evolution" }) {
                                buildTracker.submit(context = context, project = projectOf(record).ifBlank { selectedProject.value },
                                    stateFile = taskStateFile, autonomous = PreferencesManager(context).selfEvolutionAutonomous, onUpdate = upsertRecord)
                            } else {
                                taskRunner.runTask(record.definition.copy(id = "agent-retry-${System.currentTimeMillis()}"), taskStateFile) { upsertRecord(it) }
                            }
                        },
                        onCancel = { cancelTarget.value = record })
                    Spacer(Modifier.height(Spacing.s8))
                }
            }
        }

        Spacer(Modifier.height(Spacing.s16))

        // ── 自愈闭环 ──
        SectionCard(title = "自愈闭环") {
            if (buildResult.value.isNotBlank()) InfoNote("最近构建: ${buildResult.value}")
            if (crashState.value.isNotBlank()) { Spacer(Modifier.height(Spacing.s4)); InfoNote("最近崩溃回流: ${crashState.value}") }

            Spacer(Modifier.height(Spacing.s12))
            Button(
                onClick = {
                    if (fixSending.value) return@Button
                    fixSending.value = true
                    fixMsg.value = ""
                    val proj = selectedProject.value
                    val model = PreferencesManager(context).selfEvolutionModel
                    scope.launch(Dispatchers.IO) {
                        val r = sendBuildFixToOpenCode(context, proj, model)
                        fixMsg.value = r
                        fixSending.value = false
                    }
                },
                enabled = !fixSending.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (fixSending.value) "发送中…" else "生成修复命令并发送到 OpenCode")
            }
            if (fixMsg.value.isNotBlank()) {
                Spacer(Modifier.height(Spacing.s8))
                InfoNote(fixMsg.value)
            }
        }

        Spacer(Modifier.height(Spacing.s24))
    }
}

// ─────────────────── 确认弹窗 ───────────────────

@Composable
private fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ─────────────────── AgentTaskRow ───────────────────

@Composable
private fun AgentTaskRow(
    task: AgentTaskRecord, isSelected: Boolean, onToggle: () -> Unit,
    onDelete: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
) {
    val statusTone = when (task.status) {
        AgentTaskStatus.SUCCEEDED -> StatusTone.Ok
        AgentTaskStatus.FAILED -> StatusTone.Warn
        else -> StatusTone.Neutral
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(Radius.button)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.s12)) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.definition.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    if (task.status == AgentTaskStatus.RUNNING) {
                        val phase = task.steps.firstOrNull { it.status == AgentTaskStatus.RUNNING }?.name
                            ?: task.steps.lastOrNull { it.status == AgentTaskStatus.SUCCEEDED }?.name
                        if (!phase.isNullOrBlank()) Text("当前阶段：$phase", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                StatusChip(taskStatusLabel(task.status), statusTone)
            }
            if (task.definition.description.isNotBlank()) {
                Spacer(Modifier.height(Spacing.s4))
                Text(task.definition.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (isSelected) {
                if (task.steps.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s8))
                    HorizontalDivider()
                    Spacer(Modifier.height(Spacing.s8))
                    task.steps.forEachIndexed { i, s ->
                        Text("${i + 1}. ${s.name} · ${taskStatusLabel(s.status)}" + if (s.exitCode >= 0) " (exit=${s.exitCode})" else "",
                            color = taskStatusColor(s.status), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (task.status == AgentTaskStatus.FAILED) {
                    Spacer(Modifier.height(Spacing.s8))
                    HorizontalDivider()
                    Spacer(Modifier.height(Spacing.s8))
                    val ctx = LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("复制错误摘要", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable { copyToClipboard(ctx, extractErrorSummary(task.log)); Toast.makeText(ctx, "错误摘要已复制", Toast.LENGTH_SHORT).show() })
                        Text("复制日志路径", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable {
                                val project = Regex("--project\\s+(\\S+)").find(task.definition.command)?.groupValues?.getOrNull(1)
                                val path = project?.let { findLatestFailureLogFile(ctx, it)?.absolutePath }
                                if (path != null) {
                                    copyToClipboard(ctx, path)
                                    Toast.makeText(ctx, "日志路径已复制", Toast.LENGTH_SHORT).show()
                                } else {
                                    copyToClipboard(ctx, task.log)
                                    Toast.makeText(ctx, "未找到日志文件，已复制截断日志", Toast.LENGTH_SHORT).show()
                                }
                            })
                    }
                }
                Spacer(Modifier.height(Spacing.s8))
                Text(text = task.log.ifBlank { "暂无输出" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                if (task.exitCode >= 0) {
                    Spacer(Modifier.height(Spacing.s4))
                    Text("exit=${task.exitCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(Spacing.s8))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isSelected) "收起" else "详情", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onToggle).padding(6.dp))
                Spacer(Modifier.weight(1f))
                Text("重试", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onRetry).padding(6.dp))
                if (task.status == AgentTaskStatus.RUNNING) {
                    Text("取消", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(onClick = onCancel).padding(6.dp))
                } else {
                    Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(onClick = onDelete).padding(6.dp))
                }
            }
        }
    }
}

// ─────────────────── 辅助组件 ───────────────────

@Composable
private fun taskStatusColor(status: AgentTaskStatus) = when (status) {
    AgentTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.secondary
    AgentTaskStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun taskStatusLabel(status: AgentTaskStatus) = when (status) {
    AgentTaskStatus.PENDING -> "待执行"; AgentTaskStatus.RUNNING -> "执行中"
    AgentTaskStatus.SUCCEEDED -> "已完成"; AgentTaskStatus.FAILED -> "失败"; AgentTaskStatus.CANCELLED -> "已取消"
}

private enum class StatusTone { Ok, Warn, Neutral }

@Composable
private fun StatusChip(text: String, tone: StatusTone) {
    val (bg, fg) = when (tone) {
        StatusTone.Ok -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Warn -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(Radius.card)
    ) {
        Column(modifier = Modifier.padding(Spacing.s16)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.s12))
            content()
        }
    }
}

@Composable
private fun ListRow(label: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

// ─────────────────── 数据函数 ───────────────────

private fun batteryIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ubuntuInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/.aidev-rootfs-ready").exists()

private fun opencodeInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/root/.opencode/bin/opencode").exists() ||
        File(context.filesDir, "home/.opencode/bin/opencode").exists()

private fun compilerInstalled(context: Context): Boolean =
    File(context.filesDir, "home/compiler_rootfs/.aidev-rootfs-ready").exists()

private fun lastBuildResult(context: Context): String {
    val dir = File(context.filesDir, "home/.aidev-build-bridge")
    val f = dir.listFiles { _, n -> n.startsWith("result-") && n.endsWith(".json") }?.maxByOrNull { it.lastModified() } ?: return ""
    return runCatching { org.json.JSONObject(f.readText()).optString("message", "") }.getOrNull() ?: ""
}

/**
 * 读取最近一次成功构建的产物（APK 路径 + 包名），供「部署到设备」按钮使用。
 * 优先匹配当前选中的项目；返回 null 表示尚无可用产物（需先成功构建）。
 */
private fun lastBuildArtifact(context: Context, project: String): Pair<String, String>? {
    val dir = File(context.filesDir, "home/.aidev-build-bridge")
    val results = (dir.listFiles { _, n -> n.startsWith("result-") && n.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { org.json.JSONObject(f.readText()) to f.lastModified() }.getOrNull() }
        .filter { it.first.optBoolean("success") }
        .filter { it.first.optString("project", "").let { p -> p.isBlank() || p == project } }
        .sortedByDescending { it.second }
    for ((o, _) in results) {
        val apk = o.optString("apk_path", "")
        val pkg = o.optString("pkg", "")
        if (apk.isNotBlank() && File(apk).isFile) return apk to (pkg.takeIf { it.isNotBlank() } ?: "")
    }
    return null
}

private fun writeSelfEvolutionModel(context: Context, model: String) {
    runCatching {
        val dir = File(context.filesDir, "home/.aidev-loop"); dir.mkdirs()
        File(dir, "se-config.json").writeText(org.json.JSONObject().put("model", model).toString() + "\n")
    }
}

private fun listProjects(context: Context): List<String> {
    val ws = PathConfig.workspaceDir(context)
    val dirs = ws.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.filter { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile || File(it, "gradlew").isFile }
        ?.map { it.name }?.sorted() ?: emptyList()
    return if (dirs.isEmpty()) listOf("MyAndroidProject") else dirs
}

private fun projectOf(record: AgentTaskRecord): String =
    record.definition.command.substringAfter("--project ", "").trim()

private fun extractErrorSummary(log: String): String {
    val start = "━━━ 错误摘要 ━━━"; val end = "━━━━━━━━━━━━━━"
    val si = log.indexOf(start); if (si < 0) return log
    val cs = si + start.length; val ei = log.indexOf(end, cs)
    return if (ei < 0) log.substring(cs).trim() else log.substring(cs, ei).trim()
}

/**
 * 选最新的构建失败日志文件，避免读到被后续构建覆盖的旧日志：
 * 1) 扫描 .aidev-loop/ 与 logs/<project>/ 下最新的 build-failure-<id>.json，
 *    若其 log_file 指向的文件存在则用之（最精确，对应本次失败）；
 * 2) 否则回退到 last-build-failure.log；
 * 3) 再回退 build.log。
 * 返回的文件路径在 PRoot 内可直接访问（/host-home 或 /sdcard 已绑定）。
 */
private fun findLatestFailureLogFile(context: Context, project: String): File? {
    val dirs = listOf(
        File(PathConfig.aidevHome(context), ".aidev-loop"),
        File(PathConfig.logsDir(context), project),
    )
    val jsons = dirs.flatMap { d ->
        (d.listFiles { f -> f.name.matches(Regex("build-failure-.*\\.json")) } ?: emptyArray()).toList()
    }.filter { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val pr = o.optString("project", "")
            // 跳过已被修复（fix_applied=true）的回流，避免把旧失败当成当前失败
            val fixed = o.optBoolean("fix_applied")
            (pr == project || pr.isBlank()) && !fixed
        }.getOrDefault(false)
    }.sortedByDescending { it.lastModified() }
    for (j in jsons) {
        val lf = runCatching { JSONObject(j.readText()).optString("log_file", "") }.getOrNull()
        if (!lf.isNullOrBlank()) {
            val f = File(lf)
            if (f.isFile) return f
        }
    }
    val stable = File(File(PathConfig.logsDir(context), project), "last-build-failure.log")
    if (stable.isFile) return stable
    return null
}

/**
 * 从最新构建日志提取错误摘要并发送到宇宙 A 的 OpenCode（端口 4096 共享后端），
 * 在 /workspace/<project> 目录下创建专用修复会话。这样「宇宙 B 构建失败 → 宇宙 A 改码」
 * 的回流不依赖脆弱的自动闭环，由服务器中心按钮可靠触发。
 */
private suspend fun sendBuildFixToOpenCode(context: Context, project: String, model: String): String {
    // 优先选「最新」的失败日志（按 build-failure-<id>.json 的 log_file，回退 last-build-failure.log / build.log），
    // 避免读到被后续构建覆盖的旧日志
    val latestLog = findLatestFailureLogFile(context, project)
    val buildLog = File(File(PathConfig.logsDir(context), project), "build.log")
    val logFile = latestLog ?: if (buildLog.isFile) buildLog else null
    if (logFile == null || !logFile.isFile) return "未找到 $project 的构建失败日志（logs/$project/ 或 home/.aidev-loop/ 下无 build-failure-*.json）"
    val logText = runCatching { logFile.readText() }.getOrDefault("")
    if (logText.isBlank()) return "构建日志为空，无错误可修复"
    // 终端 TUI 直接粘贴超长文本会卡死，故只把日志的【完整路径】交给 OpenCode，
    // 让它自己读取完整日志（PRoot 已绑定 /sdcard，路径在终端内可直接访问）。
    val logPath = logFile.absolutePath

    LoopTrace.section("构建修复回流: $project (model=$model)")
    LoopTrace.log("Fix", "读取构建日志: logs/$project/build.log")
    val backendOk = OpenCodeServerManager.ensureRunning(context)
    LoopTrace.log("Fix", "ensureRunning=$backendOk diag=${OpenCodeServerManager.lastDiagnostic} baseUrl=${com.aidev.six.Constants.OPENCODE_BASE_URL}")
    AIDevLogger.i("OpenCodeFix", "ensureRunning=$backendOk diag=${OpenCodeServerManager.lastDiagnostic} baseUrl=${com.aidev.six.Constants.OPENCODE_BASE_URL}")
    if (!backendOk) {
        LoopTrace.log("Fix", "后端未就绪，中止")
        return "OpenCode 后端未就绪：${OpenCodeServerManager.lastDiagnostic}"
    }

    // 终端 TUI 真实端口：aidev-opencode 写入 AIDEV_HOME/.aidev-opencode-port。
    // 默认 4096 常被 App 侧 headless serve 占用，必须打到 TUI 自己的端口，否则命令
    // 进了没有 TUI 的 serve 后端，终端看不到任何东西。
    val tuiPort = runCatching {
        File(PathConfig.aidevHome(context), ".aidev-opencode-port").readText().trim().toIntOrNull()
    }.getOrNull()?.takeIf { it in 1..65535 } ?: 4096
    val client = OpenCodeClient("http://127.0.0.1:$tuiPort")
    client.directory = "/workspace/$project"
    LoopTrace.log("Fix", "终端 TUI 端口=$tuiPort (portfile=${PathConfig.aidevHome(context)}/.aidev-opencode-port)")
    AIDevLogger.i("OpenCodeFix", "tuiPort=$tuiPort")

    val prompt = buildString {
        append("项目 $project 在 Android 构建（宇宙 B）中失败，请在 /workspace/$project 下定位并修复编译/构建错误。\n\n")
        append("本次失败完整日志路径：\n")
        append(logPath)
        append("\n（用 Read/cat 读取该文件即可看到完整构建输出，不要重复粘贴日志内容）\n\n")
        append("请自行驱动标准黑盒闭环（不要等系统替你重建）：\n")
        append("1) 读取上面的日志路径，定位根因并直接修改源码修复；\n")
        append("2) build 验证：运行 `aidev-build` 看实时输出；失败回到 1，直到构建通过（成功会得到 apk_path）；\n")
        append("3) deploy：运行 `aidev-deploy --apk <apk_path> --pkg <包名>` 安装并启动；\n")
        append("4) verify：运行 `aidev-verify-run --pkg <包名>` 监控是否崩溃；crashed=true 则读其 crash_log_path 回到 1；\n")
        append("5) 循环直到「构建通过 + 部署成功 + 运行不崩」（三个黑盒的标准出口全为成功/未崩溃）。\n")
        append("注意：不要反复 cat 旧失败日志来确认，直接看各黑盒的标准出口（JSON/stdout）。\n")
    }
    val (prov, mid) = splitModelForFix(model)
    LoopTrace.log("Fix", "prompt 长度=${prompt.length} prov=$prov mid=$mid")
    AIDevLogger.i("OpenCodeFix", "prompt len=${prompt.length} model=$model prov=$prov mid=$mid")

    // 无论如何都落盘 + 复制到剪贴板：保证命令可用，不依赖脆弱的 TUI 注入。
    val cmdFile = File("/sdcard/self-evolution-fix-$project.txt")
    runCatching { cmdFile.writeText(prompt) }
    runCatching { copyToClipboard(context, prompt) }
    LoopTrace.log("Fix", "已保存命令到 ${cmdFile.absolutePath} 并复制到剪贴板")

    // 主路径：直接注入终端 TUI 的命令行。append 成功后文本已出现在终端，
    // submit 尽力自动提交；无论 submit 是否成功，用户都能在终端看到命令（可手动回车）。
    val appendResp = client.appendTuiPrompt(prompt)
    LoopTrace.log("Fix", "appendTuiPrompt 响应=$appendResp")
    AIDevLogger.i("OpenCodeFix", "appendResp=$appendResp")
    if (appendResp != null) {
        val submitResp = client.submitTuiPrompt()
        LoopTrace.log("Fix", "submitTuiPrompt 响应=$submitResp")
        AIDevLogger.i("OpenCodeFix", "submitResp=$submitResp")
        return "已把修复命令填入终端 OpenCode 命令行（端口 $tuiPort，append=$appendResp, submit=$submitResp），请查看终端（必要时回车提交）。命令也已存到 $cmdFile 并复制剪贴板"
    }

    // 回退：终端 TUI 不可用（未在终端运行 opencode / 后端无 TUI）时，投递到当前最活跃会话。
    val sessions = client.listSessions()
    LoopTrace.log("Fix", "TUI 注入未生效，回退到会话；可用会话数=${sessions.size}")
    AIDevLogger.i("OpenCodeFix", "TUI 注入未生效，回退到会话；可用会话数=${sessions.size}")
    val targetSession = sessions.firstOrNull() ?: client.createSession("构建修复: $project")
        ?: return "OpenCode 后端无可用会话，命令已存到 $cmdFile 并复制剪贴板，请手动粘贴到终端 OpenCode"
    val ok = client.sendPromptAsync(targetSession.id, prompt, prov, mid, null)
    LoopTrace.log("Fix", "session 回退 ok=$ok session=${targetSession.title}")
    AIDevLogger.i("OpenCodeFix", "session fallback ok=$ok session=${targetSession.title}")
    val base = if (ok) "已发送修复命令到 OpenCode 会话「${targetSession.title}」(端口 $tuiPort)" else "发送失败"
    return "$base。命令也已存到 $cmdFile 并复制剪贴板，可直接粘贴到终端 OpenCode"
}

private fun splitModelForFix(model: String): Pair<String?, String?> {
    val idx = model.indexOf('/')
    return if (idx <= 0) null to null else model.substring(0, idx) to model.substring(idx + 1)
}

private fun copyToClipboard(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("AIDev", text))
}
