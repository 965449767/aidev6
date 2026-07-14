package com.aidev.six.ui.pages

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aidev.six.AIDevCommandDispatcher
import com.aidev.six.ShizukuLogcat
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ADB / Shizuku Explorer（M3-3）：列出已装应用，解析权限/启动 Activity，一键
 * 打开 / 强制停止 / 清除数据（均走 Shizuku 白名单命令）。全 fail-safe。
 * 见 rules/core/UI.md（Dashboard 优先、Card+Grid、只用 Token）。
 */
data class PackageDetail(
    val pkg: String,
    val version: String,
    val mainActivity: String?,
    val permissions: List<String>,
)

@Composable
fun AdbExplorerPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var packages by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PackageDetail?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            packages = runCatching { listPackages() }.getOrElse { emptyList() }
        }
    }

    fun select(pkg: String) {
        selected = pkg
        detail = null
        busy = true
        scope.launch(Dispatchers.IO) {
            detail = runCatching { loadDetail(pkg) }.getOrNull()
            busy = false
        }
    }

    fun runOp(command: String, label: String) {
        scope.launch(Dispatchers.IO) {
            val res = runCatching { ShizukuLogcat.executeCommand(command) }.getOrNull()
            val ok = res?.exitCode == 0
            val msg = if (ok) "执行成功" else "失败: ${(res?.stderr ?: res?.stdout ?: "").take(120)}"
            AIDevCommandDispatcher.notify(context, label, msg, priority = if (ok) "default" else "high")
        }
    }

    val filtered = remember(packages, query) {
        packages?.filter { it.contains(query.trim(), ignoreCase = true) }.orEmpty()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        item { AppSectionHeader("设备浏览器", "ADB / Shizuku Explorer") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用包名…") },
                singleLine = true,
            )
        }
        when {
            packages == null -> item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    CircularProgressIndicator(modifier = Modifier.height(Spacing.s16))
                    Text("加载应用列表…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            filtered.isEmpty() -> item {
                Text("无匹配应用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> items(filtered, key = { it }) { pkg ->
                PackageRow(pkg = pkg, selected = pkg == selected, onClick = { select(pkg) })
            }
        }
        detail?.let { d ->
            item {
                PackageDetailCard(
                    d = d,
                    busy = busy,
                    onOpen = { runOp("monkey -p ${d.pkg} -c android.intent.category.LAUNCHER 1", "打开 ${d.pkg}") },
                    onStop = { runOp("am force-stop ${d.pkg}", "停止 ${d.pkg}") },
                    onClear = { runOp("pm clear ${d.pkg}", "清除数据 ${d.pkg}") },
                )
            }
        }
    }
}

@Composable
private fun PackageRow(pkg: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.button),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pkg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PackageDetailCard(
    d: PackageDetail,
    busy: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.s16)) {
            Text(d.pkg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Spacing.s4))
            Text("版本: ${d.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("启动 Activity: ${d.mainActivity ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.s8))
            Text("权限 (${d.permissions.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                d.permissions.take(12).joinToString("\n").ifBlank { "（无声明权限）" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.s12))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalButton(onClick = onOpen, enabled = !busy) { Text("打开") }
                FilledTonalButton(onClick = onStop, enabled = !busy) { Text("停止") }
                FilledTonalButton(onClick = onClear, enabled = !busy) { Text("清数据") }
            }
        }
    }
}

// ——— 数据层（fail-safe，解析失败回退空）———

private suspend fun listPackages(): List<String> {
    val res = ShizukuLogcat.executeCommand("pm list packages")
    if (res.exitCode != 0) return emptyList()
    return res.stdout.lineSequence()
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.isNotBlank() }
        .sorted()
        .toList()
}

private suspend fun loadDetail(pkg: String): PackageDetail {
    val res = ShizukuLogcat.executeCommand("dumpsys package $pkg")
    val text = if (res.exitCode == 0) res.stdout else ""
    val version = VERSION_RE.find(text)?.groupValues?.get(1)?.trim() ?: "—"
    val main = MAIN_ACTIVITY_RE.find(text)?.groupValues?.get(1)?.trim()
    val permissions = PERM_RE.findAll(text).map { it.groupValues[1] }.filter { it.contains("permission") }.toList()
    return PackageDetail(pkg, version, main, permissions)
}

private val VERSION_RE = Regex("versionName=([^\\s]+)")
private val MAIN_ACTIVITY_RE = Regex("android\\.intent\\.action\\.MAIN:\\s*\\n\\s*([^\\s/]+/[^\\s]+)")
private val PERM_RE = Regex("(\\S+?): granted=")
