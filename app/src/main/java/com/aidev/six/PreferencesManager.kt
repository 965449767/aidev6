package com.aidev.six

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class PreferencesManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    val sharedPreferences: SharedPreferences get() = prefs

    private var pendingEdit: SharedPreferences.Editor? = null

    fun batch(block: PreferencesManager.() -> Unit) {
        val ed = prefs.edit()
        pendingEdit = ed
        try {
            block()
        } finally {
            ed.apply()
            pendingEdit = null
        }
    }

    var themePreset: String
        get() = prefs.getString(Constants.PrefKeys.THEME_PRESET, "system") ?: "system"
        set(value) = write { putString(Constants.PrefKeys.THEME_PRESET, value) }

    var bgMode: String
        get() = prefs.getString(Constants.PrefKeys.BG_MODE, "solid") ?: "solid"
        set(value) = write { putString(Constants.PrefKeys.BG_MODE, value) }

    var bgImageUri: String?
        get() = prefs.getString(Constants.PrefKeys.BG_IMAGE_URI, null)
        set(value) = write { putString(Constants.PrefKeys.BG_IMAGE_URI, value) }

    var hapticTap: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.HAPTIC_TAP, true)
        set(value) = write { putBoolean(Constants.PrefKeys.HAPTIC_TAP, value) }

    var fontSp: Float
        get() = prefs.getFloat(Constants.PrefKeys.FONT_SP, 12f)
        set(value) = write { putFloat(Constants.PrefKeys.FONT_SP, value) }

    var terminalCustomKeys: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_CUSTOM_KEYS, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_CUSTOM_KEYS, value) }

    var terminalKeyOverrides: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_KEY_OVERRIDES, value) }

    var terminalKeyAliases: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_KEY_ALIASES, value) }

    var terminalKeyOrder: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_KEY_ORDER, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_KEY_ORDER, value) }

    var terminalPinnedCompletions: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS, value) }

    var writeSettingsPrompted: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.WRITE_SETTINGS_PROMPTED, false)
        set(value) = write { putBoolean(Constants.PrefKeys.WRITE_SETTINGS_PROMPTED, value) }

    var syncTerminalFiles: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.SYNC_TERMINAL_FILES, false)
        set(value) = write { putBoolean(Constants.PrefKeys.SYNC_TERMINAL_FILES, value) }

    var currentProjectPath: String
        get() = prefs.getString(Constants.PrefKeys.CURRENT_PROJECT_PATH, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.CURRENT_PROJECT_PATH, value) }

    var backupDir: String
        get() = prefs.getString(Constants.PrefKeys.BACKUP_DIR, "/sdcard/AIDev/backups/") ?: "/sdcard/AIDev/backups/"
        set(value) = write { putString(Constants.PrefKeys.BACKUP_DIR, value) }


    var externalAidevDir: String
        get() = prefs.getString(Constants.PrefKeys.EXTERNAL_AIDEV_DIR, "/sdcard/AIDev") ?: "/sdcard/AIDev"
        set(value) = write { putString(Constants.PrefKeys.EXTERNAL_AIDEV_DIR, value) }

    var bridgeSocketEnabled: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.BRIDGE_SOCKET_ENABLED, true)
        set(v) = write { putBoolean(Constants.PrefKeys.BRIDGE_SOCKET_ENABLED, v) }

    var repoMode: String
        get() = prefs.getString(Constants.PrefKeys.REPO_MODE, "AUTO") ?: "AUTO"
        set(value) {
            val v = value.trim().uppercase()
            write { putString(Constants.PrefKeys.REPO_MODE, v) }
            writeRepoPolicyFile(v)
        }

    private fun writeRepoPolicyFile(mode: String) {
        runCatching {
            val dir = java.io.File(Constants.REPO_ROOT)
            dir.mkdirs()
            java.io.File(dir, ".nomedia").createNewFile()
            java.io.File(dir, "policy.txt").writeText(mode)
        }
    }

    var projectActionHistory: String
        get() = prefs.getString(Constants.PrefKeys.PROJECT_ACTION_HISTORY, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.PROJECT_ACTION_HISTORY, value) }

    var recentFileMore: String
        get() = prefs.getString(Constants.PrefKeys.RECENT_FILE_MORE, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.RECENT_FILE_MORE, value) }

    var recentTerminalMore: String
        get() = prefs.getString(Constants.PrefKeys.RECENT_TERMINAL_MORE, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.RECENT_TERMINAL_MORE, value) }

    var recentAgentMore: String
        get() = prefs.getString(Constants.PrefKeys.RECENT_AGENT_MORE, "") ?: ""
        set(value) = write { putString(Constants.PrefKeys.RECENT_AGENT_MORE, value) }

    var autoShowKeyboard: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.AUTO_SHOW_KEYBOARD, true)
        set(value) = write { putBoolean(Constants.PrefKeys.AUTO_SHOW_KEYBOARD, value) }

    var keepaliveAuto: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.KEEPALIVE_AUTO, true)
        set(value) = write { putBoolean(Constants.PrefKeys.KEEPALIVE_AUTO, value) }

    var fileFavorites: Set<String>
        get() = prefs.getStringSet(Constants.PrefKeys.FILE_FAVORITES, emptySet()) ?: emptySet()
        set(value) = write { putStringSet(Constants.PrefKeys.FILE_FAVORITES, value) }

    var fileRecentDirs: Set<String>
        get() = prefs.getStringSet(Constants.PrefKeys.FILE_RECENT_DIRS, emptySet()) ?: emptySet()
        set(value) = write { putStringSet(Constants.PrefKeys.FILE_RECENT_DIRS, value) }

    var fileLayoutMode: String
        get() = prefs.getString(Constants.PrefKeys.FILE_LAYOUT_MODE, "split") ?: "split"
        set(value) = write { putString(Constants.PrefKeys.FILE_LAYOUT_MODE, value) }

    var terminalTheme: String
        get() = prefs.getString(Constants.PrefKeys.TERMINAL_THEME, "classic-dark") ?: "classic-dark"
        set(value) = write { putString(Constants.PrefKeys.TERMINAL_THEME, value) }

    var bgOverride: Int
        get() = prefs.getInt(Constants.PrefKeys.TERMINAL_BG_OVERRIDE, -1)
        set(value) = write { putInt(Constants.PrefKeys.TERMINAL_BG_OVERRIDE, value) }

    var useTrash: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.USE_TRASH, true)
        set(value) = write { putBoolean(Constants.PrefKeys.USE_TRASH, value) }

    var fileSortMode: String
        get() = prefs.getString(Constants.PrefKeys.FILE_SORT_MODE, "name") ?: "name"
        set(value) = write { putString(Constants.PrefKeys.FILE_SORT_MODE, value) }

    var fileShowHidden: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.FILE_SHOW_HIDDEN, false)
        set(value) = write { putBoolean(Constants.PrefKeys.FILE_SHOW_HIDDEN, value) }

    var fileConfirmDelete: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.FILE_CONFIRM_DELETE, true)
        set(value) = write { putBoolean(Constants.PrefKeys.FILE_CONFIRM_DELETE, value) }

    var fileTrashRetention: String
        get() = prefs.getString(Constants.PrefKeys.FILE_TRASH_RETENTION, "3min") ?: "3min"
        set(value) = write { putString(Constants.PrefKeys.FILE_TRASH_RETENTION, value) }

    var projectMode: Boolean
        get() = prefs.getBoolean(Constants.PrefKeys.PROJECT_MODE, false)
        set(value) = write { putBoolean(Constants.PrefKeys.PROJECT_MODE, value) }

    var projectViewTab: Int
        get() = prefs.getInt(Constants.PrefKeys.PROJECT_VIEW_TAB, 0)
        set(value) = write { putInt(Constants.PrefKeys.PROJECT_VIEW_TAB, value) }

    var lastTab: Int
        get() = prefs.getInt(Constants.PrefKeys.LAST_TAB, -1)
        set(value) = write { putInt(Constants.PrefKeys.LAST_TAB, value) }

    /**
     * 动态桥接 Token：首次读取时生成 UUID 并持久化，
     * 同时写入 aidev_home/.bridge-token 文件供 shell 脚本读取。
     */
    val bridgeToken: String
        get() {
            var token = prefs.getString(Constants.PrefKeys.BRIDGE_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = java.util.UUID.randomUUID().toString()
                write { putString(Constants.PrefKeys.BRIDGE_TOKEN, token) }
                syncTokenToFile(token)
            }
            return token
        }

    /** 把 token 写入文件，供 aidev-bridge.sh 等脚本读取。写入 aidev home（shell 可达）。 */
    fun syncTokenToFile(token: String) {
        runCatching {
            val home = appContext.filesDir
            val tokenFile = java.io.File(home, ".bridge-token")
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
        }
    }

    /** 同步 token 到 aidev home 目录（供 PRoot 内 shell 脚本读取）。 */
    fun syncTokenToAidevHome(aidevHome: File) {
        runCatching {
            val token = bridgeToken
            val tokenFile = java.io.File(aidevHome, ".bridge-token")
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token)
        }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val ed = pendingEdit
        if (ed != null) {
            ed.block()
        } else {
            prefs.edit().apply { block(); apply() }
        }
    }
}
