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
import androidx.compose.material.icons.rounded.Refresh
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
import com.aidev.six.PathConfig
import com.aidev.six.chat.ChatPart
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.git.GitDiffParser
import com.aidev.six.terminal.ProotLauncher
import com.aidev.six.ui.components.EmptyState
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitReviewPage(
    modifier: Modifier = Modifier,
    repoPath: String = "/host-home/aidev6",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diff by remember { mutableStateOf<List<GitDiffParser.FileDiff>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var aiReply by remember { mutableStateOf<String?>(null) }

    val summary = remember(diff) { GitDiffParser.summarize(diff) }

    fun scan() {
        scope.launch {
            scanning = true
            error = null
            aiReply = null
            val res = withContext(Dispatchers.IO) {
                ProotLauncher.run(
                    context,
                    "git -C $repoPath diff HEAD --numstat",
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

    fun review() {
        scope.launch {
            reviewing = true
            aiReply = null
            if (!OpenCodeServerManager.isRunning()) {
                aiReply = "OpenCode 未运行，无法 AI 评审。请先启动 OpenCode 服务后再试。"
                reviewing = false
                return@launch
            }
            val client = OpenCodeClient("http://127.0.0.1:${OpenCodeServerManager.PORT}")
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

        Text(
            repoPath,
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
                        enabled = !reviewing && OpenCodeServerManager.isRunning(),
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
