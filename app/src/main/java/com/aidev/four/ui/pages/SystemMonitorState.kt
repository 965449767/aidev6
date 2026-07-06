package com.aidev.four.ui.pages

import com.aidev.four.monitor.BatteryMonitor
import com.aidev.four.monitor.DiskInfo
import com.aidev.four.monitor.ProcessInfo
import com.aidev.four.monitor.SystemMetricsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Immutable

@Immutable
data class SystemMonitorState(
    val cpu: Float = 0f,
    val memTotal: Long = 0L,
    val memAvail: Long = 0L,
    val networkInfo: String = "0 B / 0 B",
    val diskInfo: List<DiskInfo> = emptyList(),
    val processes: List<ProcessInfo> = emptyList(),
    val batteryLevel: Int = -1,
    val batteryTemp: Float = 0f,
    val batteryStatus: String = "未知",
    val batteryHealth: String = "未知",
    val batteryVoltage: Int = 0,
    val batteryCharging: String = "未知",
    val killDialogProcess: ProcessInfo? = null,
) {
    fun refresh(collector: SystemMetricsCollector, batteryMonitor: BatteryMonitor): SystemMonitorState {
        collector.collectCpuUsage()
        collector.collectNetworkTraffic()
        batteryMonitor.collectApiInfo()
        val mem = collector.getMemoryInfo()
        val rx = collector.rxSpeed; val tx = collector.txSpeed
        return copy(
            cpu = collector.cpuUsagePercent,
            memTotal = mem.memTotal,
            memAvail = mem.memAvailable,
            networkInfo = collector.formatSpeed(rx) + " / " + collector.formatSpeed(tx),
            diskInfo = collector.getDiskInfo(),
            processes = collector.getProcessList(),
            batteryLevel = batteryMonitor.level,
            batteryTemp = batteryMonitor.temp,
            batteryStatus = batteryMonitor.status,
            batteryHealth = batteryMonitor.health,
            batteryVoltage = batteryMonitor.voltage,
            batteryCharging = batteryMonitor.chargingType,
        )
    }

    fun startPolling(
        scope: CoroutineScope,
        collector: SystemMetricsCollector,
        batteryMonitor: BatteryMonitor,
        onState: (SystemMonitorState) -> Unit,
    ) {
        scope.launch {
            while (isActive) {
                try {
                    val newState = withContext(Dispatchers.IO) { refresh(collector, batteryMonitor) }
                    onState(newState)
                } catch (_: Exception) { }
                delay(3000L)
            }
        }
    }
}
