package com.aidev.six.ui.pages

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidev.six.PathConfig
import com.aidev.six.ProjectDetector
import com.aidev.six.ProjectExporter
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ExportPhase { LOADING, EMPTY, SELECTING, OPTIONS, EXPORTING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportProjectDialog(
    activity: Activity,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(ExportPhase.LOADING) }
    var projects by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) {
            val ws = PathConfig.workspaceDir(activity)
            runCatching { ProjectDetector.findAndroidProjects(ws) }.getOrDefault(emptyList())
        }
        phase = when {
            projects.isEmpty() -> ExportPhase.EMPTY
            projects.size == 1 -> {
                selected = projects.first()
                ExportPhase.OPTIONS
            }
            else -> ExportPhase.SELECTING
        }
    }

    when (phase) {
        ExportPhase.LOADING -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("\u5BFC\u51FA\u9879\u76EE\u6E90\u7801") },
                text = { Text("\u6B63\u5728\u626B\u63CF workspace \u4E2D\u7684\u9879\u76EE\u2026") },
                confirmButton = {},
            )
        }

        ExportPhase.EMPTY -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("\u5BFC\u51FA\u9879\u76EE\u6E90\u7801") },
                text = {
                    Text("workspace \u4E2D\u672A\u68C0\u6D4B\u5230 Android \u9879\u76EE\u3002\u8BF7\u5148\u5728\u7EC8\u7AEF\u4E2D\u5BFC\u5165\u6216\u521B\u5EFA\u9879\u76EE\u3002")
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("\u786E\u5B9A") }
                },
            )
        }

        ExportPhase.SELECTING -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("\u9009\u62E9\u9879\u76EE") },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    ) {
                        items(projects, key = { it.absolutePath }) { dir ->
                            val meta = remember(dir) {
                                runCatching { ProjectDetector.getProjectMeta(dir) }.getOrNull()
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = dir
                                        phase = ExportPhase.OPTIONS
                                    }
                                    .padding(horizontal = Spacing.s16, vertical = 10.dp),
                            ) {
                                Text(
                                    text = meta?.name ?: dir.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (meta != null) {
                                    Text(
                                        text = "${meta.language}  \u00B7  ${dir.absolutePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") }
                },
            )
        }

        ExportPhase.OPTIONS, ExportPhase.EXPORTING -> {
            val projectDir = selected ?: return
            ExportOptionsDialog(activity, projectDir, phase, onDismiss) {
                phase = ExportPhase.EXPORTING
                scope.launch(Dispatchers.IO) {
                    try {
                        val outName = "${projectDir.name}-source.md"
                        val outFile = File(PathConfig.externalAidevDir(activity), outName)
                        ProjectExporter.exportToFile(projectDir, outFile)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(activity, "\u5DF2\u5BFC\u51FA\u6E90\u7801\uFF1A${outFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            phase = ExportPhase.OPTIONS
                            android.widget.Toast.makeText(activity, "\u5BFC\u51FA\u5931\u8D25\uFF1A${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportOptionsDialog(
    activity: Activity,
    projectDir: File,
    phase: ExportPhase,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    var includeGit by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (phase != ExportPhase.EXPORTING) onDismiss() },
        title = { Text("\u5BFC\u51FA\u9879\u76EE\u6E90\u7801") },
        text = {
            Column {
                Text(projectDir.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    projectDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.s12))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { includeGit = !includeGit }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeGit, onCheckedChange = { includeGit = it })
                    Spacer(Modifier.width(Spacing.s8))
                    Text("\u5305\u542B .git", style = MaterialTheme.typography.bodyMedium)
                }
                if (phase == ExportPhase.EXPORTING) {
                    Spacer(Modifier.height(Spacing.s8))
                    Text("\u5BFC\u51FA\u4E2D\u2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onExport,
                enabled = phase != ExportPhase.EXPORTING,
            ) { Text(if (phase == ExportPhase.EXPORTING) "\u5BFC\u51FA\u4E2D\u2026" else "\u5BFC\u51FA") }
        },
        dismissButton = {
            TextButton(onClick = { if (phase != ExportPhase.EXPORTING) onDismiss() }) { Text("\u53D6\u6D88") }
        },
    )
}
