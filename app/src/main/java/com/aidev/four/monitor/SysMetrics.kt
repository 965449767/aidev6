package com.aidev.four.monitor

import androidx.compose.runtime.Immutable

@Immutable
data class MemInfo(
    var memTotal: Long = 0L,
    var memAvailable: Long = 0L,
    var memFree: Long = 0L,
    var buffers: Long = 0L,
    var cached: Long = 0L,
    var swapTotal: Long = 0L,
    var swapFree: Long = 0L
)

@Immutable
data class DiskInfo(
    val filesystem: String,
    val size: String,
    val used: String,
    val available: String,
    val usePercent: String,
    val mountedOn: String
)

@Immutable
data class ProcessInfo(
    val user: String,
    val pid: String,
    val cpu: String,
    val mem: String,
    val vsz: String,
    val rss: String,
    val stat: String,
    val command: String
)
