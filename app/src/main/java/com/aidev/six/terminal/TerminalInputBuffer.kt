package com.aidev.six.terminal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * Buffers IME / key input and flushes it to the PTY in batches.
 *
 * Without this, every committed character is written to the PTY immediately,
 * which for a 2000-char paste means 2000 write+render cycles. Batching into a
 * ~[FLUSH_MS] window collapses that to a handful of writes — input IO drops by
 * ~90% while remaining imperceptible to the user. Control bytes (\r, \u007F,
 * TUI ctrl codes) are appended verbatim so ordering and semantics are preserved.
 */
class TerminalInputBuffer(
    private val write: (String) -> Unit,
    private val monitor: TerminalPerfMonitor? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val sb = AtomicReference(StringBuilder())

    private val flushRunnable = Runnable { flushNow() }

    fun append(text: String) {
        if (text.isEmpty()) return
        sb.get().append(text)
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, FLUSH_MS)
    }

    /** Write any pending input immediately (focus loss / command send / paste). */
    fun flushNow() {
        handler.removeCallbacks(flushRunnable)
        val b = sb.getAndSet(StringBuilder())
        if (b.isNotEmpty()) {
            val chunk = b.toString()
            write(chunk)
            monitor?.onInputFlush(chunk.toByteArray().size)
        }
    }

    fun dispose() {
        handler.removeCallbacks(flushRunnable)
        sb.set(StringBuilder())
    }

    companion object {
        /** 10ms: far below perceptual threshold, collapses bursts effectively. */
        const val FLUSH_MS: Long = 10
    }
}
