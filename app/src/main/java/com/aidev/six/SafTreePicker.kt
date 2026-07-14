package com.aidev.six

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import java.io.File

/**
 * 封装系统目录选择器（SAF OpenDocumentTree）。
 * 仅对主存储（primary）能解析出真实文件路径；其余卷返回 null，由调用方提示手动输入。
 */
@Composable
fun rememberTreeDirPicker(onResult: (File?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        onResult(uri?.let { treeUriToPrimaryFile(it) })
    }
    return { launcher.launch(null) }
}

fun treeUriToPrimaryFile(uri: Uri): File? {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val split = docId.split(":", limit = 2)
        if (split.first() != "primary") return null
        val base = Environment.getExternalStorageDirectory()
        File(base, split.getOrElse(1) { "" })
    }.getOrNull()
}
