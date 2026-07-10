package com.aidev.six

import androidx.compose.runtime.Immutable

@Immutable
data class WorkbenchPalette(
    val bg: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val muted: Int,
    val outline: Int,
    val accent: Int,
    val success: Int,
    val warning: Int,
    val danger: Int
)
