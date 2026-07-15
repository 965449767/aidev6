package com.aidev.six.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.aidev.six.PreferencesManager
import com.aidev.six.ShizukuLogcat
import com.aidev.six.ui.components.AppRadioDialogContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MenuEntryRow(entry: MenuEntry, modifier: Modifier = Modifier) {
    when (val kind = entry.kind) {
        is MenuEntryKind.Toggle -> {
            var checked by remember { mutableStateOf(kind.checked()) }
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.button))
                    .clickable {
                        val newValue = !checked
                        checked = newValue
                        kind.onToggle(newValue)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = entry.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.s12))
                Switch(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        kind.onToggle(it)
                    },
                )
            }
        }
        else -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.button))
                    .clickable(onClick = entry.action)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = entry.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogHost(
    dialog: SettingsDialog,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is SettingsDialog.ThemePreset -> ThemePresetDialog(dialog.current, onDismiss)
        is SettingsDialog.BackgroundMode -> BackgroundModeDialog(dialog.current, onDismiss)
        is SettingsDialog.ShizukuStatus -> ShizukuStatusDialog(dialog.installed, dialog.available, dialog.statusText, onDismiss)
        is SettingsDialog.PathEdit -> PathEditDialog(dialog.title, dialog.current, dialog.onSave, onDismiss)
    }
}

@Composable
private fun ThemePresetDialog(current: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = listOf("dark" to "深色", "light" to "亮色", "system" to "跟随系统")
    var selectedValue by remember { mutableStateOf(current.ifEmpty { "dark" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题预设") },
        text = {
            AppRadioDialogContent(
                items = items,
                selectedValue = selectedValue,
                onSelect = { selectedValue = it },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                PreferencesManager(context).themePreset = selectedValue
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun BackgroundModeDialog(current: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = listOf("solid" to "纯色背景", "gradient" to "主题渐变", "image" to "自定义图片")
    var selectedValue by remember { mutableStateOf(current.ifEmpty { "solid" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("背景模式") },
        text = {
            AppRadioDialogContent(
                items = items,
                selectedValue = selectedValue,
                onSelect = { selectedValue = it },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                PreferencesManager(context).bgMode = selectedValue
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}



@Composable
private fun ShizukuStatusDialog(installed: Boolean, available: Boolean, statusText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shizuku 状态") },
        text = {
            Column {
                Text("Shizuku 应用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (installed) "已安装" else "未安装",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (installed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text("Shizuku 授权", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (available && !testing) {
                    Text(
                        text = "\u25B6 测试命令执行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            testing = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ShizukuLogcat.executeCommand("echo SHIZUKU_TEST_OK")
                                }
                                testResult = if (result.isSuccess) {
                                    "\u2705 成功：${result.stdout.trim()}"
                                } else {
                                    "\u274C 失败：${result.stderr.take(100)}"
                                }
                                testing = false
                            }
                        },
                    )
                    Spacer(Modifier.height(Spacing.s4))
                }
                if (testResult.isNotEmpty()) {
                    Text(
                        text = testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult.startsWith("\u2705")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!installed) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                    } catch (_: Exception) {}
                } else if (!available) {
                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) context.startActivity(intent)
                }
                onDismiss()
            }) {
                Text(
                    if (!installed) "去安装"
                    else if (!available) "打开 Shizuku"
                    else "关闭"
                )
            }
        },
        dismissButton = {
            if (!available) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PathEditDialog(title: String, current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(Radius.button),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    if (text.isNotBlank()) {
                        onSave(text.trim())
                    }
                    onDismiss()
                }) { Text("保存") }
                TextButton(onClick = {
                    onSave("")
                    onDismiss()
                }) { Text("重置默认") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PathSettingsSheet(
    context: android.content.Context,
    prefs: PreferencesManager,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("路径设置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.s12))

            PathRow("备份目录", "备份文件和恢复数据的存储路径", com.aidev.six.PathConfig.backupDir(context).absolutePath) {
                dialog = SettingsDialog.PathEdit("备份目录", com.aidev.six.PathConfig.backupDir(context).absolutePath) { prefs.backupDir = it }
            }
            PathRow("项目目录", "Ubuntu 内新建项目的默认位置（相对 rootfs）", com.aidev.six.PathConfig.projectsDir(context).absolutePath) {
                dialog = SettingsDialog.PathEdit("项目目录（相对 rootfs）", prefs.projectsDirRel.ifBlank { "root/projects" }) { prefs.projectsDirRel = it }
            }
            PathRow("外部 AIDev 目录", "Android 侧项目数据存放路径", com.aidev.six.PathConfig.externalAidevDir(context).absolutePath) {
                dialog = SettingsDialog.PathEdit("外部 AIDev 目录", com.aidev.six.PathConfig.externalAidevDir(context).absolutePath) { prefs.externalAidevDir = it }
            }

            ReadonlyPathRow("AIDev Home", "核心数据目录，含 Ubuntu 环境和全部配置（只读）", com.aidev.six.PathConfig.aidevHome(context).absolutePath)
            ReadonlyPathRow("Ubuntu Rootfs", "Ubuntu 根文件系统（只读）", com.aidev.six.PathConfig.rootfs(context).absolutePath)
            ReadonlyPathRow("任务日志目录", "后台任务日志和元数据的存储位置（只读）", com.aidev.six.PathConfig.tasksDir(context).absolutePath)
        }
    }

    dialog?.let { d ->
        SettingsDialogHost(d, onDismiss = { dialog = null })
    }
}

@Composable
internal fun PathRow(title: String, desc: String, path: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ReadonlyPathRow(title: String, desc: String, path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val clip = android.content.ClipData.newPlainText("path", path)
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(clip)
            }
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}




