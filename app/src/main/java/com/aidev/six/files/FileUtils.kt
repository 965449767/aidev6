package com.aidev.six.files

import android.app.Activity
import android.widget.Toast
import com.aidev.six.ClipboardHelper
import java.io.File

fun isImageFile(file: File): Boolean =
    file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

fun isLikelyText(file: File): Boolean =
    runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(2048)
            val n = input.read(buffer)
            if (n <= 0) return@use true
            for (i in 0 until n) {
                val b = buffer[i].toInt() and 0xFF
                if (b == 0) return@use false
            }
            true
        }
    }.getOrDefault(false)

fun formatSize(n: Long): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fK".format(n / 1024.0)
    n < 1024L * 1024L * 1024L -> "%.1fM".format(n / 1024.0 / 1024.0)
    else -> "%.1fG".format(n / 1024.0 / 1024.0 / 1024.0)
}

fun copyText(activity: Activity, label: String, text: String) {
    ClipboardHelper.copy(activity, label, text)
}

fun toast(activity: Activity, text: String) {
    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
