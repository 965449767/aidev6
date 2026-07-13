package com.aidev.six.ui.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.ProjectScaffoldState
import com.aidev.six.ScaffoldBaseline
import com.aidev.six.ScaffoldTemplate

@Composable
fun ProjectScaffoldDialog(
    onDismiss: () -> Unit,
    onSendToTerminal: ((String) -> Unit)? = null,
) {
    val state = ProjectScaffoldState
    var screen by remember { mutableStateOf("form") }

    val currentCallback = rememberUpdatedState(onSendToTerminal)

    LaunchedEffect(Unit) {
        state.onSendToTerminal = currentCallback.value
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Android 项目脚手架") },
        text = {
            when (screen) {
                "form" -> ScaffoldForm(onVisual = { screen = "visual" })
                "visual" -> VisualPreDevPlanning(
                    state = state,
                    onScript = { screen = "script" },
                    onBack = { screen = "form" },
                )
                else -> ScriptPreview(
                    state.generateScript(),
                    onBack = { screen = "visual" },
                    onSendToTerminal = onSendToTerminal,
                )
            }
        },
        confirmButton = {
            when (screen) {
                "form" -> TextButton(onClick = onDismiss) { Text("取消") }
                "visual" -> TextButton(onClick = { screen = "form" }) { Text("返回修改") }
                else -> TextButton(onClick = { screen = "visual" }) { Text("返回") }
            }
        },
    )
}

@Composable
private fun ScaffoldForm(onVisual: () -> Unit) {
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
        Button(onClick = onVisual, enabled = state.form.projectName.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("可视化预览（动手前先看清楚）")
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
private fun VisualPreDevPlanning(
    state: ProjectScaffoldState,
    onScript: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        item {
            Text(
                "动手前先看清：这个项目长什么样、包含哪些文件、能做什么、不能做什么。确认无误后再生成脚本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SectionTitle("① UI 模拟图（静态示意，非真实预览）")
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PhoneMockup()
            }
            Spacer(Modifier.height(12.dp))
        }
        item { SectionTitle("② 项目结构（将生成）") }
        item {
            val tree = ScaffoldBaseline.structure.replace("<package>", state.form.packageName.ifBlank { "com.example.app" })
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(tree, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }
        item { SectionTitle("③ 能力 / 权限（提前感知限制）") }
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("默认具备能力：", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    ScaffoldBaseline.capabilityNotes.forEach { cap ->
                        Text("• $cap", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("设备内受限/需谨慎的权限：", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    ScaffoldBaseline.restrictedPermissions.forEach { (perm, note) ->
                        Text("• $perm — $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "如确需上述权限，请在生成后于 AndroidManifest.xml 手动声明并评估必要性；" +
                            "完整能力边界见应用内文档 docs/compose-capabilities.md。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            Button(onClick = onScript, modifier = Modifier.fillMaxWidth()) {
                Text("查看将生成的脚本")
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回修改")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun PhoneMockup() {
    Surface(
        modifier = Modifier.width(200.dp).border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(12.dp)) {
            Surface(
                Modifier.fillMaxWidth().height(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("My App", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            repeat(3) { i ->
                Surface(
                    Modifier.fillMaxWidth().height(40.dp).padding(vertical = 3.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Box(Modifier.fillMaxSize().padding(start = 10.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                        Text("列表项 ${i + 1}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                Modifier.fillMaxWidth().height(36.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("主操作按钮", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
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
