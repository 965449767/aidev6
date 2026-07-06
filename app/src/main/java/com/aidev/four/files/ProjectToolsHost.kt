package com.aidev.four.files

import android.app.Activity
import com.aidev.four.PreferencesManager
import java.io.File

interface ProjectToolsHost {
    fun hostActivity(): Activity
    fun hostPm(): PreferencesManager
    fun hostSelectedFile(): File?
    fun hostActiveDir(): File
    fun hostNavigateTo(file: File)
    fun hostReloadAll()
    fun hostClearSelection()
    fun hostSetSelectedFile(file: File)
    fun hostEditSelected()
    fun hostFormatSize(bytes: Long): String
    fun hostCopyText(label: String, text: String)
    fun hostCopySelectedPath()
    fun hostToast(message: String)
    fun hostRememberRecentDir(dir: File)
    fun hostSwitchToTab(index: Int)
}
