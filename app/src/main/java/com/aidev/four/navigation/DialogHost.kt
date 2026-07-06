package com.aidev.four.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aidev.four.ui.pages.ProjectScaffoldDialog
import com.aidev.four.ui.pages.SFtpDialog

@Composable
fun DialogHost(
    onExecuteCommand: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val dialogManager = LocalDialogManager.current
    content()

    val dialogType = dialogManager.currentDialog
    if (dialogType != null) {
        ComposeDialogWrapper(
            type = dialogType,
            onExecuteCommand = onExecuteCommand,
            onDismiss = { dialogManager.dismiss() },
        )
    }
}

@Composable
private fun ComposeDialogWrapper(
    type: DialogType,
    onExecuteCommand: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        is DialogType.SFtpTransfer -> "SFTP 文件传输"
        is DialogType.ProjectScaffold -> "Android 项目脚手架"
    }

    var ready by remember(type) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (type) {
                is DialogType.SFtpTransfer -> SFtpDialog(onDismiss = onDismiss)
                is DialogType.ProjectScaffold -> {
                    val context = LocalContext.current
                    ProjectScaffoldDialog(
                        onDismiss = onDismiss,
                        onSendToTerminal = { script ->
                            val file = java.io.File(context.cacheDir, "scaffold_gen.sh")
                            file.writeText(script)
                            file.setExecutable(true)
                            onExecuteCommand("sh ${file.absolutePath}")
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
