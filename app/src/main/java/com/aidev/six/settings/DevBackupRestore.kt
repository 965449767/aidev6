package com.aidev.six.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import com.aidev.six.TerminalCommandBus
import java.io.File

@Immutable
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: String,
    val totalSize: String,
    val components: Map<String, ComponentInfo>
)

@Immutable
data class ComponentInfo(
    val path: String,
    val description: String,
    val size: String?
)

class DevBackupRestore(private val context: Context) {

    companion object {
        const val BACKUP_PATH = "/storage/emulated/0/dev-backup"
        const val RESTORE_SCRIPT = "$BACKUP_PATH/restore.sh"
    }

    /**
     * Check if the backup directory exists and has a valid manifest.
     */
    fun isBackupAvailable(): Boolean {
        val backupDir = File(BACKUP_PATH)
        if (!backupDir.isDirectory) return false
        return File(BACKUP_PATH, "manifest.json").isFile
    }

    /**
     * Parse the backup manifest to show what's available.
     */
    fun loadManifest(): BackupManifest? {
        val manifestFile = File(BACKUP_PATH, "manifest.json")
        if (!manifestFile.isFile) return null
        return try {
            val text = manifestFile.readText()
            val json = org.json.JSONObject(text)
            val comps = mutableMapOf<String, ComponentInfo>()
            val components = json.getJSONObject("components")
            for (key in components.keys()) {
                val c = components.getJSONObject(key)
                comps[key] = ComponentInfo(
                    path = c.optString("path", ""),
                    description = c.optString("description", ""),
                    size = c.optString("size", "").ifEmpty { null }
                )
            }
            BackupManifest(
                formatVersion = json.optInt("formatVersion", 1),
                createdAt = json.optString("createdAt", ""),
                totalSize = json.optString("totalSize", ""),
                components = comps
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if a specific component exists in the backup.
     */
    fun hasComponent(component: String): Boolean {
        return when (component) {
            "android-sdk" -> File(BACKUP_PATH, "android-sdk/platforms").isDirectory
            "gradle-wrapper" -> File(BACKUP_PATH, "gradle/wrapper").isDirectory
            "jdk" -> File(BACKUP_PATH, "jdk/bin").isDirectory
            "dev-env" -> File(BACKUP_PATH, "dev-env/bin").isDirectory
            else -> false
        }
    }

    /**
     * Build the restore command for a specific component.
     * The command is designed to be run inside PRoot Ubuntu via TerminalCommandBus.
     */
    fun buildRestoreCommand(component: String): String? {
        val restoreScript = RESTORE_SCRIPT
        return when (component) {
            "android-sdk" -> "bash $restoreScript --component sdk 2>&1"
            "gradle-wrapper" -> "bash $restoreScript --component gradle 2>&1"
            "jdk" -> "bash $restoreScript --component jdk 2>&1"
            "all" -> "bash $restoreScript 2>&1"
            else -> null
        }
    }

    /**
     * Restore a component asynchronously via TerminalCommandBus.
     */
    fun restoreComponent(component: String) {
        val cmd = buildRestoreCommand(component) ?: return
        TerminalCommandBus.post(cmd)
    }

    /**
     * Restore all components via TerminalCommandBus.
     */
    fun restoreAll() {
        if (isBackupAvailable()) {
            TerminalCommandBus.post("bash $RESTORE_SCRIPT 2>&1")
        }
    }

    /**
     * Generate a user-friendly summary of what's in the backup.
     */
    fun getBackupSummary(): String {
        val manifest = loadManifest() ?: return "备份不可用"
        val sb = StringBuilder()
        sb.appendLine("本地备份可用（$BACKUP_PATH）")
        sb.appendLine("备份时间: ${manifest.createdAt.take(10)}")
        sb.appendLine("总大小: ${manifest.totalSize}")
        sb.appendLine()
        sb.appendLine("可用组件:")
        for ((key, info) in manifest.components) {
            val sizeStr = info.size?.let { " ($it)" } ?: ""
            sb.appendLine("  • ${info.description}$sizeStr")
        }
        return sb.toString()
    }
}
