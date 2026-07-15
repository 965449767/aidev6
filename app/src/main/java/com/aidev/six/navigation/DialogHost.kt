package com.aidev.six.navigation

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("") },
        text = {
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
