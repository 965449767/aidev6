package com.aidev.four

import androidx.compose.runtime.Immutable

@Immutable
data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val isSuccess get() = exitCode == 0
}
