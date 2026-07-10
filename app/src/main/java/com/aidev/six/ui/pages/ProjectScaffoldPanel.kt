package com.aidev.six.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.ProjectScaffoldState
import com.aidev.six.ScaffoldTemplate

@Composable
fun ProjectScaffoldDialog(
    onDismiss: () -> Unit,
    onSendToTerminal: ((String) -> Unit)? = null,
) {
    val state = ProjectScaffoldState
    var showPreview by remember { mutableStateOf(false) }

    val currentCallback = rememberUpdatedState(onSendToTerminal)

    LaunchedEffect(Unit) {
        state.onSendToTerminal = currentCallback.value
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android 项目脚手架") },
        text = {
            if (showPreview) {
                ScriptPreview(state.generateScript(), onBack = { showPreview = false }, onSendToTerminal = onSendToTerminal)
            } else {
                ScaffoldForm(onPreview = { showPreview = true })
            }
        },
        confirmButton = {
            if (showPreview) {
                TextButton(onClick = { showPreview = false }) { Text("返回修改") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun ScaffoldForm(onPreview: () -> Unit) {
    val state = ProjectScaffoldState
    Column {
        OutlinedTextField(state.form.projectName, onValueChange = { state.form = state.form.copy(projectName = it) },
            label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(state.form.packageName, onValueChange = { state.form = state.form.copy(packageName = it) },
            label = { Text("包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("模板选择", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.heightIn(max = 200.dp)) {
            items(state.templates, key = { it.name }) { tmpl ->
                TemplateRow(tmpl, selected = state.form.templateName == tmpl.name) {
                    state.form = state.form.copy(templateName = tmpl.name)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onPreview, enabled = state.form.projectName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("预览脚本")
        }
    }
}

@Composable
private fun TemplateRow(tmpl: ScaffoldTemplate, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(tmpl.label, style = MaterialTheme.typography.bodyLarge)
            Text(tmpl.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScriptPreview(script: String, onBack: () -> Unit, onSendToTerminal: ((String) -> Unit)? = null) {
    Column {
        Text("生成的脚本仅在 Ubuntu 终端内执行，不会直接修改本地文件系统。点击下方按钮发送到当前终端。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Surface(Modifier.fillMaxWidth().heightIn(max = 300.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(script, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                ProjectScaffoldState.onSendToTerminal?.invoke(script)
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("发送到终端执行") }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("返回修改")
        }
    }
}
