package com.aidev.six

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一个源目录「导入」进 workspace：复制到 workspace/<name>，
 * 自动跳过 build/.gradle/.idea/.cxx 等构建/IDE 垃圾，保证导入即可构建。
 * 目标名冲突时自动追加 -2 / -3 后缀。
 */
object ProjectImporter {

    private val DIR_SKIP = setOf(
        "build", "captures", "node_modules",
        ".gradle", ".idea", ".cxx", ".kotlin", ".externalNativeBuild",
    )

    suspend fun importProject(
        srcDir: File,
        workspaceDir: File,
        name: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(srcDir.isDirectory) { "源目录不存在：${srcDir.absolutePath}" }
        workspaceDir.mkdirs()
        val baseName = name?.takeIf { it.isNotBlank() }?.trim() ?: srcDir.name
        val safeName = baseName.replace(Regex("[/\\\\]"), "_").trimEnd('.')
        val dest = uniqueDir(workspaceDir, safeName)
        dest.mkdirs()
        val files = collectFiles(srcDir)
        var done = 0
        for (f in files) {
            val rel = f.relativeTo(srcDir).path.replace('\\', '/')
            val target = File(dest, rel)
            target.parentFile?.mkdirs()
            runCatching { f.copyTo(target, overwrite = true) }
            done++
            onProgress(done, files.size)
        }
        dest
    }

    private fun uniqueDir(ws: File, name: String): File {
        var candidate = File(ws, name)
        var i = 2
        while (candidate.exists()) {
            candidate = File(ws, "$name-$i")
            i++
        }
        return candidate
    }

    private fun collectFiles(src: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) {
                    if (f.name in DIR_SKIP) return@forEach
                    walk(f)
                } else {
                    out.add(f)
                }
            }
        }
        walk(src)
        return out
    }
}
