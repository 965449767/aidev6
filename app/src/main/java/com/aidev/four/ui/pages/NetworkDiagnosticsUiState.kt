package com.aidev.four.ui.pages

import androidx.compose.runtime.Immutable

@Immutable
data class NetworkDiagnosticsUiState(
    val commonPorts: List<Pair<Int, Boolean>> = emptyList(),
    val showPing: Boolean = false,
    val showHttp: Boolean = false,
    val showPortCheck: Boolean = false,
    val showDns: Boolean = false,
    val resultDialog: ResultData? = null,
)

@Immutable
data class ResultData(val title: String, val message: String)
