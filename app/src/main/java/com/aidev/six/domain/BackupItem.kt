package com.aidev.six.domain

import androidx.compose.runtime.Immutable

@Immutable
data class BackupItem(
    val id: String,
    val name: String,
    val desc: String,
    val isLarge: Boolean,
    val defaultSelected: Boolean,
    val paths: List<String>,
    val enabled: Boolean = true,
    val isInRootfs: Boolean = false
) {
    val pathCount: Int get() = paths.size
}
