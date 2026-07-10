package com.aidev.six

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Immutable
data class BackupCheckpoint(
    val backupPath: String = "",
    val lastCompletedItem: String = "",
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

object BackupResumeState {
    private const val PREFS_NAME = "backup_resume"
    private const val KEY_PATH = "checkpoint_path"
    private const val KEY_ITEM = "checkpoint_item"
    private const val KEY_TOTAL = "checkpoint_total"
    private const val KEY_DONE = "checkpoint_done"

    var enableIncremental by mutableStateOf(false)
    var checkpoint by mutableStateOf<BackupCheckpoint?>(null)
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restore()
    }

    fun saveCheckpoint(path: String, completed: String, total: Int, done: Int) {
        checkpoint = BackupCheckpoint(path, completed, total, done)
        prefs?.edit()?.apply {
            putString(KEY_PATH, path)
            putString(KEY_ITEM, completed)
            putInt(KEY_TOTAL, total)
            putInt(KEY_DONE, done)
            apply()
        }
    }

    fun clearCheckpoint() {
        checkpoint = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun restore() {
        val p = prefs ?: return
        val path = p.getString(KEY_PATH, null) ?: return
        checkpoint = BackupCheckpoint(
            backupPath = path,
            lastCompletedItem = p.getString(KEY_ITEM, "") ?: "",
            totalItems = p.getInt(KEY_TOTAL, 0),
            completedItems = p.getInt(KEY_DONE, 0),
        )
    }
}
