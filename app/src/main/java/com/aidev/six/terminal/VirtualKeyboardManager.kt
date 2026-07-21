package com.aidev.six.terminal

import android.app.Activity
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidev.six.Constants
import com.aidev.six.GestureFeedbackManager
import com.aidev.six.PreferencesManager
import com.aidev.six.TerminalImeProxyEditText
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 虚拟键盘管理器：核心状态调度与按键处理。
 *
 * 职责已拆分到以下文件以保持单一职责：
 * - KeyboardLayoutStore — 键位定义、排序、自定义覆盖的持久化
 * - KeyboardAliasStore — 别名（alias）的增删改查与文件同步
 * - KeyboardDialogs — 所有对话框 UI 构建器
 *
 * 本类仅负责：状态管理、按键输入处理、触觉反馈、外部依赖桥接。
 */
class VirtualKeyboardManager(
    private var activity: Activity? = null,
    private var session: TerminalSession? = null,
    private var gestureFeedback: GestureFeedbackManager? = null,
    private var terminalView: TerminalView? = null,
    private var inputProxy: TerminalImeProxyEditText? = null,
    private val onInputChanged: (String) -> Unit = {}
) {
    private val _ctrlLatched = mutableStateOf(false)
    var ctrlLatched: Boolean
        get() = _ctrlLatched.value
        set(value) { _ctrlLatched.value = value }

    private val _isRearranging = mutableStateOf(false)
    var isRearranging: Boolean
        get() = _isRearranging.value
        internal set(value) { _isRearranging.value = value }

    private val _keyVersion = mutableStateOf(0)
    var keyVersion: Int
        get() = _keyVersion.value
        private set(value) { _keyVersion.value = value }

    // 子模块委托
    private val layoutStore = KeyboardLayoutStore(
        getActivity = { activity },
        onLayoutChanged = { keyVersion++ }
    )
    private val aliasStore = KeyboardAliasStore(
        getActivity = { activity },
        onAliasesChanged = { keyVersion++ }
    )
    private val dialogs = KeyboardDialogs(
        getActivity = { activity },
        getSession = { session },
        getCtrlLatched = { ctrlLatched },
        setCtrlLatched = { ctrlLatched = it },
        getLayoutStore = { layoutStore },
        getAliasStore = { aliasStore },
    )

    // ── 外部依赖更新 ────────────────────────────────────────────

    fun updateActivity(act: Activity?) { activity = act }
    fun updateSession(s: TerminalSession?) { session = s }
    fun updateTerminalView(v: TerminalView?) { terminalView = v }
    fun updateInputProxy(p: TerminalImeProxyEditText?) { inputProxy = p }
    fun updateGestureFeedback(gf: GestureFeedbackManager?) { gestureFeedback = gf }

    // ── 键位与别名（委托给 layoutStore / aliasStore）────────────

    internal fun writeToSession(text: String) { session?.write(text) }

    internal fun getOrderedKeys(): List<EmbeddedVirtualKey> = layoutStore.getOrderedKeys()

    fun swapKeyOrder(id1: String, id2: String) = layoutStore.swapKeyOrder(id1, id2)

    internal fun saveKeyOrder() = layoutStore.saveKeyOrder()

    fun enterRearrangeMode() { isRearranging = true; hapticTap() }

    fun exitRearrangeMode(save: Boolean) {
        if (save) saveKeyOrder()
        isRearranging = false
        ctrlLatched = false
        hapticTap()
    }

    internal fun swipeThresholdPx(density: Float): Float {
        val act = activity ?: return 72f * density
        val multiplier = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .getFloat(Constants.PrefKeys.SWIPE_SENSITIVITY, 1.0f)
        return (72f * multiplier).coerceIn(24f, 144f) * density
    }

    // ── 按键输入处理 ────────────────────────────────────────────

    internal fun handleVirtualKeyTap(key: EmbeddedVirtualKey) {
        hapticTap()
        if (key.input == "__CTRL__") {
            ctrlLatched = !ctrlLatched
            return
        }
        val input = key.input
        if (input.isEmpty()) return
        if (session == null) {
            android.util.Log.w("AIDEV_KBD", "handleVirtualKeyTap: session is null, key=${key.label}")
        }
        // Ctrl + 单字符 → 控制字符（^A..^_ 对应 0x01..0x1F）
        if (ctrlLatched && input.length == 1) {
            val ch = input[0].uppercaseChar()
            if (ch in '@'..'_') {
                val b = byteArrayOf((ch.code and 0x1F).toByte())
                session?.write(b, 0, 1)
                ctrlLatched = false
                return
            }
        }
        // 多字符且非转义序列非回车 → 自动补回车（方便命令快捷键）
        val output = if (input.length > 1 && !input.startsWith("\u001B") && !input.endsWith("\r")) "$input\r" else input
        session?.write(output)
        onInputChanged(input)
        if (ctrlLatched) ctrlLatched = false
    }

    // ── 对话框（委托给 dialogs）────────────────────────────────

    fun showExtraKeysMenu() = dialogs.showExtraKeysMenu()

    fun showFontDialog(spToPx: (Float) -> Int, onFontApplied: (Float) -> Unit) =
        dialogs.showFontDialog(spToPx, onFontApplied)

    internal fun showKeyAlternatives(key: EmbeddedVirtualKey) = dialogs.showKeyAlternatives(key)

    internal fun editVirtualKey(id: String) = dialogs.editVirtualKey(id)

    fun showAliasManager() = dialogs.showAliasManager()

    // ── 触觉反馈 ────────────────────────────────────────────────

    internal fun hapticTap() {
        val gf = gestureFeedback
        if (gf != null) { gf.tick(); return }
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PrefKeys.HAPTIC_TAP, true)) return
        val view = terminalView ?: return
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    internal fun sendSwipeAction(action: String) {
        if (action.isEmpty()) return
        if (action.startsWith("\u001B")) {
            session?.write(action)
        } else {
            session?.write("$action\r")
        }
    }
}
