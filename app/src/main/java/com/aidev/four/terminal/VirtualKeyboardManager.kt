package com.aidev.four.terminal

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidev.four.Constants
import com.aidev.four.GestureFeedbackManager
import com.aidev.four.PreferencesManager
import com.aidev.four.TerminalImeProxyEditText
import com.aidev.four.decodeKeyInput
import com.aidev.four.encodeKeyInput
import com.aidev.four.parseKeyAliases
import com.aidev.four.terminalDp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlin.math.abs

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

    internal var currentKeyOrder = mutableStateListOf<String>()
    private var keysLoaded = false

    private fun ensureKeysLoaded() {
        if (!keysLoaded) {
            currentKeyOrder.clear()
            currentKeyOrder.addAll(loadKeyOrder())
            keysLoaded = true
        }
    }

    fun updateActivity(act: Activity?) { activity = act }
    fun updateSession(s: TerminalSession?) { session = s }
    fun updateTerminalView(v: TerminalView?) { terminalView = v }
    fun updateInputProxy(p: TerminalImeProxyEditText?) { inputProxy = p }
    fun updateGestureFeedback(gf: GestureFeedbackManager?) { gestureFeedback = gf }

    internal fun writeToSession(text: String) {
        session?.write(text)
    }

    internal fun getOrderedKeys(): List<EmbeddedVirtualKey> {
        ensureKeysLoaded()
        val all = embeddedKeys()
        val keyMap = all.associateBy { it.id }
        val ordered = currentKeyOrder.mapNotNull { keyMap[it] }
        val rest = all.filter { it.id !in currentKeyOrder }
        return ordered + rest
    }

    private fun loadKeyOrder(): List<String> {
        val act = activity ?: return embeddedKeys().map { it.id }
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val raw = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ORDER, "") ?: ""
        val allIds = embeddedKeys().map { it.id }
        if (raw.isBlank()) return allIds
        val saved = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (saved.size >= allIds.size) return saved
        return saved + allIds.filter { it !in saved }
    }

    fun swapKeyOrder(id1: String, id2: String) {
        ensureKeysLoaded()
        val i1 = currentKeyOrder.indexOf(id1)
        val i2 = currentKeyOrder.indexOf(id2)
        if (i1 < 0 || i2 < 0) return
        currentKeyOrder[i1] = id2
        currentKeyOrder[i2] = id1
        keysLoaded = true
    }

    internal fun saveKeyOrder() {
        ensureKeysLoaded()
        val act = activity ?: return
        val raw = currentKeyOrder.joinToString(",")
        act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .edit().putString(Constants.PrefKeys.TERMINAL_KEY_ORDER, raw).apply()
    }

    fun enterRearrangeMode() {
        isRearranging = true
        hapticTap()
    }

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
        if (ctrlLatched && input.length == 1) {
            val ch = input[0].uppercaseChar()
            if (ch in '@'..'_') {
                val b = byteArrayOf((ch.code and 0x1F).toByte())
                session?.write(b, 0, 1)
                ctrlLatched = false
                return
            }
        }
        session?.write(input)
        onInputChanged(input)
        if (ctrlLatched) {
            ctrlLatched = false
        }
    }

    fun showExtraKeysMenu() {
        val act = activity ?: return
        runCatching {
            val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            val keys = mutableListOf(
                EmbeddedVirtualKey("HOME", "\u001b[H"),
                EmbeddedVirtualKey("END", "\u001b[F"),
                EmbeddedVirtualKey("PGUP", "\u001b[5~"),
                EmbeddedVirtualKey("PGDN", "\u001b[6~"),
                EmbeddedVirtualKey("~", "~"),
                EmbeddedVirtualKey("\u6E05\u5C4F", "clear\n"),
                EmbeddedVirtualKey("Ubuntu", "ubuntu\n"),
                EmbeddedVirtualKey("\u4EFB\u52A1", "task-list\n")
            )
            keys.addAll(parseCustomKeys(prefs.getString(Constants.PrefKeys.TERMINAL_CUSTOM_KEYS, "") ?: ""))
            MaterialAlertDialogBuilder(act)
                .setTitle("\u6269\u5C55\u952E\u76D8\u66F4\u591A")
                .setItems(keys.map { it.label }.toTypedArray()) { _, which -> session?.write(keys[which].input) }
                .show()
        }.onFailure { e ->
            android.util.Log.e("AIDEV_KBD", "showExtraKeysMenu failed", e)
        }
    }

    fun showFontDialog(spToPx: (Float) -> Int, onFontApplied: (Float) -> Unit) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val current = prefs.getFloat(Constants.PrefKeys.FONT_SP, 12f).coerceIn(10f, 24f)
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(terminalDp(act, 20), terminalDp(act, 10), terminalDp(act, 20), 0)
        }
        val value = android.widget.TextView(act).apply {
            text = "${current.toInt()}sp"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
        }
        val seek = android.widget.SeekBar(act).apply {
            max = 14
            progress = current.toInt() - 10
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${10 + progress}sp"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        box.addView(value)
        box.addView(seek)
        MaterialAlertDialogBuilder(act)
            .setTitle("\u7EC8\u7AEF\u5B57\u53F7")
            .setView(box)
            .setPositiveButton("\u5E94\u7528") { _, _ ->
                val sp = (10 + seek.progress).toFloat()
                prefs.edit().putFloat(Constants.PrefKeys.FONT_SP, sp).apply()
                onFontApplied(sp)
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    internal fun showKeyAlternatives(key: EmbeddedVirtualKey) {
        showKeyAlternatives(key, null)
    }

    private fun showKeyAlternatives(key: EmbeddedVirtualKey, anchor: View?) {
        val act = activity ?: return
        val symbols = listOf("@", ":", ";", "*", "?", "$", "#", "~", ".", "_", "-", "+", "=", "%", "^", "&", "!", "\"", "'", "`")
        val dp = { v: Int -> terminalDp(act, v) }

        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val hint = android.widget.TextView(act).apply {
            text = "\u957F\u6309\u5FEB\u6377"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
        }
        content.addView(hint)

        var dialogRef: android.app.Dialog? = null
        val grid = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val row1 = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val row2 = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        symbols.take(10).forEach { sym ->
            row1.addView(createSymbolButton(act, sym, key, dp) { dialogRef?.dismiss() })
        }
        symbols.drop(10).forEach { sym ->
            row2.addView(createSymbolButton(act, sym, key, dp) { dialogRef?.dismiss() })
        }
        grid.addView(row1)
        content.addView(grid)
        grid.addView(row2)

        val actions = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(android.widget.TextView(act).apply {
            text = "\u7F16\u8F91\u952E"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { editVirtualKey(key); dialogRef?.dismiss() }
        })
        actions.addView(android.widget.TextView(act).apply {
            text = "\u66F4\u591A"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showExtraKeysMenu(); dialogRef?.dismiss() }
        })
        content.addView(actions)

        dialogRef = MaterialAlertDialogBuilder(act)
            .setView(content)
            .setNegativeButton("\u5173\u95ED", null)
            .show()
    }

    private fun createSymbolButton(act: Activity, sym: String, key: EmbeddedVirtualKey, dp: (Int) -> Int, onDismiss: () -> Unit): TextView {
        return TextView(act).apply {
            text = sym
            textSize = 14f
            setTextColor(0xFFD1D5DB.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF1F2937.toInt())
                cornerRadius = 6f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener {
                session?.write(sym)
                if (ctrlLatched) { ctrlLatched = false }
                onDismiss()
            }
        }
    }

    private fun editVirtualKey(key: EmbeddedVirtualKey) {
        val act = activity ?: return
        val dp = { v: Int -> terminalDp(act, v) }
        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val nameInput = android.widget.EditText(act).apply {
            hint = "\u6309\u94AE\u540D\u79F0"
            setText(key.label)
        }
        content.addView(nameInput)
        fun divider(): View = View(act).apply {
            setBackgroundColor(0xFF374151.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1).apply {
                topMargin = dp(12); bottomMargin = dp(8)
            }
        }
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "\u70B9\u51FB\u8F93\u5165"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
        })
        val currentTap = if (key.input.isNotEmpty()) "  \u5F53\u524D: ${encodeKeyInput(key.input)}" else "  \uFF08\u672A\u8BBE\u7F6E\uFF09"
        content.addView(android.widget.TextView(act).apply {
            text = currentTap
            setTextColor(0xFF6B7280.toInt())
            textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val tapInput = android.widget.EditText(act).apply {
            hint = "\u4F8B\u5982 c\u3001\\n \u81EA\u52A8\u56DE\u8F66\u3001\\t\u3001\\e[A"
            setText(encodeKeyInput(key.input))
        }
        content.addView(tapInput)
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "\u4E0A\u6ED1\u547D\u4EE4"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
        })
        val currentSwipe = if (key.swipeCommand.isNotEmpty()) "  \u5F53\u524D: ${encodeKeyInput(key.swipeCommand)}" else "  \uFF08\u672A\u8BBE\u7F6E\uFF09"
        content.addView(android.widget.TextView(act).apply {
            text = currentSwipe
            setTextColor(0xFF6B7280.toInt())
            textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val swipeInput = android.widget.EditText(act).apply {
            hint = "\u4F8B\u5982 clear\u3001pwd\u3001grep "
            setText(encodeKeyInput(key.swipeCommand))
        }
        content.addView(swipeInput)
        val scroll = android.widget.ScrollView(act).apply { addView(content) }
        MaterialAlertDialogBuilder(act)
            .setTitle("\u81EA\u5B9A\u4E49\u865A\u62DF\u952E: ${key.label}")
            .setView(scroll)
            .setPositiveButton("\u4FDD\u5B58") { _, _ ->
                saveKeyOverride(key.id, EmbeddedVirtualKey(
                    nameInput.text.toString().trim().ifBlank { key.label }.take(8),
                    decodeKeyInput(tapInput.text.toString()),
                    decodeKeyInput(swipeInput.text.toString()),
                    key.id
                ))
            }
            .setNeutralButton("\u6062\u590D\u9ED8\u8BA4") { _, _ ->
                removeKeyOverride(key.id)
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    internal fun embeddedKeys(): List<EmbeddedVirtualKey> {
        val act = activity ?: return emptyList()
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val defaults = listOf(
            EmbeddedVirtualKey("ESC", "\u001B", "clear", "esc"),
            EmbeddedVirtualKey("CTRL", "__CTRL__", "", "ctrl"),
            EmbeddedVirtualKey("TAB", "\t", "help", "tab"),
            EmbeddedVirtualKey("\u2191", "\u001B[A", "history", "up"),
            EmbeddedVirtualKey("/", "/", "cd /", "slash"),
            EmbeddedVirtualKey(".", ".", "", "dot"),
            EmbeddedVirtualKey("~", "~", "", "tilde"),
            EmbeddedVirtualKey("-", "-", "cd -", "dash"),
            EmbeddedVirtualKey("C", "c", "clear", "c"),
            EmbeddedVirtualKey("⏎", "\r", "", "enter"),
            EmbeddedVirtualKey("\u2190", "\u001B[D", "\u001B[H", "left"),
            EmbeddedVirtualKey("\u2193", "\u001B[B", "ls", "down"),
            EmbeddedVirtualKey("\u2192", "\u001B[C", "\u001B[F", "right"),
            EmbeddedVirtualKey("_", "_", "", "underscore"),
            EmbeddedVirtualKey(":", ":", "", "colon"),
            EmbeddedVirtualKey("SPC", " ", "pwd", "space")
        )
        val overrides = parseKeyOverrides(prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: "")
        val customizedDefaults = defaults.map { key -> overrides[key.id] ?: key }
        val custom = parseCustomKeys(prefs.getString(Constants.PrefKeys.TERMINAL_CUSTOM_KEYS, "") ?: "")
        return (customizedDefaults + custom).take(16)
    }

    private fun parseCustomKeys(raw: String): List<EmbeddedVirtualKey> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val label = parts.getOrNull(0)?.trim().orEmpty()
            val input = parts.getOrNull(1).orEmpty()
            val swipe = parts.getOrNull(2).orEmpty()
            if (label.isEmpty() || input.isEmpty()) null else EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipe), "custom_$label")
        }.take(8)

    private fun parseKeyOverrides(raw: String): Map<String, EmbeddedVirtualKey> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0)?.trim().orEmpty()
            val label = parts.getOrNull(1)?.trim().orEmpty()
            val input = parts.getOrNull(2).orEmpty()
            val swipe = parts.getOrNull(3).orEmpty()
            if (id.isEmpty() || label.isEmpty()) null else id to EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipe), id)
        }.toMap()

    private fun saveKeyOverride(id: String, key: EmbeddedVirtualKey) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        val line = listOf(id, key.label, encodeKeyInput(key.input), encodeKeyInput(key.swipeCommand)).joinToString("\t")
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, (lines + line).joinToString("\n")).apply()
    }

    private fun removeKeyOverride(id: String) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, lines.joinToString("\n")).apply()
    }

    internal fun hapticTap() {
        val gf = gestureFeedback
        if (gf != null) { gf.tick(); return }
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PrefKeys.HAPTIC_TAP, true)) return
        val view = terminalView ?: return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
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
