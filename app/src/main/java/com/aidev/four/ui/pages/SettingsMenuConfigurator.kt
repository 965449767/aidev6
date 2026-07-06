package com.aidev.four.ui.pages

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aidev.four.KeepAliveService
import com.aidev.four.PreferencesManager
import com.aidev.four.ShizukuLogcat

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
): MenuConfig = MenuConfig("外观", listOf(
    MenuEntry("主题预设", "切换深色/浅色/跟随系统") {
        showDialog(SettingsDialog.ThemePreset(prefs.themePreset))
        onDismiss()
    },
    MenuEntry("背景模式", "纯色/渐变/自定义图片") {
        showDialog(SettingsDialog.BackgroundMode(prefs.bgMode))
        onDismiss()
    },
))





internal fun systemMenu(
    context: android.content.Context,
    prefs: PreferencesManager,
    onDismiss: () -> Unit,
    showDialog: (SettingsDialog) -> Unit,
): MenuConfig = MenuConfig("系统与权限", listOf(
    MenuEntry("存储权限", "管理所有文件访问权限") {
        if (Build.VERSION.SDK_INT >= 30) {
            kotlin.runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
        onDismiss()
    },
    MenuEntry("通知权限", "管理通知显示权限") {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
        onDismiss()
    },
    MenuEntry("安装未知应用", "允许安装来自未知来源的应用") {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
        onDismiss()
    },
    MenuEntry("修改系统设置", "允许应用修改系统设置（如亮度、超时）") {
        if (Build.VERSION.SDK_INT >= 23) {
            if (!Settings.System.canWrite(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
        }
        onDismiss()
    },
    MenuEntry("电池优化", "将应用加入电池优化白名单，防止后台被限制") {
        if (Build.VERSION.SDK_INT >= 23) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        }
        onDismiss()
    },
    MenuEntry("Shizuku 状态", "实时检测 Shizuku 安装和授权状态") {
        val installed = runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        val available = installed && ShizukuLogcat.isAvailable()
        showDialog(SettingsDialog.ShizukuStatus(
            installed = installed,
            available = available,
            statusText = if (installed) ShizukuLogcat.statusText() else "未安装",
        ))
        onDismiss()
    },
    MenuEntry(
        title = "后台常驻",
        desc = "启动保活服务防止进程被系统回收",
        kind = MenuEntryKind.Toggle(
            checked = { prefs.keepaliveAuto },
            onToggle = { if (it) { runCatching { KeepAliveService.start(context) }.onFailure { android.widget.Toast.makeText(context, "启动保活服务失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show() } }; prefs.keepaliveAuto = it },
        ),
    ),
    MenuEntry("应用详情", "跳转到系统应用信息页") {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        })
        onDismiss()
    },
))
