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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.aidev.six.PathConfig
import com.aidev.six.git.GitDiffParser
import com.aidev.six.git.GitRepoDetector
import com.aidev.six.terminal.ProotLauncher
import com.aidev.six.ui.components.EmptyState
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "git_review"
private const val KEY_CURRENT_REPO = "current_repo"

private data class ProjectReview(
    val repo: String,
    val isGit: Boolean,
    val diff: List<GitDiffParser.FileDiff>,
    val summary: GitDiffParser.ReviewSummary,
    val hasChanges: Boolean,
)

@Composable
fun GitReviewPage(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    var projects by remember { mutableStateOf<List<ProjectReview>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<String?>(null) }
    var selectedIsGit by remember { mutableStateOf(true) }
    var detailDiff by remember { mutableStateOf<List<GitDiffParser.FileDiff>>(emptyList()) }
    var scanningOverview by remember { mutableStateOf(false) }
    var scanningDetail by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val detailSummary = remember(detailDiff) { GitDiffParser.summarize(detailDiff) }

    fun scanOne(entry: GitRepoDetector.ProjectEntry): ProjectReview {
        if (!entry.isGit) {
            return ProjectReview(entry.path, false, emptyList(), GitDiffParser.summarize(emptyList()), false)
        }
        val res = ProotLauncher.run(
            context,
            "git -C ${entry.path} diff HEAD --numstat",
            ProotLauncher.Options(
                rootfs = PathConfig.agentRootfs(context).absolutePath,
                cwd = "/host-home",
                timeoutSec = 30,
            ),
        )
        val diff = if (res.exitCode != 0 && res.stdout.isBlank()) emptyList() else GitDiffParser.parseNumstat(res.stdout)
        return ProjectReview(entry.path, true, diff, GitDiffParser.summarize(diff), diff.isNotEmpty())
    }

    fun scanOverview() {
        scope.launch {
            scanningOverview = true
            error = null
            selectedRepo = null
            val entries = withContext(Dispatchers.IO) { GitRepoDetector.listProjects(context) }
            if (entries.isEmpty()) {
                projects = emptyList()
                error = "工作目录（${GitRepoDetector.WORKSPACE_PROOT}）内未找到任何项目（git 或安卓/gradle 工程）。"
                scanningOverview = false
                return@launch
            }
            val scanned = withContext(Dispatchers.IO) {
                kotlinx.coroutines.coroutineScope {
                    entries.map { e -> async { runCatching { scanOne(e) }.getOrDefault(ProjectReview(e.path, e.isGit, emptyList(), GitDiffParser.summarize(emptyList()), false)) } }.awaitAll()
                }
            }
            projects = scanned
            scanningOverview = false
        }
    }

    fun openProject(repo: String) {
        selectedRepo = repo
        selectedIsGit = projects.find { it.repo == repo }?.isGit ?: true
        error = null
        if (!selectedIsGit) {
            detailDiff = emptyList()
            scanningDetail = false
            return
        }
        scope.launch {
            scanningDetail = true
            detailDiff = withContext(Dispatchers.IO) { scanOne(GitRepoDetector.ProjectEntry(repo, true)).diff }
            scanningDetail = false
        }
    }

    fun initGit(repo: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                ProotLauncher.run(
                    context,
                    "git -C $repo init",
                    ProotLauncher.Options(
                        rootfs = PathConfig.agentRootfs(context).absolutePath,
                        cwd = "/host-home",
                        timeoutSec = 30,
                    ),
                )
            }
            scanOverview()
        }
    }

    fun deleteRepo(repo: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                ProotLauncher.run(
                    context,
                    "rm -rf '$repo'",
                    ProotLauncher.Options(
                        rootfs = PathConfig.agentRootfs(context).absolutePath,
                        cwd = "/host-home",
                        timeoutSec = 60,
                    ),
                )
            }
            if (prefs.getString(KEY_CURRENT_REPO, "") == repo) prefs.edit().remove(KEY_CURRENT_REPO).apply()
            scanOverview()
        }
    }

    fun copyDiff() {
        val text = GitDiffParser.toReviewPrompt(detailDiff)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("GitDiff", text))
        Toast.makeText(context, "已复制 diff，请贴到终端 OpenCode 处理", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) { scanOverview() }

    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.s16).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            IconButton(onClick = { if (selectedRepo != null) { selectedRepo = null; detailDiff = emptyList() } else onBack() }) {
                Icon(Icons.Rounded.ArrowBack, "返回")
            }
            Text(if (selectedRepo == null) "代码评审 · 工作目录" else "项目评审", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { if (selectedRepo == null) scanOverview() else openProject(selectedRepo!!) }, enabled = !scanningOverview && !scanningDetail) {
                Icon(Icons.Rounded.Refresh, "刷新", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text(if (scanningOverview || scanningDetail) "扫描中…" else "刷新")
            }
        }

        if (selectedRepo == null) {
            // ── 总览层 ──
            Text(
                "工作目录：${GitRepoDetector.WORKSPACE_PROOT}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                scanningOverview -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error != null -> EmptyState(title = "未找到项目", subtitle = error ?: "")
                projects.isEmpty() -> EmptyState(title = "无项目", subtitle = "工作目录下没有 git 仓库。")
                else -> {
                    val total = projects.size
                    val dirty = projects.count { it.hasChanges }
                    val highRisk = projects.count { it.summary.highRisk > 0 }
                    val uninit = projects.count { !it.isGit }
                    val totalFiles = projects.sumOf { it.summary.files }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
                    ) {
                        Column(Modifier.padding(Spacing.s16), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                            Text("工作目录总览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "项目 $total  ·  有改动 $dirty  ·  高风险 $highRisk  ·  未初始化 $uninit  ·  改动文件共 $totalFiles",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    projects.forEach { p ->
                        ProjectOverviewRow(p) { openProject(p.repo) }
                    }
                }
            }
        } else {
            // ── 详情层 ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Text(
                    selectedRepo!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Rounded.Delete, "删除项目", tint = MaterialTheme.colorScheme.error)
                }
            }
            when {
                scanningDetail -> LinearProgressIndicator(Modifier.fillMaxWidth())
                !selectedIsGit -> {
                    EmptyState(title = "未初始化 git", subtitle = "该项目尚未 git 初始化，无法做 diff 评审。初始化后即可查看逐文件改动与 AI 评审。")
                    Row {
                        OutlinedButton(onClick = { initGit(selectedRepo!!) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Refresh, "初始化", modifier = Modifier.size(Spacing.s16))
                            Spacer(Modifier.width(Spacing.s4))
                            Text("初始化 git 仓库")
                        }
                    }
                }
                detailDiff.isEmpty() -> EmptyState(title = "无未提交改动", subtitle = "git diff HEAD 为空，工作区干净。")
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
                    ) {
                        Column(Modifier.padding(Spacing.s16), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                            Text("改动概览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "文件 ${detailSummary.files}  ·  +${detailSummary.additions} / -${detailSummary.deletions}  ·  " +
                                    "高风险 ${detailSummary.highRisk}  ·  中 ${detailSummary.midRisk}  ·  低 ${detailSummary.lowRisk}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        OutlinedButton(onClick = { copyDiff() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.ContentCopy, "复制 diff", modifier = Modifier.size(Spacing.s16))
                            Spacer(Modifier.width(Spacing.s4))
                            Text("复制 diff 到终端")
                        }
                    }
                    detailDiff.forEach { file -> FileReviewRow(file) }
                }
            }
        }
    }

    if (showDeleteConfirm && selectedRepo != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除项目") },
            text = { Text("将永久删除该仓库目录及其所有文件：\n\n${selectedRepo}\n\n此操作不可恢复，请确认。") },
            confirmButton = {
                TextButton(onClick = { val repo = selectedRepo!!; showDeleteConfirm = false; deleteRepo(repo) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectOverviewRow(project: ProjectReview, onClick: () -> Unit) {
    val riskColor = when (project.summary.maxRisk) {
        5, 4 -> MaterialTheme.colorScheme.error
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.repo.substringAfterLast("/"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (!project.isGit) "未初始化 git"
                    else if (project.hasChanges) "有 ${project.summary.files} 处改动"
                    else "工作区干净",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!project.isGit) MaterialTheme.colorScheme.tertiary
                    else if (project.hasChanges) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (project.hasChanges) {
                Text("+${project.summary.additions}/-${project.summary.deletions}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "★".repeat(project.summary.maxRisk) + "☆".repeat(5 - project.summary.maxRisk),
                    color = riskColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FileReviewRow(file: GitDiffParser.FileDiff) {
    val riskColor = when (file.riskStars) {
        5, 4 -> MaterialTheme.colorScheme.error
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val catColor = when (file.category) {
        GitDiffParser.DiffCategory.BUILD -> MaterialTheme.colorScheme.tertiary
        GitDiffParser.DiffCategory.NATIVE -> MaterialTheme.colorScheme.error
        GitDiffParser.DiffCategory.SHELL -> MaterialTheme.colorScheme.tertiary
        GitDiffParser.DiffCategory.DOC -> MaterialTheme.colorScheme.primary
        GitDiffParser.DiffCategory.COMPOSE -> MaterialTheme.colorScheme.primary
        GitDiffParser.DiffCategory.OTHER -> MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
    ) {
        Column(Modifier.padding(Spacing.s12), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Text(
                    text = "★".repeat(file.riskStars) + "☆".repeat(5 - file.riskStars),
                    color = riskColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(file.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Text("+${file.additions}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text("-${file.deletions}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier.background(catColor.copy(alpha = 0.18f), androidx.compose.foundation.shape.RoundedCornerShape(Radius.button))
                        .padding(horizontal = Spacing.s8, vertical = Spacing.s4),
                ) {
                    Text(file.category.name, color = catColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (file.notes.isNotEmpty()) {
                file.notes.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
