package com.aidev.six.ui.pages

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aidev.six.PreferencesManager
import com.aidev.six.shizuku.ShizukuLogcat

internal data class MenuConfig(
    val title: String,
    val items: List<MenuEntry>,
)

internal data class MenuEntry(
    val title: String,
    val desc: String,
    val kind: MenuEntryKind = MenuEntryKind.Click,
    val action: () -> Unit = {},
)

sealed interface MenuEntryKind {
    data object Click : MenuEntryKind
    data class Toggle(
        val checked: () -> Boolean,
        val onToggle: (Boolean) -> Unit,
    ) : MenuEntryKind
}

internal fun appearanceMenu(
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    showDialog: (SettingsDialog) -> Unit,
): MenuConfig = MenuConfig("\u5916\u89C2", listOf(
    MenuEntry("\u4E3B\u9898\u9884\u8BBE", "\u5207\u6362\u6DF1\u8272/\u6D45\u8272/\u8DDF\u968F\u7CFB\u7EDF") {
        showDialog(SettingsDialog.ThemePreset(prefs.themePreset))
        onDismiss()
    },
))




internal fun systemMenu(
    context: android.content.Context,
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    showDialog: (SettingsDialog) -> Unit,
): MenuConfig = MenuConfig("\u7CFB\u7EDF", listOf(
    MenuEntry("Shizuku \u72B6\u6001", "\u5B9E\u65F6\u68C0\u6D4B Shizuku \u5B89\u88C5\u548C\u6388\u6743\u72B6\u6001") {
        val installed = runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        val available = installed && ShizukuLogcat.isAvailable()
        showDialog(SettingsDialog.ShizukuStatus(
            installed = installed,
            available = available,
            statusText = if (installed) ShizukuLogcat.statusText() else "\u672A\u5B89\u88C5",
        ))
        onDismiss()
    },
    MenuEntry("\u7CFB\u7EDF\u6743\u9650", "\u5B58\u50A8/\u901A\u77E5/\u5B89\u88C5/\u7535\u6C60\u4F18\u5316") {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
        } else {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
        onDismiss()
    },
    MenuEntry(
        title = "\u79BB\u7EBF\u4F18\u5148\uFF08\u4E0B\u8F7D\u6765\u6E90\uFF09",
        desc = "\u5F00\u542F\u540E\u6784\u5EFA\u53EA\u4F7F\u7528 AIDevRepo \u79BB\u7EBF\u4ED3\u5E93\uFF0C\u7981\u6B62\u8D70\u7F51\u7EDC\u4E0B\u8F7D JDK/Gradle/\u4F9D\u8D56\u57FA\u7EBF",
        kind = MenuEntryKind.Toggle(
            checked = { prefs.repoMode == "STRICT" },
            onToggle = { prefs.repoMode = if (it) "STRICT" else "AUTO" },
        ),
    ),
))
