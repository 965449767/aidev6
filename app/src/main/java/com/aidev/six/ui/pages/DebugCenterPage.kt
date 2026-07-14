package com.aidev.six.ui.pages

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aidev.six.PathConfig
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.EmptyState
import com.aidev.six.ui.components.StatusCard
import com.aidev.six.ui.theme.CodeFont
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI Debug Center（M3-2）：列出 aidev-mcp 崩溃报告，解析根因（异常类 + 首个业务栈帧），
 * 一键 `aidev-crash-why` 做 AI 深度分析。fail-safe：解析失败的单条报告被跳过。
 * 见 rules/core/UI.md（状态设计：Empty/Loading/Error 统一范式）。
 */
data class CrashReportUi(
    val file: String,
    val pkg: String,
    val time: Long,
    val fatal: String,
    val stack: List<String>,
    val rootCause: String,
)

fun loadCrashReports(ctx: Context): List<CrashReportUi> {
    val dir = File(PathConfig.aidevHome(ctx), ".aidev-mcp")
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".json") }
        ?.mapNotNull { runCatching { parseCrashFile(it) }.getOrNull() }
        ?.sortedByDescending { it.time }
        .orEmpty()
}

private fun parseCrashFile(f: File): CrashReportUi {
    val j = JSONObject(f.readText())
    val pkg = j.optString("package", "unknown")
    val time = j.optLong("time", 0L)
    val fatal = j.optString("fatal", "")
    val arr = j.optJSONArray("stack")
    val stack = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }
    return CrashReportUi(f.name, pkg, time, fatal, stack, deriveRootCause(pkg, fatal, stack))
}

/** 根因 = 异常类 + 首个命中目标包名的业务栈帧（定位到具体类/方法/行）。 */
private fun deriveRootCause(pkg: String, fatal: String, stack: List<String>): String {
    val exc = fatal.substringBefore("\n").substringAfterLast(":").substringBefore("(").trim()
        .ifBlank { fatal.take(80) }
    val appFrame = stack.firstOrNull { it.contains(pkg) }
        ?.substringAfter(pkg)?.take(60)?.trim()
    return if (!appFrame.isNullOrBlank()) "$exc @ $pkg$appFrame" else exc
}

private fun formatTime(t: Long): String =
    if (t == 0L) "—" else runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t)) }.getOrDefault("—")

@Composable
fun DebugCenterPage(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reports = remember { loadCrashReports(context) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        item { AppSectionHeader("崩溃诊断", "AI Debug Center") }
        if (reports.isEmpty()) {
            item {
                EmptyState(
                    title = "暂无崩溃记录",
                    subtitle = "运行后崩溃会经 CrashReportBridge 落盘到 .aidev-mcp",
                    icon = Icons.Rounded.BugReport,
                )
            }
        } else {
            item {
                StatusCard(
                    label = "崩溃记录",
                    value = reports.size.toString(),
                    icon = Icons.Rounded.BugReport,
                    accent = MaterialTheme.colorScheme.error,
                )
            }
            items(reports, key = { it.file }) { r -> CrashCard(r, onExecuteCommand) }
        }
    }
}

@Composable
private fun CrashCard(r: CrashReportUi, onExecuteCommand: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(Spacing.s16)) {
            Text(r.pkg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.s4))
            Text(r.fatal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.s8))
            Text(
                "根因: ${r.rootCause}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.s4))
            Text(formatTime(r.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                FilledTonalButton(onClick = { onExecuteCommand("aidev-crash-why ${r.pkg}") }) {
                    Text("分析根因")
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起堆栈" else "查看堆栈 (${r.stack.size})")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.s8))
                Text(
                    r.stack.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
