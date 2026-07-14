package com.aidev.six.ui.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aidev.six.PathConfig
import com.aidev.six.ProjectImporter
import com.aidev.six.rememberTreeDirPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ProjectImportDialog(
    onDismiss: () -> Unit,
    onImported: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var srcPath by remember { mutableStateOf("") }
    var srcFile by remember { mutableStateOf<File?>(null) }
    var targetName by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val ws = PathConfig.workspaceDir(context)
    val pickTree = rememberTreeDirPicker { dir ->
        if (dir != null) {
            srcPath = dir.absolutePath
            srcFile = dir
            if (targetName.isBlank()) targetName = dir.name
        } else {
            Toast.makeText(context, "仅支持主存储目录，可手动输入路径", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入项目到 workspace") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
                Text("选择一个源目录，复制到 workspace（自动清理 build/.gradle/.idea 等）。")
                Text("源目录：", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = srcPath,
                    onValueChange = { srcPath = it; srcFile = null },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickTree() }, modifier = Modifier.fillMaxWidth()) {
                    Text("浏览…（选源目录）")
                }
                Text("目标项目名（留空用源目录名）：", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(value = targetName, onValueChange = { targetName = it }, modifier = Modifier.fillMaxWidth())
                if (running) Text("进度：$progressPct%", modifier = Modifier.padding(top = 8.dp))
                status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val src = srcFile ?: File(srcPath)
                    if (!src.isDirectory) {
                        status = "源目录无效：${src.absolutePath}"
                        return@Button
                    }
                    running = true
                    status = "导入中…"
                    progressPct = 0
                    val name = targetName.takeIf { it.isNotBlank() }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val dest = ProjectImporter.importProject(src, ws, name) { d, t ->
                                progressPct = if (t > 0) d * 100 / t else 100
                            }
                            launch(Dispatchers.Main) {
                                status = "已导入：${dest.absolutePath}"
                                running = false
                                onImported(dest)
                            }
                        } catch (e: Exception) {
                            launch(Dispatchers.Main) { status = "失败：${e.message}"; running = false }
                        }
                    }
                },
                enabled = !running,
            ) { Text("开始导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
