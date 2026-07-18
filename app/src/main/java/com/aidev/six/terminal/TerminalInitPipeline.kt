package com.aidev.six

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import com.aidev.six.terminal.TerminalInputBuffer
import com.aidev.six.terminal.TerminalPerfMonitor
import com.aidev.six.terminal.TerminalRenderScheduler
import com.termux.view.TerminalView
import kotlinx.coroutines.cancel

fun EmbeddedTerminalPage.initPipeline(activity: Activity) {
    this.activity = activity
    inputProxy = TerminalImeProxyEditText(activity).apply {
        onComposingChanged = { text ->
            completionEngine.composingBuffer = text
            onCompletionsChanged()
        }
        onCommittedText = inputLabel@ { text ->
            val s = sessionManager.currentTerminalSession
            if (s == null) {
                android.util.Log.w("AIDEV_INPUT", "drop committed text, session null: $text")
                return@inputLabel
            }
            val out = if (text == "\n") {
                "\r"
            } else if (tuiActive && keyboardManager.ctrlLatched && text.length == 1) {
                val codePoint = text[0].code
                val ctrlCode = when {
                    codePoint in 0x41..0x5A -> codePoint - 0x40
                    codePoint in 0x61..0x7A -> codePoint - 0x60
                    else -> codePoint
                }
                if (ctrlCode in 0x01..0x1A) {
                    String(byteArrayOf(ctrlCode.toByte()))
                } else {
                    text
                }
            } else {
                completionEngine.updateInputBuffer(text)
                text
            }
            sessionManager.inputBuffer?.append(out)
        }
        onBackspace = inputLabel@ {
            val s = sessionManager.currentTerminalSession
            if (s == null) {
                android.util.Log.w("AIDEV_INPUT", "drop backspace, session null")
                return@inputLabel
            }
            sessionManager.inputBuffer?.append("\u007F")
            completionEngine.updateInputBuffer("\b")
        }
        onEnter = inputLabel@ {
            val s = sessionManager.currentTerminalSession
            if (s == null) {
                android.util.Log.w("AIDEV_INPUT", "drop enter, session null")
                return@inputLabel
            }
            sessionManager.inputBuffer?.append("\r")
            completionEngine.updateInputBuffer("\n")
        }
        setOnFocusChangeListener(null)
    }
    inputProxy?.tuiKeyHandler = { event ->
        terminalView?.onKeyDown(event.keyCode, event) ?: false
    }
    val proxySize = (activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    terminalView = TerminalView(activity, null).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        pendingFontSp = currentFontSp(activity)
        setTextSize(spToPx(activity, pendingFontSp))
        setTerminalViewClient(terminalClient.createViewClient())
    }
    terminalClient.updateTerminalView(terminalView)
    sessionManager.updateTerminalView(terminalView)

    val monitor = TerminalPerfMonitor()
    val scheduler = TerminalRenderScheduler(terminalView, monitor)
    val buffer = TerminalInputBuffer(
        write = { text -> sessionManager.currentTerminalSession?.write(text) },
        monitor = monitor,
    )
    this.renderScheduler = scheduler
    this.perfMonitor = monitor
    sessionManager.updateRenderScheduler(scheduler)
    sessionManager.updateInputBuffer(buffer)
    monitor.start(activity)

    completionEngine.updateInputProxy(inputProxy)
    keyboardManager.updateInputProxy(inputProxy)
    sessionManager.updateInputProxy(inputProxy)
    terminalClient.updateInputProxy(inputProxy)
    keyboardManager.updateActivity(activity)
    sessionManager.updateActivity(activity)
    gestureFeedback = GestureFeedbackManager(activity, activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE))
    keyboardManager.updateGestureFeedback(gestureFeedback)

    coreContainerView = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
        addView(inputProxy, 0, LinearLayout.LayoutParams(proxySize, proxySize))
        addView(terminalView, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    sessionManager.scope?.cancel()
    sessionManager.scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    sessionManager.ensureSession {
        sessionManager.homeDir?.let { completionEngine.updateHomeDir(it) }
        keyboardManager.updateTerminalView(terminalView)
        sessionManager.initPwdObserver()
        sessionManager.startShizukuBridge()
        consumePendingCommand()
        completionEngine.focusTerminalInput()
    }
    coreContainerView?.let { v ->
        sessionManager.trackPostDelayed(v, 250) {
            completionEngine.focusTerminalInput()
        }
    }
    terminalView?.let { v ->
        sessionManager.trackPostDelayed(v, 600) {
            consumePendingCommand()
            completionEngine.focusTerminalInput()
        }
    }
}
