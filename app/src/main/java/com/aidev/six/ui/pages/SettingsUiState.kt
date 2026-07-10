package com.aidev.six.ui.pages

import androidx.compose.runtime.Immutable

@Immutable
data class SettingsUiState(
    val expandedSections: Set<String> = emptySet(),
    val activeDialog: SettingsDialog? = null,
    val showPathSheet: Boolean = false,
)

sealed class SettingsDialog {
    @Immutable
    data class ThemePreset(val current: String) : SettingsDialog()
    @Immutable
    data class BackgroundMode(val current: String) : SettingsDialog()
    @Immutable
    data class ShizukuStatus(
        val installed: Boolean,
        val available: Boolean,
        val statusText: String,
    ) : SettingsDialog()
    data class PathEdit(val title: String, val current: String, val onSave: (String) -> Unit) : SettingsDialog()
}
