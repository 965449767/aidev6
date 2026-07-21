package com.aidev.six.terminal

import android.app.Activity
import com.aidev.six.Constants
import java.io.File

/**
 * 键盘别名存储：别名（alias）的增删改查与文件同步。
 * 从 VirtualKeyboardManager.kt 中拆分出来。
 */
internal class KeyboardAliasStore(
    private val getActivity: () -> Activity?,
    private val onAliasesChanged: () -> Unit = {}
) {
    fun getAliases(): List<KeyAlias> {
        val act = getActivity() ?: return emptyList()
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val raw = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        return raw.lines().mapNotNull { line ->
            val parts = line.split("\t")
            val name = parts.getOrNull(0)?.trim().orEmpty()
            val value = parts.getOrNull(1).orEmpty()
            if (name.isEmpty()) null else KeyAlias(name, value)
        }
    }

    fun saveAlias(name: String, value: String, oldName: String? = null) {
        val act = getActivity() ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        val deleteKey = oldName ?: name
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != deleteKey }
        val newLine = listOf(name, value).joinToString("\t")
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, (lines + newLine).joinToString("\n")).apply()
        syncAliasesToFile()
        onAliasesChanged()
    }

    fun deleteAlias(name: String) {
        val act = getActivity() ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        val lines = old.lines().filter { it.isNotBlank() && it.substringBefore("\t") != name }
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, lines.joinToString("\n")).apply()
        syncAliasesToFile()
        onAliasesChanged()
    }

    /** 将别名同步到 rootfs 内的 .aidev_aliases 文件，供 shell 直接 source。 */
    private fun syncAliasesToFile() {
        val act = getActivity() ?: return
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
}
