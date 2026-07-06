package com.aidev.four.terminal

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalClientImpl(
    private var activity: Activity? = null,
    private var terminalView: TerminalView? = null,
    private var inputProxy: com.aidev.four.TerminalImeProxyEditText? = null,
    private val onInputChanged: (String) -> Unit = {},
    private val onTuiCheck: () -> Unit = {},
    private val onFontScaled: ((Float) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingFontSp = 10f
    private var fontApplyScheduled = false

    fun updateActivity(act: Activity?) { activity = act }
    fun updateTerminalView(v: TerminalView?) { terminalView = v }
    fun updateInputProxy(p: com.aidev.four.TerminalImeProxyEditText?) { inputProxy = p }

    fun setPendingFontSp(sp: Float) { pendingFontSp = sp }

    fun createViewClient(): TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val damped = 1f + (scale - 1f) * 0.55f
            pendingFontSp = (pendingFontSp * damped).coerceIn(10f, 24f)
            if (!fontApplyScheduled) {
                fontApplyScheduled = true
                terminalView?.post {
                    fontApplyScheduled = false
                    val next = pendingFontSp.coerceIn(10f, 24f)
                    onFontScaled?.invoke(next)
                }
            }
            return 1f
        }
        override fun onSingleTapUp(e: MotionEvent) {
            val proxy = inputProxy
            val act = activity ?: return
            if (proxy != null) {
                proxy.requestFocus()
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.restartInput(proxy)
            } else {
                terminalView?.requestFocus()
            }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = terminalView?.hasFocus() == true || inputProxy?.hasFocus() == true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> onInputChanged("\n")
                KeyEvent.KEYCODE_DEL -> onInputChanged("\b")
            }
            return false
        }
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (!ctrlDown && codePoint > 0) {
                onInputChanged(String(Character.toChars(codePoint)))
            }
            return false
        }
        override fun onEmulatorSet() { terminalView?.onScreenUpdated() }
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "TerminalView exception", e) }
    }
}
