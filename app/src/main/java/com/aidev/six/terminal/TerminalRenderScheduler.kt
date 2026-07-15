package com.aidev.six.terminal

import android.os.Handler
import android.os.Looper
import com.termux.view.TerminalView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces high-frequency terminal screen updates into at most one
 * [TerminalView.onScreenUpdated] / invalidate per [FRAME_MS] window.
 *
 * PTY output (especially fast scroll / large pastes) fires [onTextChanged]
 * many times per frame; without coalescing each event triggers a full
 * redraw, which on low-end GPUs shows as flicker / "花屏". This mirrors
 * how Chrome / VSCode / Kitty batch repaints to the display refresh rate.
 */
class TerminalRenderScheduler(
    private val terminalView: TerminalView?,
    private val monitor: TerminalPerfMonitor? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingUpdate = AtomicBoolean(false)
    private val pendingInvalidate = AtomicBoolean(false)

    private val runnable = Runnable {
        var didUpdate = false
        if (pendingUpdate.compareAndSet(true, false)) {
            didUpdate = true
            val t0 = System.nanoTime()
            terminalView?.onScreenUpdated()
            monitor?.onRenderFlush(System.nanoTime() - t0)
        }
        if (pendingInvalidate.compareAndSet(true, false)) {
            terminalView?.invalidate()
        }
        if (!didUpdate) monitor?.onInvalidateFlush()
    }

    /** Request a screen refresh, coalesced to the frame cadence. */
    fun scheduleUpdate() {
        monitor?.onUpdateScheduled()
        if (pendingUpdate.compareAndSet(false, true)) {
            handler.removeCallbacks(runnable)
            handler.postDelayed(runnable, FRAME_MS)
        }
    }

    /** Request a lightweight redraw (cursor / color change), coalesced. */
    fun scheduleInvalidate() {
        monitor?.onInvalidateScheduled()
        if (pendingInvalidate.compareAndSet(false, true)) {
            handler.removeCallbacks(runnable)
            handler.postDelayed(runnable, FRAME_MS)
        }
    }

    /** Force an immediate refresh (e.g. on session switch / attach). */
    fun flushNow() {
        handler.removeCallbacks(runnable)
        pendingUpdate.set(false)
        pendingInvalidate.set(false)
        val t0 = System.nanoTime()
        terminalView?.onScreenUpdated()
        monitor?.onRenderFlush(System.nanoTime() - t0)
    }

    fun dispose() {
        handler.removeCallbacks(runnable)
        pendingUpdate.set(false)
        pendingInvalidate.set(false)
    }

    companion object {
        /** ~60fps. */
        const val FRAME_MS: Long = 16
    }
}
