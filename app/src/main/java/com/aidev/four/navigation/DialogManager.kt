package com.aidev.four.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

sealed class DialogType {
    data object SFtpTransfer : DialogType()
    data object ProjectScaffold : DialogType()
}

class DialogManagerState {
    var currentDialog: DialogType? by mutableStateOf(null)
        private set

    fun show(type: DialogType) {
        currentDialog = type
    }

    fun dismiss() {
        currentDialog = null
    }
}

val LocalDialogManager = staticCompositionLocalOf { DialogManagerState() }

@Composable
fun rememberDialogManagerState(): DialogManagerState = remember { DialogManagerState() }
