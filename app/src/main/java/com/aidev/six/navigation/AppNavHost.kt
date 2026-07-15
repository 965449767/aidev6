package com.aidev.six.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.navigation.LocalImeBottomPx
import com.aidev.six.ui.pages.TerminalPanel

@Composable
fun AppNavHost(
    terminalPage: EmbeddedTerminalPage,
) {
    val context = LocalContext.current
    val activity = context as android.app.Activity
    val imeActive = LocalImeBottomPx.current > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TerminalPanel(
                page = terminalPage,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
