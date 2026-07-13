package com.aidev.six.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.ShellActivity.Companion.TAB_KNOWLEDGE
import com.aidev.six.ShellActivity.Companion.TAB_SERVER
import com.aidev.six.ShellActivity.Companion.TAB_SETTINGS
import com.aidev.six.ShellActivity.Companion.TAB_TERMINAL
import com.aidev.six.ui.components.EdgeSwipePanel
import com.aidev.six.ui.components.PanelIndicatorDots
import com.aidev.six.ui.components.PanelType
import com.aidev.six.ui.pages.KnowledgeBasePanel
import com.aidev.six.ui.pages.ServerPanel
import com.aidev.six.ui.pages.SettingsPanel
import com.aidev.six.ui.pages.TerminalPanel
import com.aidev.six.ui.theme.LocalBackgroundConfig

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

    var currentPanel by remember { mutableStateOf<PanelType?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EdgeSwipePanel(
                enabled = true,
                currentPanel = currentPanel,
                onPanelOpen = { currentPanel = it },
                onPanelClose = { currentPanel = null },
                imeActive = imeActive,
                excludeTop = 96.dp,
                settingsContent = {
                    SettingsPanel(
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                knowledgeContent = {
                    KnowledgeBasePanel(
                        onExecuteCommand = onExecuteCommand,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                serverContent = {
                    ServerPanel(
                        onExecuteCommand = onExecuteCommand,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                content = {
                    TerminalPanel(
                        page = terminalPage,
                        modifier = Modifier.fillMaxSize(),
                    )

                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "tab_content",
                    ) { tab ->
                        when (tab) {
                            TAB_TERMINAL -> Box(Modifier.fillMaxSize())
                            TAB_SETTINGS -> SettingsPanel(modifier = Modifier.fillMaxSize())
                            TAB_KNOWLEDGE -> KnowledgeBasePanel(
                                onExecuteCommand = onExecuteCommand,
                                modifier = Modifier.fillMaxSize(),
                            )
                            TAB_SERVER -> ServerPanel(
                                onExecuteCommand = onExecuteCommand,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
            )
        }

        if (!imeActive) {
            PanelIndicatorDots(
                currentPanel = currentPanel,
            )
        }
    }
}
