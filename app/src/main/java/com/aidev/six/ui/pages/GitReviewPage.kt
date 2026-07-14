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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.aidev.six.PathConfig
import com.aidev.six.chat.ChatPart
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.git.GitDiffParser
import com.aidev.six.git.GitRepoDetector
import com.aidev.six.terminal.ProotLauncher
import com.aidev.six.ui.components.EmptyState
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "git_review"
private const val KEY_CURRENT_REPO = "current_repo"

@Composable
fun GitReviewPage(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    var diff by remember { mutableStateOf<List<GitDiffParser.FileDiff>>(emptyList()) }
    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedRepo by remember { mutableStateOf<String?>(null) }
    var repoMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var aiReply by remember { mutableStateOf<String?>(null) }

    val summary = remember(diff) { GitDiffParser.summarize(diff) }

    fun loadDiff(repo: String) {
        scope.launch {
            scanning = true
            error = null
            aiReply = null
            val res = withContext(Dispatchers.IO) {
                ProotLauncher.run(
                    context,
                    "git -C $repo diff HEAD --numstat",
                    ProotLauncher.Options(
                        rootfs = PathConfig.agentRootfs(context).absolutePath,
                        cwd = "/host-home",
                        timeoutSec = 30,
                    ),
                )
            }
            if (res.exitCode != 0 && res.stdout.isBlank()) {
                error = res.stderr.ifBlank { "git 执行失败（exit ${res.exitCode}）" }
                diff = emptyList()
            } else {
                diff = GitDiffParser.parseNumstat(res.stdout)
            }
            scanning = false
        }
    }

    fun scan() {
        scope.launch {
            scanning = true
            error = null
            aiReply = null
            val all = withContext(Dispatchers.IO) { GitRepoDetector.listRepos(context) }
            repos = all
            val saved = prefs.getString(KEY_CURRENT_REPO, "") ?: ""
            val repo = if (saved in all) saved else all.firstOrNull()
            selectedRepo = repo
            if (repo == null) {
                error = "未找到 git 仓库（已探测 /host-home 下常见路径）。请确认 PRoot 内源码位置，或在终端创建 git 仓库。"
                diff = emptyList()
                scanning = false
                return@launch
            }
            val res = withContext(Dispatchers.IO) {
                ProotLauncher.run(
                    context,
                    "git -C $repo diff HEAD --numstat",
                    ProotLauncher.Options(
                        rootfs = PathConfig.agentRootfs(context).absolutePath,
                        cwd = "/host-home",
                        timeoutSec = 30,
                    ),
                )
            }
            if (res.exitCode != 0 && res.stdout.isBlank()) {
                error = res.stderr.ifBlank { "git 执行失败（exit ${res.exitCode}）" }
                diff = emptyList()
            } else {
                diff = GitDiffParser.parseNumstat(res.stdout)
            }
            scanning = false
        }
    }

    fun selectRepo(repo: String) {
        selectedRepo = repo
        prefs.edit().putString(KEY_CURRENT_REPO, repo).apply()
        repoMenuExpanded = false
        loadDiff(repo)
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
            if (prefs.getString(KEY_CURRENT_REPO, "") == repo) {
                prefs.edit().remove(KEY_CURRENT_REPO).apply()
            }
            scan()
        }
    }

    fun review() {
        scope.launch {
            reviewing = true
            aiReply = null
            val client = OpenCodeClient("http://127.0.0.1:${OpenCodeServerManager.PORT}")
            val healthy = withContext(Dispatchers.IO) { runCatching { client.health() }.getOrDefault(false) }
            if (!healthy) {
                aiReply = "OpenCode 未运行或未响应（端口 ${OpenCodeServerManager.PORT}）。请先启动 OpenCode：在 AI 对话中拉起，或终端执行 `opencode serve --port ${OpenCodeServerManager.PORT}`。"
                reviewing = false
                return@launch
            }
            val session = client.createSession("Git Review")
            if (session == null) {
                aiReply = "创建 OpenCode 会话失败。"
                reviewing = false
                return@launch
            }
            val ok = client.sendPromptAsync(session.id, GitDiffParser.toReviewPrompt(diff), null, null, null)
            if (!ok) {
                aiReply = "提交评审失败（OpenCode 未确认）。"
                reviewing = false
                return@launch
            }
            repeat(30) {
                if (!isActive) return@repeat
                delay(1500)
                val msgs = client.listMessages(session.id)
                val text = msgs.lastOrNull { it.role == "assistant" }
                    ?.parts?.filterIsInstance<ChatPart.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                if (!text.isNullOrBlank()) {
                    aiReply = text
                    return@repeat
                }
            }
            if (aiReply == null) aiReply = "已提交 AI 评审，请在 OpenCode 会话查看回复。"
            reviewing = false
        }
    }

    LaunchedEffect(Unit) { scan() }

    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.s16).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
            Text("代码评审", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scan() }, enabled = !scanning) {
                Icon(Icons.Rounded.Refresh, "重新扫描", modifier = Modifier.size(Spacing.s16))
                Spacer(Modifier.width(Spacing.s4))
                Text(if (scanning) "扫描中…" else "重新扫描")
            }
        }

        // 项目选择器
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Box {
                OutlinedButton(onClick = { repoMenuExpanded = true }, enabled = repos.isNotEmpty()) {
                    Text(selectedRepo ?: if (repos.isEmpty()) "无仓库" else "选择仓库")
                }
                DropdownMenu(expanded = repoMenuExpanded, onDismissRequest = { repoMenuExpanded = false }) {
                    repos.forEach { repo ->
                        DropdownMenuItem(
                            text = { Text(repo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { selectRepo(repo) },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (selectedRepo != null) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Rounded.Delete, "删除项目", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            selectedRepo ?: "未选择仓库",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            scanning -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> EmptyState(title = "无法读取改动", subtitle = error ?: "")
            diff.isEmpty() -> EmptyState(title = "无未提交改动", subtitle = "git diff HEAD 为空，工作区干净。")
            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
                ) {
                    Column(Modifier.padding(Spacing.s16), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                        Text("改动概览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "文件 ${summary.files}  ·  +${summary.additions} / -${summary.deletions}  ·  " +
                                "高风险 ${summary.highRisk}  ·  中 ${summary.midRisk}  ·  低 ${summary.lowRisk}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    OutlinedButton(
                        onClick = { review() },
                        enabled = !reviewing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, "AI 评审", modifier = Modifier.size(Spacing.s16))
                        Spacer(Modifier.width(Spacing.s4))
                        Text(if (reviewing) "评审中…" else "AI 深度评审")
                    }
                }

                aiReply?.let { reply ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.card),
                    ) {
                        Column(Modifier.padding(Spacing.s16), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                            Text("AI 评审结论", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(reply, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                diff.forEach { file ->
                    FileReviewRow(file)
                }
            }
        }
    }

    if (showDeleteConfirm && selectedRepo != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除项目") },
            text = {
                Text("将永久删除该仓库目录及其所有文件：\n\n${selectedRepo}\n\n此操作不可恢复，请确认。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val repo = selectedRepo!!
                        showDeleteConfirm = false
                        deleteRepo(repo)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
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
