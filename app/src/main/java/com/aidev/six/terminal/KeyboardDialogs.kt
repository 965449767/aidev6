package com.aidev.six.terminal

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.aidev.six.Constants
import com.aidev.six.decodeKeyInput
import com.aidev.six.encodeKeyInput
import com.aidev.six.terminalDp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalSession

/**
 * 键盘对话框构建器：所有与虚拟键盘相关的对话框 UI 弹窗。
 * 从 VirtualKeyboardManager.kt 中拆分出来，保持单一职责。
 */
internal class KeyboardDialogs(
    private val getActivity: () -> Activity?,
    private val getSession: () -> TerminalSession?,
    private val getCtrlLatched: () -> Boolean,
    private val setCtrlLatched: (Boolean) -> Unit,
    private val getLayoutStore: () -> KeyboardLayoutStore,
    private val getAliasStore: () -> KeyboardAliasStore,
) {
    fun showExtraKeysMenu() {
        val act = getActivity() ?: return
        runCatching {
            val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            val keys = mutableListOf(
                EmbeddedVirtualKey("HOME", "\u001b[H"),
                EmbeddedVirtualKey("END", "\u001b[F"),
                EmbeddedVirtualKey("PGUP", "\u001b[5~"),
                EmbeddedVirtualKey("PGDN", "\u001b[6~"),
                EmbeddedVirtualKey("~", "~"),
                EmbeddedVirtualKey("清屏", "clear\n"),
                EmbeddedVirtualKey("Ubuntu", "ubuntu\n"),
                EmbeddedVirtualKey("任务", "task-list\n")
            )
            keys.addAll(parseCustomKeys(prefs.getString(Constants.PrefKeys.TERMINAL_CUSTOM_KEYS, "") ?: ""))
            MaterialAlertDialogBuilder(act)
                .setTitle("扩展键盘更多")
                .setItems(keys.map { it.label }.toTypedArray()) { _, which -> getSession()?.write(keys[which].input) }
                .show()
        }.onFailure { e ->
            android.util.Log.e("AIDEV_KBD", "showExtraKeysMenu failed", e)
        }
    }

    private fun parseCustomKeys(raw: String): List<EmbeddedVirtualKey> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val label = parts.getOrNull(0)?.trim().orEmpty()
            val input = parts.getOrNull(1).orEmpty()
            if (label.isEmpty() || input.isEmpty()) null
            else EmbeddedVirtualKey(label.take(8), decodeKeyInput(input))
        }.take(8)

    fun showFontDialog(spToPx: (Float) -> Int, onFontApplied: (Float) -> Unit) {
        val act = getActivity() ?: return
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
            .setTitle("终端字号")
            .setView(box)
            .setPositiveButton("应用") { _, _ ->
                val sp = (10 + seek.progress).toFloat()
                prefs.edit().putFloat(Constants.PrefKeys.FONT_SP, sp).apply()
                onFontApplied(sp)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showKeyAlternatives(key: EmbeddedVirtualKey) {
        val act = getActivity() ?: return
        val symbols = listOf("@", ":", ";", "*", "?", "$", "#", "~", ".", "_", "-", "+", "=", "%", "^", "&", "!", "\"", "'", "`")
        val dp = { v: Int -> terminalDp(act, v) }

        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        content.addView(android.widget.TextView(act).apply {
            text = "长按快捷"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
        })

        var dialogRef: android.app.Dialog? = null
        val grid = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val row1 = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val row2 = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        symbols.take(10).forEach { sym ->
            row1.addView(createSymbolButton(act, sym, dp) { dialogRef?.dismiss() })
        }
        symbols.drop(10).forEach { sym ->
            row2.addView(createSymbolButton(act, sym, dp) { dialogRef?.dismiss() })
        }
        grid.addView(row1)
        content.addView(grid)
        grid.addView(row2)

        dialogRef = MaterialAlertDialogBuilder(act)
            .setView(content)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun createSymbolButton(act: Activity, sym: String, dp: (Int) -> Int, onDismiss: () -> Unit): TextView {
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
                getSession()?.write(sym)
                if (getCtrlLatched()) { setCtrlLatched(false) }
                onDismiss()
            }
        }
    }

    fun editVirtualKey(id: String) {
        val store = getLayoutStore()
        val key = store.getOrderedKeys().find { it.id == id } ?: return
        val act = getActivity() ?: return
        val dp = { v: Int -> terminalDp(act, v) }

        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val nameInput = android.widget.EditText(act).apply {
            hint = "按键名称"
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
            text = "点击输入"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        })
        val currentTap = if (key.input.isNotEmpty()) "  当前: ${encodeKeyInput(key.input)}" else "  （未设置）"
        content.addView(android.widget.TextView(act).apply {
            text = currentTap
            setTextColor(0xFF6B7280.toInt()); textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val tapInput = android.widget.EditText(act).apply {
            hint = "例如 c、\\n 自动回车、\\t、\\e[A"
            setText(encodeKeyInput(key.input))
        }
        content.addView(tapInput)
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "下滑命令"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        })
        val currentSwipeDown = if (key.swipeCommand.isNotEmpty()) "  当前: ${encodeKeyInput(key.swipeCommand)}" else "  （未设置）"
        content.addView(android.widget.TextView(act).apply {
            text = currentSwipeDown
            setTextColor(0xFF6B7280.toInt()); textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val swipeDownInput = android.widget.EditText(act).apply {
            hint = "例如 clear、pwd、ls "
            setText(encodeKeyInput(key.swipeCommand))
        }
        content.addView(swipeDownInput)
        content.addView(divider())
        content.addView(android.widget.TextView(act).apply {
            text = "上滑命令"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        })
        val currentSwipeUp = if (key.swipeUpCommand.isNotEmpty()) "  当前: ${encodeKeyInput(key.swipeUpCommand)}" else "  （未设置）"
        content.addView(android.widget.TextView(act).apply {
            text = currentSwipeUp
            setTextColor(0xFF6B7280.toInt()); textSize = 11f
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        })
        val swipeUpInput = android.widget.EditText(act).apply {
            hint = "例如 cd ..、exit、grep "
            setText(encodeKeyInput(key.swipeUpCommand))
        }
        content.addView(swipeUpInput)
        content.addView(divider())

        // 常用命令模板
        content.addView(android.widget.TextView(act).apply {
            text = "常用命令模板"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 12f
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

        // 别名绑定
        val aliasStore = getAliasStore()
        val aliases = aliasStore.getAliases()
        if (aliases.isNotEmpty()) {
            content.addView(divider())
            content.addView(android.widget.TextView(act).apply {
                text = "别名绑定"
                setTextColor(0xFF9CA3AF.toInt()); textSize = 12f
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
            .setTitle("自定义虚拟键: ${key.label}")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val rawTap = decodeKeyInput(tapInput.text.toString())
                val processedTap = when {
                    rawTap.isEmpty() || rawTap == "__CTRL__" || rawTap.startsWith("\u001B") -> rawTap
                    rawTap.length == 1 -> rawTap
                    rawTap.endsWith("\r") -> rawTap
                    else -> "$rawTap\r"
                }
                store.saveKeyOverride(key.id, EmbeddedVirtualKey(
                    nameInput.text.toString().trim().ifBlank { key.label }.take(8),
                    processedTap,
                    decodeKeyInput(swipeDownInput.text.toString()),
                    key.id,
                    decodeKeyInput(swipeUpInput.text.toString())
                ))
            }
            .setNeutralButton("恢复默认") { _, _ -> store.removeKeyOverride(key.id) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 别名管理对话框 ────────────────────────────────────────────

    fun showAliasManager() {
        val act = getActivity() ?: return
        val store = getAliasStore()
        val aliases = store.getAliases()
        if (aliases.isEmpty()) {
            showAliasEditDialog(null, null)
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("别名管理")
            .setItems(aliases.map { "${it.name} = ${it.value}" }.toTypedArray()) { _, which ->
                showAliasActionDialog(aliases[which])
            }
            .setPositiveButton("+ 添加别名") { _, _ -> showAliasEditDialog(null, null) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAliasActionDialog(alias: KeyAlias) {
        val act = getActivity() ?: return
        val store = getAliasStore()
        MaterialAlertDialogBuilder(act)
            .setTitle(alias.name)
            .setMessage(alias.value)
            .setPositiveButton("编辑") { _, _ -> showAliasEditDialog(alias.name, alias.value) }
            .setNeutralButton("删除") { _, _ ->
                store.deleteAlias(alias.name)
                showAliasManager()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAliasEditDialog(name: String?, value: String?) {
        val act = getActivity() ?: return
        val store = getAliasStore()
        val dpNum = { v: Int -> terminalDp(act, v) }
        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpNum(20), dpNum(10), dpNum(20), 0)
        }
        val nameInput = android.widget.EditText(act).apply {
            hint = "别名"
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
            hint = "命令 (例如 ls -lah)"
            if (value != null) setText(value)
            setTextColor(0xFFD1D5DB.toInt())
            minLines = 2
        }
        content.addView(valueInput)
        MaterialAlertDialogBuilder(act)
            .setTitle(if (name == null) "添加别名" else "编辑别名")
            .setView(content)
            .setPositiveButton("保存") { _, _ ->
                val n = nameInput.text.toString().trim()
                val v = valueInput.text.toString().trim()
                if (n.isNotEmpty() && v.isNotEmpty()) {
                    store.saveAlias(n, v, name)
                }
                showAliasManager()
            }
            .setNegativeButton("取消") { _, _ -> showAliasManager() }
            .show()
    }
}
