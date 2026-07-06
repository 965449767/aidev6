package com.aidev.four

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {
    fun copy(activity: Activity, label: String, text: String) {
        try {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
            // silently ignore
        }
    }

    fun paste(activity: Activity): String? = try {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
    } catch (_: Exception) {
        null
    }

    fun hasContent(activity: Activity): Boolean = try {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.itemCount?.let { it > 0 } == true
    } catch (_: Exception) {
        false
    }
}
