package com.aidev.six

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把安卓项目源码合成为「AI 可读」文本文档（Markdown 或纯文本）：
 * 开头附目录树与统计，随后每个源文件以「相对路径 + 代码块」呈现。
 * 默认排除构建产物、IDE 缓存、二进制；.git 可选择包含。
 *
 * 流式写入：exportToFile 直接写入 BufferedWriter，
 * 内存占用仅为单文件大小，不会因整体 StringBuilder 而 OOM。
 */
object ProjectExporter {

    data class Options(
        val includeGit: Boolean = false,
        val plainText: Boolean = false,
        val maxFileBytes: Long = 512 * 1024,
    )

    private val DIR_SKIP = setOf("build", "captures", "node_modules", "target", "vendor", "Pods", ".gradle", ".cxx")
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
        require(projectDir.isDirectory) { "项目目录不存在：${projectDir.absolutePath}" }
        val sources = collectFiles(projectDir, opts)
        val total = sources.size
        outFile.parentFile?.mkdirs()
        outFile.bufferedWriter().use { writer ->
            writeHeader(writer, projectDir, opts, total)
            writer.newLine()
            writeTreeSection(writer, projectDir, opts)
            writer.newLine()
            if (!opts.plainText) {
                writer.write("## 源码")
                writer.newLine()
            }
            var done = 0
            for (f in sources) {
                writeSourceFile(writer, f, projectDir, opts)
                done++
                onProgress(done, total)
            }
        }
    }

    private fun writeHeader(writer: BufferedWriter, projectDir: File, opts: Options, fileCount: Int) {
        val projName = projectDir.name
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        if (opts.plainText) {
            writer.write("PROJECT SOURCE: $projName")
            writer.newLine()
            writer.newLine()
            writer.write("Generated: $stamp")
            writer.newLine()
            writer.write("Source files: $fileCount")
            writer.newLine()
            writer.write("Path: ${projectDir.absolutePath}")
        } else {
            writer.write("# 项目源码导出：$projName")
            writer.newLine()
            writer.newLine()
            writer.write("> 生成时间：$stamp")
            writer.newLine()
            writer.write("> 源码文件数：$fileCount")
            writer.newLine()
            writer.write("> 项目路径：${projectDir.absolutePath}")
        }
    }

    private fun writeTreeSection(writer: BufferedWriter, projectDir: File, opts: Options) {
        writer.write(if (opts.plainText) "DIRECTORY TREE:" else "## 目录结构")
        writer.newLine()
        writer.write("```")
        writer.newLine()
        writeTree(writer, projectDir, opts)
        writer.write("```")
        writer.newLine()
    }

    private fun writeSourceFile(writer: BufferedWriter, file: File, projectDir: File, opts: Options) {
        val rel = file.relativeTo(projectDir).path.replace('\\', '/')
        if (opts.plainText) {
            writer.write("===== FILE: $rel =====")
            writer.newLine()
            writer.write(safeRead(file))
            writer.newLine()
            writer.newLine()
        } else {
            val lang = langOf(file.name)
            writer.write("### $rel")
            writer.newLine()
            writer.write("```$lang")
            writer.newLine()
            writer.write(safeRead(file))
            writer.newLine()
            writer.write("```")
            writer.newLine()
            writer.newLine()
        }
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

    private fun writeTree(writer: BufferedWriter, dir: File, opts: Options, indent: String = "") {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isDirectory) {
                if (shouldSkipDir(f.name, opts.includeGit)) return@forEach
                writer.write("$indent${f.name}/")
                writer.newLine()
                writeTree(writer, f, opts, "$indent  ")
            } else {
                if (shouldSkipFile(f, opts.maxFileBytes)) return@forEach
                writer.write("$indent${f.name}")
                writer.newLine()
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
        "ts", "tsx", "mjs" -> "typescript"
        "py" -> "python"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
        "rs" -> "rust"
        "go" -> "go"
        "swift" -> "swift"
        else -> ""
    }
}
