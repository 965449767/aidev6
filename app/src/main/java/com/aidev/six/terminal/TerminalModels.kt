package com.aidev.six.terminal

import androidx.compose.runtime.Immutable
import com.termux.terminal.TerminalSession

@Immutable
data class EmbeddedTermSession(
    val id: Int,
    val title: String,
    val session: TerminalSession,
    val aiSession: Boolean = false,
    val locked: Boolean = false,
)

@Immutable
data class EmbeddedVirtualKey(
    val label: String,
    val input: String,
    val swipeCommand: String = "",
    val id: String = label,
    val swipeUpCommand: String = ""
)

@Immutable
data class KeyAlias(
    val name: String,
    val value: String
)

@Immutable
data class TerminalCompletion(
    val label: String,
    val insertText: String = label,
    val kind: String = "CMD"
)
