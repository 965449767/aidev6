package com.aidev.six.ui.pages

import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aidev.six.PathConfig
import com.aidev.six.ProjectExporter
import com.aidev.six.PreferencesManager
import com.aidev.six.rememberTreeDirPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ProjectExportDialog(
    projectName: String,
    defaultOutDir: File,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeGit by remember { mutableStateOf(false) }
    var plainText by remember { mutableStateOf(false) }
    var destPath by remember { mutableStateOf(File(defaultOutDir, "$projectName-source.md").absolutePath) }
    var running by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val pickTree = rememberTreeDirPicker { dir ->
        if (dir != null) destPath = File(dir, "$projectName-source.md").absolutePath
        else Toast.makeText(context, "仅支持主存储目录，可手动输入路径", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出源码（AI 文档）") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.s4)) {
                Text("将「$projectName」源码合成为一份 AI 可读文档（每文件带路径与代码块）。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = plainText, onCheckedChange = { plainText = it })
                    Text("纯文本格式（非 Markdown）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeGit, onCheckedChange = { includeGit = it })
                    Text("包含 .git 目录")
                }
                Text("目标文件：", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(value = destPath, onValueChange = { destPath = it }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickTree() }, modifier = Modifier.fillMaxWidth()) {
                    Text("浏览…（主存储目录）")
                }
                if (running) Text("进度：$progressPct%", modifier = Modifier.padding(top = 8.dp))
                status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val src = File(PathConfig.workspaceDir(context), projectName)
                    if (!src.isDirectory) {
                        status = "项目目录不存在：${src.absolutePath}"
                        return@Button
                    }
                    running = true
                    status = "导出中…"
                    progressPct = 0
                    val out = File(destPath)
                    val opts = ProjectExporter.Options(includeGit = includeGit, plainText = plainText)
                    scope.launch(Dispatchers.IO) {
                        try {
                            ProjectExporter.exportToFile(src, out, opts) { d, t ->
                                progressPct = if (t > 0) d * 100 / t else 100
                            }
                            launch(Dispatchers.Main) { status = "已导出：${out.absolutePath}"; running = false }
                        } catch (e: Exception) {
                            launch(Dispatchers.Main) { status = "失败：${e.message}"; running = false }
                        }
                    }
                },
                enabled = !running,
            ) { Text("开始导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
