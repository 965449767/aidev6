package com.aidev.four.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.aidev.four.TerminalCommandBus
import android.widget.Toast
import androidx.compose.runtime.Immutable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

@Immutable
data class CheckItem(val name: String, val ok: Boolean, val fixAction: String?)

class DevEnvironmentChecker(
    private val activity: Activity
) {
    fun checkAndRepair() {
        val home = File(activity.filesDir, "home")
        val rootfs = File(home, "ubuntu-rootfs")
        val checks = mutableListOf<CheckItem>()

        val storageOk = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        checks.add(CheckItem("存储权限", storageOk, if (!storageOk) "action:storage" else null))
        val batteryOk = if (Build.VERSION.SDK_INT < 23) true else (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(activity.packageName) ?: false
        checks.add(CheckItem("电池优化白名单", batteryOk, if (!batteryOk) "action:battery" else null))

        checks.add(CheckItem("AIDev Home", home.exists(), null))
        checks.add(CheckItem("Ubuntu rootfs", rootfs.exists(), null))
        checks.add(CheckItem("PRoot 依赖", File(home, "proot-lib/libtalloc.so.2").exists(), null))

        val devTools = listOf("node" to "Node.js", "python3" to "Python3", "git" to "Git", "java" to "JDK", "npm" to "npm")
        val missingTools = mutableListOf<String>()
        for ((cmd, label) in devTools) {
            val binPaths = listOf(
                File(rootfs, "usr/bin/$cmd"), File(rootfs, "usr/local/bin/$cmd"),
                File(rootfs, "root/.opencode/bin/$cmd"), File(rootfs, "bin/$cmd"),
            )
            val exists = if (cmd == "java") {
                binPaths.any { it.exists() } || File(rootfs, "usr/lib/jvm").listFiles()?.any { it.isDirectory } == true
            } else binPaths.any { it.exists() }
            if (!exists) missingTools.add(label)
        }
        val baseLabel = if (missingTools.isNotEmpty()) "基础开发工具包 · 缺失：${missingTools.joinToString("、")}" else "基础开发工具包"
        checks.add(CheckItem(baseLabel, missingTools.isEmpty(), if (missingTools.isNotEmpty()) "setup-dev-env" else null))

        val optionalTools = listOf("opencode" to "OpenCode", "gradle" to "Gradle", "go" to "Go", "cargo" to "Rust/Cargo")
        for ((cmd, label) in optionalTools) {
            val binPaths = listOf(
                File(rootfs, "usr/bin/$cmd"), File(rootfs, "usr/local/bin/$cmd"),
                File(rootfs, "root/.opencode/bin/$cmd"), File(rootfs, "bin/$cmd"),
            )
            val exists = binPaths.any { it.exists() }
            checks.add(CheckItem(label, exists, if (cmd == "opencode" && !exists) "install-aitool" else null))
        }

        val failedChecks = checks.filter { !it.ok }
        val allOk = failedChecks.isEmpty()
        val fixable = failedChecks.filter { it.fixAction != null }

        val body = StringBuilder()
        body.append(if (allOk) "所有环境检查通过 ✓\n\n" else "发现 ${failedChecks.size} 个问题：\n\n")
        for (item in checks) body.append("${if (item.ok) "✓" else "✗"} ${item.name}\n")

        val terminalCheckLabel = "终端详细检测"
        MaterialAlertDialogBuilder(activity)
            .setTitle("开发环境检查")
            .setMessage(body.toString())
            .setPositiveButton(if (fixable.isNotEmpty()) "一键修复" else null) { _, _ ->
                val cmds = mutableListOf<String>()
                for (item in fixable) {
                    when (item.fixAction) {
                        "action:storage" -> openStorageSettings()
                        "action:battery" -> {
                            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            })
                        }
                        else -> cmds.add(item.fixAction!!)
                    }
                }
                if (cmds.isNotEmpty()) {
                    TerminalCommandBus.post(cmds.joinToString(" && "))
                    TerminalCommandBus.post("check-dev-env")
                    Toast.makeText(activity, "修复命令已发送，请观察终端输出", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(terminalCheckLabel) { _, _ ->
                if (!rootfs.exists()) {
                    Toast.makeText(activity, "Ubuntu 环境尚未初始化，请先进入终端", Toast.LENGTH_SHORT).show()
                } else {
                    TerminalCommandBus.post("check-dev-env")
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") })
            }.onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${activity.packageName}") })
        }
    }
}
