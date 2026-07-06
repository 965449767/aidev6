package com.aidev.four.monitor

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class SystemMetricsCollector {
    private var prevCpuTotal: Long = 0L
    private var prevCpuIdle: Long = 0L
    private var prevRxBytes: Long = 0L
    private var prevTxBytes: Long = 0L
    var rxSpeed: Long = 0L; private set
    var txSpeed: Long = 0L; private set
    var cpuUsagePercent: Float = 0f; private set

    fun collectCpuUsage() {
        try {
            val lines = File("/proc/stat").readLines()
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") } ?: return
            val parts = cpuLine.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 5) return
            var total: Long = 0
            for (i in 1 until parts.size) {
                total += parts[i].toLongOrNull() ?: 0L
            }
            val idle = (parts[4].toLongOrNull() ?: 0L) + (parts[5].toLongOrNull() ?: 0L)
            if (prevCpuTotal > 0) {
                val diffTotal = total - prevCpuTotal
                val diffIdle = idle - prevCpuIdle
                if (diffTotal > 0) {
                    cpuUsagePercent = ((diffTotal - diffIdle).toFloat() / diffTotal.toFloat()) * 100f
                }
            }
            prevCpuTotal = total
            prevCpuIdle = idle
        } catch (e: Exception) {
            Log.w("SysMetrics", "collectCpuUsage failed", e)
        }
    }

    fun collectNetworkTraffic() {
        try {
            val lines = File("/proc/net/dev").readLines()
            var totalRx: Long = 0
            var totalTx: Long = 0
            for (line in lines) {
                if (!line.contains(":")) continue
                val iface = line.substringBefore(":").trim()
                if (iface == "lo") continue
                val parts = line.substringAfter(":").trim().split(Regex("\\s+"))
                if (parts.size >= 10) {
                    totalRx += parts[1].toLongOrNull() ?: 0L
                    totalTx += parts[9].toLongOrNull() ?: 0L
                }
            }
            if (prevRxBytes > 0) {
                rxSpeed = totalRx - prevRxBytes
                txSpeed = totalTx - prevTxBytes
            }
            prevRxBytes = totalRx
            prevTxBytes = totalTx
        } catch (e: Exception) {
            Log.w("SysMetrics", "collectNetworkTraffic failed", e)
        }
    }

    fun getMemoryInfo(): MemInfo {
        val info = MemInfo()
        try {
            val lines = File("/proc/meminfo").readLines()
            for (line in lines) {
                val pair = line.split(Regex(":"), limit = 2).let {
                    if (it.size == 2) it[0].trim() to it[1].trim() else null
                } ?: continue
                val key = pair.first
                val value = pair.second
                val kb = value.removeSuffix(" kB").trim().toLongOrNull() ?: continue
                when {
                    key == "MemTotal" -> info.memTotal = kb
                    key == "MemAvailable" -> info.memAvailable = kb
                    key == "MemFree" -> info.memFree = kb
                    key == "Buffers" -> info.buffers = kb
                    key == "Cached" -> info.cached = kb
                    key == "SwapTotal" -> info.swapTotal = kb
                    key == "SwapFree" -> info.swapFree = kb
                }
            }
        } catch (e: Exception) {
            Log.w("SysMetrics", "getMemoryInfo failed", e)
        }
        return info
    }

    fun getDiskInfo(): List<DiskInfo> {
        val result = mutableListOf<DiskInfo>()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("df", "-h"))
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                var first = true
                while (reader.readLine().also { line = it } != null) {
                    if (first) { first = false; continue }
                    val l = line ?: continue
                    val parts = l.trim().split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        result.add(DiskInfo(
                            filesystem = parts[0],
                            size = parts[1],
                            used = parts[2],
                            available = parts[3],
                            usePercent = parts[4],
                            mountedOn = parts[5]
                        ))
                    }
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (_: Exception) { } finally {
            process?.destroy()
        }
        return result
    }

    fun getProcessList(): List<ProcessInfo> {
        val result = mutableListOf<ProcessInfo>()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps aux --sort=-%cpu 2>/dev/null || ps aux"))
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                var first = true
                while (reader.readLine().also { line = it } != null) {
                    if (first) { first = false; continue }
                    val l = line ?: continue
                    val parts = l.trim().split(Regex("\\s+"))
                    if (parts.size >= 11) {
                        result.add(ProcessInfo(
                            user = parts[0],
                            pid = parts[1],
                            cpu = parts[2],
                            mem = parts[3],
                            vsz = parts[4],
                            rss = parts[5],
                            stat = parts[7].takeIf { parts.size > 7 } ?: "",
                            command = parts.drop(10).joinToString(" ")
                        ))
                    }
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (_: Exception) { } finally {
            process?.destroy()
        }
        return result.take(15)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024 -> "${bytesPerSec} B/s"
            bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    fun parseDiskPercent(): String {
        return try {
            val diskInfo = getDiskInfo()
            val rootDisk = diskInfo.firstOrNull { it.mountedOn == "/" }
                ?: diskInfo.firstOrNull()
            rootDisk?.usePercent ?: "N/A"
        } catch (_: Exception) { "N/A" }
    }
}
