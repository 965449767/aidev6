package com.aidev.six.ui.pages

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.PathConfig
import com.aidev.six.PreferencesManager
import com.aidev.six.ui.components.AppActionRow
import com.aidev.six.ui.components.AppSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalMoreSheet(
    activity: Activity,
    onDismiss: () -> Unit,
) {
    val prefs = remember { PreferencesManager(activity) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showExportDialog by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val showDialog: (SettingsDialog) -> Unit = { activeDialog = it }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            AppSectionHeader("\u5916\u89C2")
            appearanceMenu(prefs, {}, showDialog).items.forEach { MenuEntryRow(it) }

            HorizontalDivider()

            AppSectionHeader("\u7CFB\u7EDF")
            systemMenu(activity, prefs, {}, showDialog).items.forEach { MenuEntryRow(it) }

            HorizontalDivider()

            AppSectionHeader("\u8DEF\u5F84")
            PathRow("\u5907\u4EFD\u76EE\u5F55", "\u5907\u4EFD\u6587\u4EF6\u548C\u6062\u590D\u6570\u636E\u7684\u5B58\u50A8\u8DEF\u5F84", PathConfig.backupDir(activity).absolutePath) {
                activeDialog = SettingsDialog.PathEdit("\u5907\u4EFD\u76EE\u5F55", PathConfig.backupDir(activity).absolutePath) { prefs.backupDir = it }
            }
            ReadonlyPathRow("\u5DE5\u4F5C\u533A\u76EE\u5F55", "\u6784\u5EFA/\u90E8\u7F72/\u626B\u63CF/\u5BFC\u5165\u7684\u771F\u6B63\u5DE5\u4F5C\u533A", PathConfig.workspaceDir(activity).absolutePath)
            PathRow("\u5916\u90E8 AIDev \u76EE\u5F84", "Android \u4FA7\u9879\u76EE\u6570\u636E\u5B58\u653E\u8DEF\u5F84", PathConfig.externalAidevDir(activity).absolutePath) {
                activeDialog = SettingsDialog.PathEdit("\u5916\u90E8 AIDev \u76EE\u5F84", PathConfig.externalAidevDir(activity).absolutePath) { prefs.externalAidevDir = it }
            }
            AppActionRow("\u5BFC\u51FA\u9879\u76EE\u6E90\u7801", onClick = { showExportDialog = true })
        }
    }

    activeDialog?.let { SettingsDialogHost(it) { activeDialog = null } }
    if (showExportDialog) {
        ExportProjectDialog(activity) { showExportDialog = false }
    }
}
