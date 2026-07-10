package com.aidev.six

import com.aidev.six.terminal.KeyAlias

internal fun decodeKeyInput(input: String): String =
    input.replace("\\n", "\n").replace("\\t", "\t").replace("\\e", "\u001b")

internal fun encodeKeyInput(input: String): String =
    input.replace("\u001b", "\\e").replace("\n", "\\n").replace("\t", "\\t")

internal fun parseKeyAliases(raw: String): List<KeyAlias> =
    raw.lines().mapNotNull { line ->
        val parts = line.split("\t")
        val name = parts.getOrNull(0)?.trim().orEmpty()
        val value = parts.getOrNull(1).orEmpty()
        if (name.isEmpty()) null else KeyAlias(name, value)
    }

internal fun terminalDp(activity: android.app.Activity, v: Int): Int =
    (activity.resources.displayMetrics.density * v + 0.5f).toInt()

internal fun tapCmdHint() = "例如 c、\\n 自动回车、\\t、\\e[A"

internal fun shellEscape(path: String): String = "'${path.replace("'", "'\\''")}'"
