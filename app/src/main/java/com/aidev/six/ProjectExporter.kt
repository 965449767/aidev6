package com.aidev.six

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把安卓项目源码合成为一份「AI 可读」文本文档（Markdown 或纯文本）：
 * 开头附目录树与统计，随后每个源文件以「相对路径 + 代码块」呈现。
 * 默认排除构建产物、IDE 缓存、二进制；.git 可选择包含。
 */
object ProjectExporter {

    data class Options(
        val includeGit: Boolean = false,
        val plainText: Boolean = false,
        val maxFileBytes: Long = 512 * 1024,
    )

    private val DIR_SKIP = setOf("build", "captures", "node_modules")
    private val BINARY_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "so", "aar", "jar",
        "zip", "tar", "gz", "tgz", "wav", "mp3", "mp4", "mkv", "ttf", "otf",
        "woff", "woff2", "db", "sqlite", "keystore", "jks", "bin", "apk", "dex",
    )

    suspend fun exportToFile(
        projectDir: File,
        outFile: File,
        opts: Options = Options(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val doc = buildAiDoc(projectDir, opts, onProgress)
        outFile.parentFile?.mkdirs()
        outFile.writeText(doc)
    }

    suspend fun buildAiDoc(
        projectDir: File,
        opts: Options = Options(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        require(projectDir.isDirectory) { "项目目录不存在：${projectDir.absolutePath}" }
        val sources = collectFiles(projectDir, opts)
        val total = sources.size
        val sb = StringBuilder()
        val projName = projectDir.name
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        if (opts.plainText) {
            sb.appendLine("PROJECT SOURCE: $projName")
            sb.appendLine()
            sb.appendLine("Generated: $stamp")
            sb.appendLine("Source files: $total")
            sb.appendLine("Path: ${projectDir.absolutePath}")
        } else {
            sb.appendLine("# 项目源码导出：$projName")
            sb.appendLine()
            sb.appendLine("> 生成时间：$stamp")
            sb.appendLine("> 源码文件数：$total")
            sb.appendLine("> 项目路径：${projectDir.absolutePath}")
        }
        sb.appendLine()
        sb.appendLine(if (opts.plainText) "DIRECTORY TREE:" else "## 目录结构")
        sb.appendLine("```")
        appendTree(sb, projectDir, opts)
        sb.appendLine("```")
        sb.appendLine()
        if (!opts.plainText) sb.appendLine("## 源码")
        var done = 0
        for (f in sources) {
            val rel = f.relativeTo(projectDir).path.replace('\\', '/')
            if (opts.plainText) {
                sb.appendLine("===== FILE: $rel =====")
                sb.appendLine(safeRead(f))
                sb.appendLine()
            } else {
                val lang = langOf(f.name)
                sb.appendLine("### $rel")
                sb.appendLine("```$lang")
                sb.appendLine(safeRead(f))
                sb.appendLine("```")
                sb.appendLine()
            }
            done++
            onProgress(done, total)
        }
        sb.toString()
    }

    private fun safeRead(f: File): String =
        runCatching { f.readText() }.getOrDefault("<<无法读取（非文本或编码异常）>>")

    private fun collectFiles(root: File, opts: Options): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File) {
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) {
                    if (shouldSkipDir(f.name, opts.includeGit)) return@forEach
                    walk(f)
                } else {
                    if (shouldSkipFile(f, opts.maxFileBytes)) return@forEach
                    result.add(f)
                }
            }
        }
        walk(root)
        return result
    }

    private fun shouldSkipDir(name: String, includeGit: Boolean): Boolean {
        if (name in DIR_SKIP) return true
        if (name.startsWith(".")) return name != ".git" || !includeGit
        return false
    }

    private fun shouldSkipFile(f: File, maxBytes: Long): Boolean {
        val name = f.name.lowercase()
        if (name == "local.properties") return true
        if (name.endsWith(".iml")) return true
        if (f.extension.lowercase() in BINARY_EXT) return true
        if (f.length() > maxBytes) return true
        return false
    }

    private fun appendTree(sb: StringBuilder, dir: File, opts: Options, indent: String = "") {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                if (shouldSkipDir(f.name, opts.includeGit)) return@forEach
                sb.appendLine("$indent${f.name}/")
                appendTree(sb, f, opts, "$indent  ")
            } else {
                if (shouldSkipFile(f, opts.maxFileBytes)) return@forEach
                sb.appendLine("$indent${f.name}")
            }
        }
    }

    private fun langOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "gradle" -> "gradle"
        "json" -> "json"
        "md" -> "markdown"
        "txt" -> "text"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "sha", "sh", "bash" -> "bash"
        "properties" -> "properties"
        "pro" -> "protobuf"
        "css", "scss", "sass" -> "css"
        "html", "htm" -> "html"
        "js", "mjs", "cjs" -> "javascript"
        "ts" -> "typescript"
        "py" -> "python"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
        "rs" -> "rust"
        "go" -> "go"
        "swift" -> "swift"
        else -> ""
    }
}
