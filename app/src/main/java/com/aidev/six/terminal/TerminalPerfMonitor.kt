package com.aidev.six.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aidev.six.PathConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Observability for the terminal render/input pipeline.
 *
 * Feeds off [TerminalRenderScheduler] and [TerminalInputBuffer], samples once
 * per second, and exposes the latest [PerfSample] both to an in-app HUD
 * (via [onSample]) and to a JSON log file for offline diagnosis.
 */
class TerminalPerfMonitor {

    // ---- raw counters (monotonic) ----
    private val updateScheduled = AtomicLong(0)
    private val updateFlushed = AtomicLong(0)
    private val invalidateScheduled = AtomicLong(0)
    private val invalidateFlushed = AtomicLong(0)
    private val inputFlushes = AtomicLong(0)
    private val inputBytes = AtomicLong(0)
    private val renderTimeTotalNs = AtomicLong(0)
    private val renderTimeMaxNs = AtomicLong(0)

    /** Latest 1s sample, pushed to [onSample] each tick. */
    var onSample: ((PerfSample) -> Unit)? = null

    private var logFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // previous-window snapshots for per-second deltas
    private var prevUpdateSched = 0L
    private var prevUpdateFlush = 0L
    private var prevInvSched = 0L
    private var prevInvFlush = 0L
    private var prevInputFlush = 0L
    private var prevInputBytes = 0L
    private var prevRenderTotal = 0L
    private var prevRenderMax = 0L

    fun start(ctx: Context) {
        logFile = File(PathConfig.logsDir(ctx.applicationContext), "terminal-perf.json")
        if (!running) {
            running = true
            handler.postDelayed(sampler, SAMPLE_MS)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampler)
    }

    // ---- feed points (called from main thread) ----
    fun onUpdateScheduled() = updateScheduled.incrementAndGet()
    fun onInvalidateScheduled() = invalidateScheduled.incrementAndGet()
    fun onInvalidateFlush() = invalidateFlushed.incrementAndGet()
    fun onRenderFlush(renderNs: Long) {
        updateFlushed.incrementAndGet()
        renderTimeTotalNs.addAndGet(renderNs)
        var max = renderTimeMaxNs.get()
        while (renderNs > max) {
            if (renderTimeMaxNs.compareAndSet(max, renderNs)) break
            max = renderTimeMaxNs.get()
        }
    }

    fun onInputFlush(bytes: Int) {
        inputFlushes.incrementAndGet()
        inputBytes.addAndGet(bytes.toLong())
    }

    private val sampler: Runnable = Runnable {
        if (!running) return@Runnable
        val upS = updateScheduled.get()
        val upF = updateFlushed.get()
        val inS = invalidateScheduled.get()
        val inF = invalidateFlushed.get()
        val inFl = inputFlushes.get()
        val inB = inputBytes.get()
        val rt = renderTimeTotalNs.get()
        val rmax = renderTimeMaxNs.get()

        val updSchedPerSec = (upS - prevUpdateSched).toInt()
        val updFlushPerSec = (upF - prevUpdateFlush).toInt()
        val invSchedPerSec = (inS - prevInvSched).toInt()
        val invFlushPerSec = (inF - prevInvFlush).toInt()
        val inputFlushPerSec = (inFl - prevInputFlush).toInt()
        val inputBytesPerSec = (inB - prevInputBytes)
        val renderTotalThisSec = rt - prevRenderTotal
        val renderAvgMs = if (updFlushPerSec > 0) (renderTotalThisSec / 1_000_000) / updFlushPerSec.toDouble() else 0.0
        val renderMaxMs = (rmax - prevRenderMax) / 1_000_000.0
        val coalescedSaved = (updSchedPerSec - updFlushPerSec).coerceAtLeast(0)

        val sample = PerfSample(
            updSchedPerSec = updSchedPerSec,
            updFlushPerSec = updFlushPerSec,
            invSchedPerSec = invSchedPerSec,
            invFlushPerSec = invFlushPerSec,
            inputFlushPerSec = inputFlushPerSec,
            inputBytesPerSec = inputBytesPerSec,
            renderAvgMs = renderAvgMs,
            renderMaxMs = renderMaxMs,
            coalescedSaved = coalescedSaved,
        )
        onSample?.invoke(sample)
        writeLog(sample)

        prevUpdateSched = upS
        prevUpdateFlush = upF
        prevInvSched = inS
        prevInvFlush = inF
        prevInputFlush = inFl
        prevInputBytes = inB
        prevRenderTotal = rt
        prevRenderMax = rmax

        handler.postDelayed(sampler, SAMPLE_MS)
    }

    private fun writeLog(s: PerfSample) {
        val f = logFile ?: return
        try {
            f.writeText(
                """{"t":${System.currentTimeMillis()},"updSched":${s.updSchedPerSec},"updFlush":${s.updFlushPerSec},"invSched":${s.invSchedPerSec},"invFlush":${s.invFlushPerSec},"inputFlush":${s.inputFlushPerSec},"inputBytes":${s.inputBytesPerSec},"renderAvgMs":${"%.3f".format(s.renderAvgMs)},"renderMaxMs":${"%.3f".format(s.renderMaxMs)},"coalescedSaved":${s.coalescedSaved}}"""
            )
        } catch (_: Exception) {
            // logging must never affect terminal perf
        }
    }

    companion object {
        const val SAMPLE_MS: Long = 1000
    }
}

data class PerfSample(
    val updSchedPerSec: Int,
    val updFlushPerSec: Int,
    val invSchedPerSec: Int,
    val invFlushPerSec: Int,
    val inputFlushPerSec: Int,
    val inputBytesPerSec: Long,
    val renderAvgMs: Double,
    val renderMaxMs: Double,
    val coalescedSaved: Int,
)
