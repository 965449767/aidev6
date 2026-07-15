package com.aidev.six.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.ShellActivity.Companion.TAB_SETTINGS
import com.aidev.six.ShellActivity.Companion.TAB_TERMINAL
import com.aidev.six.ui.components.EdgeSwipePanel
import com.aidev.six.ui.components.PanelIndicatorDots
import com.aidev.six.ui.components.PanelType
import com.aidev.six.ui.pages.SettingsPanel
import com.aidev.six.navigation.LocalImeBottomPx
import com.aidev.six.ui.pages.TerminalPanel

@Composable
fun AppNavHost(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    onExecuteCommand: (String) -> Unit = {},
    terminalPage: EmbeddedTerminalPage,
) {
    val context = LocalContext.current
    val activity = context as android.app.Activity
    val imeActive = LocalImeBottomPx.current > 0

    var settingsOpen by remember { mutableStateOf(currentTab == TAB_SETTINGS) }
    androidx.compose.runtime.LaunchedEffect(currentTab) { settingsOpen = currentTab == TAB_SETTINGS }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EdgeSwipePanel(
                enabled = true,
                currentPanel = if (settingsOpen) PanelType.SETTINGS else null,
                onPanelOpen = { if (it == PanelType.SETTINGS) { settingsOpen = true; onTabSelected(TAB_SETTINGS) } },
                onPanelClose = { settingsOpen = false; onTabSelected(TAB_TERMINAL) },
                imeActive = imeActive,
                settingsContent = {
                    SettingsPanel(
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                content = {
                    TerminalPanel(
                        page = terminalPage,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }

        if (!imeActive) {
            PanelIndicatorDots(
                currentPanel = if (settingsOpen) PanelType.SETTINGS else null,
            )
        }
    }
}
