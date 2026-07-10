package com.aidev.six.files

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.aidev.six.PathConfig
import com.aidev.six.PreferencesManager
import java.io.File

internal class ProjectToolsHostImpl(private val activity: Activity) : ProjectToolsHost {

    override fun hostSwitchToTab(index: Int) {
        (activity as? com.aidev.six.ShellActivity)?.switchTo(index)
    }

    private val pm by lazy { PreferencesManager(activity) }

    override fun hostActivity(): Activity = activity

    override fun hostPm(): PreferencesManager = pm

    override fun hostSelectedFile(): File? = null

    override fun hostActiveDir(): File {
        return PathConfig.workspaceDir(activity)
    }

    override fun hostNavigateTo(file: File) {}

    override fun hostReloadAll() {}

    override fun hostClearSelection() {}

    override fun hostSetSelectedFile(file: File) {}

    override fun hostEditSelected() {}

    override fun hostFormatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1fK".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1fM".format(bytes / 1024.0 / 1024.0)
            else -> "%.1fG".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }

    override fun hostCopyText(label: String, text: String) {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun hostCopySelectedPath() {
        hostCopyText("AIDev 路径", hostActiveDir().absolutePath)
    }

    override fun hostToast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    override fun hostRememberRecentDir(dir: File) {
        pm.sharedPreferences.edit().putString("last_terminal_cd", dir.absolutePath).apply()
    }
}
