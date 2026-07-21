package com.aidev.six.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.aidev.six.shizuku.ShizukuLogcat
import com.aidev.six.ShizukuState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class ShizukuInstaller(
    private val activity: Activity,
    private val onInstallComplete: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun installApk(file: File) {
        if (!file.isFile || !file.name.endsWith(".apk", ignoreCase = true)) {
            toast(activity, "\u5F53\u524D\u9009\u62E9\u7684\u4E0D\u662F APK")
            return
        }
        val path = file.absolutePath
        val inProot = path.contains("ubuntu-rootfs")
        val onSdcard = path.startsWith("/sdcard/") || path.startsWith("/storage/")
        if (inProot) {
            toast(activity, "APK \u5728 PRoot \u5185\u90E8\uFF0C\u8BF7\u5148\u590D\u5236\u5230 /sdcard/")
            return
        }
        if (!onSdcard) {
            toast(activity, "APK \u8DEF\u5F84\u4E0D\u53EF\u8BBF\u95EE\uFF0C\u8BF7\u79FB\u81F3 /sdcard/ \u76EE\u5F55")
            return
        }

        val state = ShizukuLogcat.checkState(activity)
        when (state) {
            is ShizukuState.NotInstalled -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("\u9700\u8981 Shizuku")
                    .setMessage("\u9759\u9ED8\u5B89\u88C5 APK \u9700\u8981 Shizuku \u6743\u9650\uFF0C\u4F46\u672A\u68C0\u6D4B\u5230 Shizuku \u5E94\u7528\u3002")
                    .setPositiveButton("\u53BB\u5B89\u88C5") { _, _ ->
                        runCatching {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                        }.onFailure {
                            toast(activity, "\u8BF7\u5728\u5E94\u7528\u5546\u5E97\u641C\u7D22 Shizuku")
                        }
                    }
                    .setNegativeButton("\u53D6\u6D88", null)
                    .show()
            }
            is ShizukuState.NotRunning -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Shizuku \u672A\u8FD0\u884C")
                    .setMessage("\u8BF7\u5728 Shizuku \u5E94\u7528\u4E2D\u542F\u52A8\u670D\u52A1\u540E\u91CD\u8BD5\u3002")
                    .setPositiveButton("\u6253\u5F00 Shizuku") { _, _ ->
                        activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                            activity.startActivity(it)
                        }
                    }
                    .setNegativeButton("\u53D6\u6D88", null)
                    .show()
            }
            is ShizukuState.NotAuthorized -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Shizuku \u672A\u6388\u6743")
                    .setMessage("\u8BF7\u5728 Shizuku \u5E94\u7528\u4E2D\u4E3A\u672C\u5E94\u7528\u6388\u4E88\u6743\u9650\u540E\u91CD\u8BD5\u3002")
                    .setPositiveButton("\u6253\u5F00 Shizuku") { _, _ ->
                        activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                            activity.startActivity(it)
                        }
                    }
                    .setNegativeButton("\u53D6\u6D88", null)
                    .show()
            }
            is ShizukuState.Ready -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("\u5B89\u88C5 APK")
                    .setMessage("\u5C06\u901A\u8FC7 Shizuku \u9759\u9ED8\u5B89\u88C5\uFF1A\n${file.name}")
                    .setPositiveButton("\u5B89\u88C5") { _, _ -> executeInstall(file) }
                    .setNeutralButton("\u8BCA\u65AD\u5B89\u88C5") { _, _ -> diagnoseInstall(file) }
                    .setNegativeButton("\u53D6\u6D88", null)
                    .show()
            }
        }
    }

    private fun executeInstall(file: File) {
        val srcPath = file.absolutePath.replace("'", "'\\''")
        val tmpPath = "/data/local/tmp/aidev-install-${file.name.hashCode()}.apk"
        ShizukuLogcat.executeFireAndForget("cp '$srcPath' '$tmpPath' && pm install -r -d '$tmpPath'; rm -f '$tmpPath'")
        toast(activity, "正在安装...")
    }

    private fun diagnoseInstall(file: File) {
        val srcPath = file.absolutePath.replace("'", "'\\''")
        val tmpPath = "/data/local/tmp/aidev-install-${file.name.hashCode()}.apk"
        val cmd = "cp '$srcPath' '$tmpPath' && pm install -r -d '$tmpPath'; rm -f '$tmpPath'"
        scope.launch {
            val result = ShizukuLogcat.executeCommand(cmd)
            val hint = ShizukuLogcat.pmInstallErrorHint(result)
            val msg = """
命令:
$cmd

退出码: ${result.exitCode}

stdout:
${result.stdout.take(500).ifBlank { "(空)" }}

stderr:
${result.stderr.take(500).ifBlank { "(空)" }}

结果解析: $hint
            """.trimIndent()
            if (!activity.isFinishing) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("诊断结果")
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    fun destroy() { scope.cancel() }
}
