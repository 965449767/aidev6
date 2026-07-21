package com.aidev.six

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidev.six.terminal.CompletionEngine
import com.aidev.six.terminal.EmbeddedTermSession
import com.aidev.six.terminal.SessionManager
import com.aidev.six.terminal.TerminalClientImpl
import com.aidev.six.terminal.TerminalCompletion
import com.aidev.six.terminal.TerminalPerfMonitor
import com.aidev.six.terminal.TerminalRenderScheduler
import com.aidev.six.terminal.VirtualKeyboardManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EmbeddedTerminalPage {
    private companion object {
        const val DEFAULT_FONT_SP = 12f
        const val MIN_FONT_SP = 10f
        const val MAX_FONT_SP = 24f
    }

    var activity: Activity? = null
        internal set
    var terminalView: TerminalView? = null
        internal set
    var inputProxy: com.aidev.six.TerminalImeProxyEditText? = null
        internal set
    private val _completionSnapshot = mutableStateOf(emptyList<TerminalCompletion>())
    val completionSnapshot: State<List<TerminalCompletion>> = _completionSnapshot
    var coreContainerView: View? = null
        internal set
    var tuiActive by mutableStateOf(false)
        private set
    val completionEngine = CompletionEngine(onCompletionsChanged = { onCompletionsChanged() })
    val keyboardManager = VirtualKeyboardManager(onInputChanged = { text -> completionEngine.updateInputBuffer(text) })
    internal var gestureFeedback: GestureFeedbackManager? = null
    val sessionManager = SessionManager(completionEngine = completionEngine, keyboardManager = keyboardManager)
    var renderScheduler: TerminalRenderScheduler? = null
    var perfMonitor: TerminalPerfMonitor? = null
    internal val terminalClient = TerminalClientImpl(
        onInputChanged = { text -> completionEngine.updateInputBuffer(text) },
        onTuiCheck = { updateTuiMode() },
        onFontScaled = { sp -> applyFontSp(sp) }
    )

    internal var pendingFontSp = DEFAULT_FONT_SP

    internal fun spToPx(activity: Activity, sp: Float): Int {
        val config = activity.resources.configuration
        return (sp * config.fontScale * config.densityDpi / 160f).toInt()
    }

    private fun updateTuiMode() {
        val s = sessionManager.currentTerminalSession ?: return
        val isTui = s.isAlternateScreenActive()
        inputProxy?.tuiMode = isTui
        if (isTui != tuiActive) {
            tuiActive = isTui
            if (!isTui) inputProxy?.requestFocus()
        }
    }

    fun init(activity: Activity) {
        initPipeline(activity)
    }

    fun currentFontSp(activity: Activity): Float =
        activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .getFloat(Constants.PrefKeys.FONT_SP, DEFAULT_FONT_SP)
            .coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    fun applyFontSp(sp: Float) {
        val act = activity ?: return
        val value = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE).edit().putFloat(Constants.PrefKeys.FONT_SP, value).apply()
        pendingFontSp = value
        terminalView?.setTextSize(spToPx(act, value))
        renderScheduler?.flushNow() ?: terminalView?.onScreenUpdated()
    }

    private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var completionJob: Job? = null

    internal fun onCompletionsChanged() {
        // 输入热路径防抖：合并 100ms 内的连续按键，避免每次按键全量重算补全。
        completionJob?.cancel()
        completionJob = completionScope.launch {
            delay(100)
            _completionSnapshot.value = completionEngine.completionSuggestions()
        }
    }

    internal fun showCompletionMenu(activity: Activity, item: TerminalCompletion) {
        val options = if (item.kind == "PIN") {
            arrayOf("\u8865\u5168", "\u6267\u884C\u5E76\u56DE\u8F66", "\u590D\u5236\u547D\u4EE4", "\u53D6\u6D88\u56FA\u5B9A")
        } else {
            arrayOf("\u8865\u5168", "\u6267\u884C\u5E76\u56DE\u8F66", "\u590D\u5236\u547D\u4EE4", "\u56FA\u5B9A\u5230\u5E38\u7528")
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(item.label)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "\u8865\u5168" -> { sessionManager.flushInput(); completionEngine.applyCompletion(item) }
                    "\u6267\u884C\u5E76\u56DE\u8F66" -> { sessionManager.flushInput(); completionEngine.executeCompletion(item) }
                    "\u590D\u5236\u547D\u4EE4" -> {
                        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("AIDev command", item.insertText))
                        Toast.makeText(activity, "\u5DF2\u590D\u5236\u547D\u4EE4", Toast.LENGTH_SHORT).show()
                    }
                    "\u56FA\u5B9A\u5230\u5E38\u7528" -> completionEngine.pinCompletion(item)
                    "\u53D6\u6D88\u56FA\u5B9A" -> completionEngine.unpinCompletion(item)
                }
            }
            .show()
    }

    private fun showGroupedActionMenu(activity: Activity, title: String, prefKey: String, actions: List<Pair<String, () -> Unit>>) {
        val recent = recentMenuLabels(activity, prefKey).filter { label -> actions.any { it.first == label } }
        val sorted = recent + actions.filterNot { recent.contains(it.first) }.map { it.first }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(sorted.toTypedArray()) { _, which ->
                val selected = sorted.getOrNull(which) ?: return@setItems
                rememberMenuLabel(activity, prefKey, selected)
                actions.firstOrNull { it.first == selected }?.second?.invoke()
            }
            .setNegativeButton("\u5173\u95ED", null)
            .show()
    }

    private fun recentMenuLabels(activity: Activity, key: String): List<String> =
        activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE).getString(key, "")?.lines()?.filter { it.isNotBlank() }.orEmpty().takeLast(3).reversed()

    private fun rememberMenuLabel(activity: Activity, key: String, label: String) {
        val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(key, "")?.lines()?.filter { it.isNotBlank() && it != label }.orEmpty()
        prefs.edit().putString(key, (old + label).takeLast(6).joinToString("\n")).apply()
    }

    internal fun consumePendingCommand() = sessionManager.consumePendingCommand()

    fun onSelected(activity: Activity) {
        this.activity = activity
        completionEngine.updateActivity(activity)
        keyboardManager.updateActivity(activity)
        if (gestureFeedback == null) {
        gestureFeedback = GestureFeedbackManager(activity, activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE))
            keyboardManager.updateGestureFeedback(gestureFeedback)
        }
        sessionManager.updateActivity(activity)
        terminalClient.updateActivity(activity)
        sessionManager.scope?.cancel()
        sessionManager.scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        sessionManager.ensureSession()
        sessionManager.homeDir?.let { completionEngine.updateHomeDir(it) }
        sessionManager.initPwdObserver()
        completionEngine.focusTerminalInput()
        sessionManager.consumePendingCommand()
        terminalView?.let { v ->
            sessionManager.trackPostDelayed(v, 600) {
                completionEngine.focusTerminalInput()
            }
        }
    }

    fun onDestroy(activity: Activity) {
        disposeTerminal()
        sessionManager.cleanup()
        completionScope.cancel()
        this.activity = null
    }

    private fun startShizukuBridge() = sessionManager.startShizukuBridge()

    fun toggleTuiMode(activity: Activity) {
        tuiActive = !tuiActive
        inputProxy?.tuiMode = tuiActive
        Toast.makeText(activity, if (tuiActive) "TUI \u6A21\u5F0F\u5DF2\u5F00\u542F" else "TUI \u6A21\u5F0F\u5DF2\u5173\u95ED", Toast.LENGTH_SHORT).show()
        if (!tuiActive) {
            inputProxy?.requestFocus()
        }
    }

    fun silentCd(ubuntuPath: String) { sessionManager.flushInput(); sessionManager.silentCd(ubuntuPath) }
    fun prefillCdCommand(ubuntuPath: String) { sessionManager.flushInput(); sessionManager.prefillCdCommand(ubuntuPath) }

    fun flushInput() = sessionManager.flushInput()

    fun disposeTerminal() {
        renderScheduler?.dispose()
        sessionManager.inputBuffer?.dispose()
        perfMonitor?.stop()
        renderScheduler = null
        perfMonitor = null
    }
}

private fun com.termux.terminal.TerminalSession.isAlternateScreenActive(): Boolean {
    return try {
        val emulator = javaClass.getMethod("getEmulator").invoke(this)
        emulator.javaClass.getMethod("isAlternateScreenActive").invoke(emulator) as Boolean
    } catch (_: Exception) {
        false
    }
}
