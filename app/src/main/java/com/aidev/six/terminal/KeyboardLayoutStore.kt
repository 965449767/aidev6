package com.aidev.six.terminal

import android.app.Activity
import com.aidev.six.Constants
import com.aidev.six.decodeKeyInput
import com.aidev.six.encodeKeyInput

/**
 * 键盘布局存储：键位定义、排序、自定义覆盖的持久化管理。
 * 从 VirtualKeyboardManager.kt 中拆分出来，保持单一职责。
 */
internal class KeyboardLayoutStore(
    private val getActivity: () -> Activity?,
    private val onLayoutChanged: () -> Unit = {}
) {
    private var currentKeyOrder = mutableListOf<String>()
    private var keysLoaded = false

    private fun ensureKeysLoaded() {
        if (!keysLoaded) {
            currentKeyOrder.clear()
            currentKeyOrder.addAll(loadKeyOrder())
            keysLoaded = true
        }
    }

    fun getOrderedKeys(): List<EmbeddedVirtualKey> {
        ensureKeysLoaded()
        val all = embeddedKeys()
        val keyMap = all.associateBy { it.id }
        val ordered = currentKeyOrder.mapNotNull { keyMap[it] }
        val rest = all.filter { it.id !in currentKeyOrder }
        return ordered + rest
    }

    private fun loadKeyOrder(): List<String> {
        val act = getActivity() ?: return embeddedKeys().map { it.id }
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
    }

    fun saveKeyOrder() {
        ensureKeysLoaded()
        val act = getActivity() ?: return
        val raw = currentKeyOrder.joinToString(",")
        act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .edit().putString(Constants.PrefKeys.TERMINAL_KEY_ORDER, raw).apply()
    }

    // ── 键位定义与覆盖 ────────────────────────────────────────────

    fun embeddedKeys(): List<EmbeddedVirtualKey> {
        val act = getActivity() ?: return emptyList()
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val defaults = listOf(
            EmbeddedVirtualKey("ESC", "\u001B", "clear", "esc", "exit"),
            EmbeddedVirtualKey("CTRL", "__CTRL__", "", "ctrl"),
            EmbeddedVirtualKey("TAB", "\t", "help", "tab"),
            EmbeddedVirtualKey("/", "/", "cd /", "slash"),
            EmbeddedVirtualKey(".", ".", "", "dot"),
            EmbeddedVirtualKey("\u2191", "\u001B[A", "history", "up", "cd .."),
            EmbeddedVirtualKey("~", "~", "", "tilde"),
            EmbeddedVirtualKey("\u23CE", "\r", "", "enter"),
            EmbeddedVirtualKey("C", "c", "clear", "c"),
            EmbeddedVirtualKey("-", "-", "cd -", "dash"),
            EmbeddedVirtualKey("_", "_", "", "underscore"),
            EmbeddedVirtualKey(":", ":", "", "colon"),
            EmbeddedVirtualKey("\u2190", "\u001B[D", "\u001B[H", "left"),
            EmbeddedVirtualKey("\u2193", "\u001B[B", "ls", "down"),
            EmbeddedVirtualKey("\u2192", "\u001B[C", "\u001B[F", "right"),
            EmbeddedVirtualKey("\u232B", "\u007F", "\u0015", "bspace")
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
            if (label.isEmpty() || input.isEmpty()) null
            else EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipeDown), "custom_$label", decodeKeyInput(swipeUp))
        }.take(8)

    private fun parseKeyOverrides(raw: String): Map<String, EmbeddedVirtualKey> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val id = parts.getOrNull(0)?.trim().orEmpty()
            val label = parts.getOrNull(1)?.trim().orEmpty()
            val input = parts.getOrNull(2).orEmpty()
            val swipeDown = parts.getOrNull(3).orEmpty()
            val swipeUp = parts.getOrNull(4).orEmpty()
            if (id.isEmpty() || label.isEmpty()) null
            else id to EmbeddedVirtualKey(label.take(8), decodeKeyInput(input), decodeKeyInput(swipeDown), id, decodeKeyInput(swipeUp))
        }.toMap()

    fun saveKeyOverride(id: String, key: EmbeddedVirtualKey) {
        val act = getActivity() ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        val line = listOf(id, key.label, encodeKeyInput(key.input), encodeKeyInput(key.swipeCommand), encodeKeyInput(key.swipeUpCommand)).joinToString("\t")
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, (lines + line).joinToString("\n")).apply()
        keysLoaded = false
        ensureKeysLoaded()
        onLayoutChanged()
    }

    fun removeKeyOverride(id: String) {
        val act = getActivity() ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != id }
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, lines.joinToString("\n")).apply()
        keysLoaded = false
        ensureKeysLoaded()
        onLayoutChanged()
    }
}
