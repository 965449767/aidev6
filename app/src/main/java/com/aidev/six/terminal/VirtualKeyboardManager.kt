package com.aidev.six.terminal

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
import com.aidev.six.Constants
import com.aidev.six.GestureFeedbackManager
import com.aidev.six.PreferencesManager
import com.aidev.six.TerminalImeProxyEditText
import com.aidev.six.decodeKeyInput
import com.aidev.six.encodeKeyInput
import com.aidev.six.terminalDp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
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

    private val _keyVersion = mutableStateOf(0)
    var keyVersion: Int
        get() = _keyVersion.value
        private set(value) { _keyVersion.value = value }

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
        val output = if (input.length > 1 && !input.startsWith("\u001B") && !input.endsWith("\r")) "$input\r" else input
        session?.write(output)
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

    internal fun editVirtualKey(id: String) {
        val key = getOrderedKeys().find { it.id == id } ?: return
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
            text = "\u4E0B\u6ED1\u547D\u4EE4"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
        })
        val currentSwipeDown = if (key.swipeCommand.isNotEmpty()) "  \u5F53\u524D: ${encodeKeyInput(key.swipeCommand)}" else "  \uFF08\u672A\u8BBE\u7F6E\uFF09"
        content.addView(android.widget.TextView(act).apply {
            text = currentSwipeDown
            setTextColor(0xFF6B7280.toInt())
            textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val swipeDownInput = android.widget.EditText(act).apply {
            hint = "\u4F8B\u5982 clear\u3001pwd\u3001ls "
            setText(encodeKeyInput(key.swipeCommand))
        }
        content.addView(swipeDownInput)
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "\u4E0A\u6ED1\u547D\u4EE4"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
        })
        val currentSwipeUp = if (key.swipeUpCommand.isNotEmpty()) "  \u5F53\u524D: ${encodeKeyInput(key.swipeUpCommand)}" else "  \uFF08\u672A\u8BBE\u7F6E\uFF09"
        content.addView(android.widget.TextView(act).apply {
            text = currentSwipeUp
            setTextColor(0xFF6B7280.toInt())
            textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val swipeUpInput = android.widget.EditText(act).apply {
            hint = "\u4F8B\u5982 cd ..\u3001exit\u3001grep "
            setText(encodeKeyInput(key.swipeUpCommand))
        }
        content.addView(swipeUpInput)
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "\u5E38\u7528\u547D\u4EE4\u6A21\u677F"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
        })
        val templates = listOf("clear", "pwd", "ls -la", "cd ..", "exit", "help", "grep ", "find ", "history", "mkdir ")
        val templateRow = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        var currentRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        templates.forEachIndexed { i, cmd ->
            val btn = android.widget.TextView(act).apply {
                text = cmd
                textSize = 12f
                setTextColor(0xFFD1D5DB.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1F2937.toInt())
                    cornerRadius = 6f
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, dp(28)).apply {
                    setMargins(0, dp(3), dp(6), dp(3))
                }
                setOnClickListener {
                    val current = tapInput.text.toString()
                    tapInput.setText(if (current.isNotBlank()) "$current $cmd" else cmd)
                    tapInput.setSelection(tapInput.text.length)
                }
            }
            currentRow.addView(btn)
            if ((i + 1) % 5 == 0 && i < templates.size - 1) {
                templateRow.addView(currentRow)
                currentRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            }
        }
        templateRow.addView(currentRow)
        content.addView(templateRow)
        val aliases = getAliases()
        if (aliases.isNotEmpty()) {
            content.addView(divider())
            content.addView(android.widget.TextView(act).apply {
                text = "\u522B\u540D\u7ED1\u5B9A"
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 12f
            })
            val aliasRow = android.widget.LinearLayout(act).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            }
            var currentAliasRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            aliases.forEachIndexed { i, alias ->
                val btn = android.widget.TextView(act).apply {
                    text = alias.name
                    textSize = 12f
                    setTextColor(0xFFD1D5DB.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF374151.toInt())
                        cornerRadius = 6f
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(-2, dp(28)).apply {
                        setMargins(0, dp(3), dp(6), dp(3))
                    }
                    setOnClickListener {
                        val current = tapInput.text.toString()
                        tapInput.setText(if (current.isNotBlank()) "$current ${alias.name}" else alias.name)
                        tapInput.setSelection(tapInput.text.length)
                    }
                }
                currentAliasRow.addView(btn)
                if ((i + 1) % 5 == 0 && i < aliases.size - 1) {
                    aliasRow.addView(currentAliasRow)
                    currentAliasRow = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                }
            }
            aliasRow.addView(currentAliasRow)
            content.addView(aliasRow)
        }
        val scroll = android.widget.ScrollView(act).apply { addView(content) }
        MaterialAlertDialogBuilder(act)
            .setTitle("\u81EA\u5B9A\u4E49\u865A\u62DF\u952E: ${key.label}")
            .setView(scroll)
            .setPositiveButton("\u4FDD\u5B58") { _, _ ->
                val rawTap = decodeKeyInput(tapInput.text.toString())
                val processedTap = when {
                    rawTap.isEmpty() || rawTap == "__CTRL__" || rawTap.startsWith("\u001B") -> rawTap
                    rawTap.length == 1 -> rawTap
                    rawTap.endsWith("\r") -> rawTap
                    else -> "$rawTap\r"
                }
                saveKeyOverride(key.id, EmbeddedVirtualKey(
                    nameInput.text.toString().trim().ifBlank { key.label }.take(8),
                    processedTap,
                    decodeKeyInput(swipeDownInput.text.toString()),
                    key.id,
                    decodeKeyInput(swipeUpInput.text.toString())
                ))
                keysLoaded = false
                ensureKeysLoaded()
            }
            .setNeutralButton("\u6062\u590D\u9ED8\u8BA4") { _, _ ->
                removeKeyOverride(key.id)
                keysLoaded = false
                ensureKeysLoaded()
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    internal fun embeddedKeys(): List<EmbeddedVirtualKey> {
        val act = activity ?: return emptyList()
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val defaults = listOf(
            EmbeddedVirtualKey("ESC", "\u001B", "clear", "esc", "exit"),
            EmbeddedVirtualKey("CTRL", "__CTRL__", "", "ctrl"),
            EmbeddedVirtualKey("TAB", "\t", "help", "tab"),
            EmbeddedVirtualKey("/", "/", "cd /", "slash"),
            EmbeddedVirtualKey(".", ".", "", "dot"),
            EmbeddedVirtualKey("\u2191", "\u001B[A", "history", "up", "cd .."),
            EmbeddedVirtualKey("~", "~", "", "tilde"),
            EmbeddedVirtualKey("⏎", "\r", "", "enter"),
            EmbeddedVirtualKey("C", "c", "clear", "c"),
            EmbeddedVirtualKey("-", "-", "cd -", "dash"),
            EmbeddedVirtualKey("_", "_", "", "underscore"),
            EmbeddedVirtualKey(":", ":", "", "colon"),
            EmbeddedVirtualKey("\u2190", "\u001B[D", "\u001B[H", "left"),
            EmbeddedVirtualKey("\u2193", "\u001B[B", "ls", "down"),
            EmbeddedVirtualKey("\u2192", "\u001B[C", "\u001B[F", "right"),
            EmbeddedVirtualKey("⌫", "\u007F", "\u0015", "bspace")
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
            val swipeDown = parts.getOrNull(2).orEmpty()
            val swipeUp = parts.getOrNull(3).orEmpty()
            if (label.isEmpty() || input.isEmpty()) null else EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipeDown), "custom_$label", decodeKeyInput(swipeUp))
        }.take(8)

    private fun parseKeyOverrides(raw: String): Map<String, EmbeddedVirtualKey> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0)?.trim().orEmpty()
            val label = parts.getOrNull(1)?.trim().orEmpty()
            val input = parts.getOrNull(2).orEmpty()
            val swipeDown = parts.getOrNull(3).orEmpty()
            val swipeUp = parts.getOrNull(4).orEmpty()
            if (id.isEmpty() || label.isEmpty()) null else id to EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipeDown), id, decodeKeyInput(swipeUp))
        }.toMap()

    private fun saveKeyOverride(id: String, key: EmbeddedVirtualKey) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        val line = listOf(id, key.label, encodeKeyInput(key.input), encodeKeyInput(key.swipeCommand), encodeKeyInput(key.swipeUpCommand)).joinToString("\t")
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, (lines + line).joinToString("\n")).apply()
        keyVersion++
    }

    private fun removeKeyOverride(id: String) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, lines.joinToString("\n")).apply()
        keyVersion++
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

    internal fun getAliases(): List<KeyAlias> {
        val act = activity ?: return emptyList()
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val raw = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        return raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val name = parts.getOrNull(0)?.trim().orEmpty()
            val value = parts.getOrNull(1).orEmpty()
            if (name.isEmpty()) null else KeyAlias(name, value)
        }
    }

    internal fun saveAlias(name: String, value: String, oldName: String? = null) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        val deleteKey = oldName ?: name
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != deleteKey }
        val newLine = listOf(name, value).joinToString("\t")
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, (lines + newLine).joinToString("\n")).apply()
        keyVersion++
        syncAliasesToFile()
    }

    internal fun deleteAlias(name: String) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != name }
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, lines.joinToString("\n")).apply()
        keyVersion++
        syncAliasesToFile()
    }

    private fun syncAliasesToFile() {
        val act = activity ?: return
        val home = File(act.filesDir, "home")
        val aliasesFile = File(home, ".aidev_aliases")
        val aliases = getAliases()
        val content = aliases.joinToString("\n") { "alias ${it.name}='${it.value.replace("'", "'\\''")}'" }
        runCatching {
            aliasesFile.parentFile?.mkdirs()
            aliasesFile.writeText(content + "\n")
            aliasesFile.setReadable(true, false)
        }.onFailure { e ->
            android.util.Log.e("AIDEV_KBD", "syncAliasesToFile failed", e)
        }
    }

    fun showAliasManager() {
        val act = activity ?: return
        val aliases = getAliases()
        if (aliases.isEmpty()) {
            showAliasEditDialog(null, null)
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("\u522B\u540D\u7BA1\u7406")
            .setItems(aliases.map { "${it.name} = ${it.value}" }.toTypedArray()) { _, which ->
                showAliasActionDialog(aliases[which])
            }
            .setPositiveButton("\uFF0B \u6DFB\u52A0\u522B\u540D") { _, _ -> showAliasEditDialog(null, null) }
            .setNegativeButton("\u5173\u95ED", null)
            .show()
    }

    private fun showAliasActionDialog(alias: KeyAlias) {
        val act = activity ?: return
        MaterialAlertDialogBuilder(act)
            .setTitle(alias.name)
            .setMessage(alias.value)
            .setPositiveButton("\u7F16\u8F91") { _, _ -> showAliasEditDialog(alias.name, alias.value) }
            .setNeutralButton("\u5220\u9664") { _, _ ->
                deleteAlias(alias.name)
                showAliasManager()
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    private fun showAliasEditDialog(name: String?, value: String?) {
        val act = activity ?: return
        val dpNum = { v: Int -> terminalDp(act, v) }
        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpNum(20), dpNum(10), dpNum(20), 0)
        }
        val nameInput = android.widget.EditText(act).apply {
            hint = "\u522B\u540D"
            if (name != null) setText(name)
            setTextColor(0xFFD1D5DB.toInt())
        }
        content.addView(nameInput)
        content.addView(View(act).apply {
            setBackgroundColor(0xFF374151.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1).apply {
                topMargin = dpNum(8); bottomMargin = dpNum(8)
            }
        })
        val valueInput = android.widget.EditText(act).apply {
            hint = "\u547D\u4EE4 (\u4F8B\u5982 ls -lah)"
            if (value != null) setText(value)
            setTextColor(0xFFD1D5DB.toInt())
            minLines = 2
        }
        content.addView(valueInput)
        MaterialAlertDialogBuilder(act)
            .setTitle(if (name == null) "\u6DFB\u52A0\u522B\u540D" else "\u7F16\u8F91\u522B\u540D")
            .setView(content)
            .setPositiveButton("\u4FDD\u5B58") { _, _ ->
                val n = nameInput.text.toString().trim()
                val v = valueInput.text.toString().trim()
                if (n.isNotEmpty() && v.isNotEmpty()) {
                    saveAlias(n, v, name)
                }
                showAliasManager()
            }
            .setNegativeButton("\u53D6\u6D88") { _, _ -> showAliasManager() }
            .show()
    }
}
