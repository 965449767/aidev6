package com.aidev.six.context

import java.io.File

/**
 * 代码符号模型：类 / 接口 / 对象 / 函数 / 清单组件，及其标签与依赖。
 */
data class CodeSymbol(
    val name: String,
    val kind: String,
    val file: String,
    val line: Int,
    val summary: String,
    val tags: List<String>,
    val deps: List<String>,
)

/**
 * 代码索引器（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 * 扫描项目源树，抽取符号（类/接口/对象/函数/清单组件）及其标签、依赖。
 * 替代 aidev-index.sh 的 grep 方案；持久化由 [ContextManager] 负责。
 */
object CodeIndexer {
    private val DECL_RE = Regex(
        """^\s*(?:public |private |internal |protected |open |abstract |sealed |data |inner |enum |final |override |suspend |inline |external |expect |actual )*(class|interface|object|fun)\s+([A-Za-z_][A-Za-z0-9_]*)"""
    )
    private val ANNOTATION_RE = Regex("""@(\w+)""")
    private val COMPONENT_RE = Regex("""android:name="([^"]+)"""")

    fun indexDirectory(rootDir: File): List<CodeSymbol> {
        val result = mutableListOf<CodeSymbol>()
        if (!rootDir.isDirectory) return result

        rootDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { !it.path.contains("/build/") && !it.path.contains("/generated/") && !it.path.contains("/.gradle/") }
            .forEach { file ->
                val rel = runCatching { file.relativeTo(rootDir).path }.getOrDefault(file.path)
                var prevRaw = ""
                file.useLines { lines ->
                    lines.forEachIndexed { i, raw ->
                        val m = DECL_RE.find(raw)
                        if (m != null) {
                            val kind = normalizeKind(m.groupValues[1])
                            val name = m.groupValues[2]
                            val tags = ANNOTATION_RE.findAll("$prevRaw\n$raw").map { it.groupValues[1] }.toList()
                            val deps = extractDeps(raw)
                            result.add(CodeSymbol(name, kind, rel, i + 1, raw.trim().take(160), tags, deps))
                        }
                        prevRaw = raw
                    }
                }
            }

        val manifest = File(rootDir, "app/src/main/AndroidManifest.xml")
        if (manifest.isFile) {
            manifest.useLines { lines ->
                lines.forEachIndexed { i, line ->
                    val c = COMPONENT_RE.find(line)
                    if (c != null) {
                        result.add(
                            CodeSymbol(
                                name = c.groupValues[1],
                                kind = "component",
                                file = "app/src/main/AndroidManifest.xml",
                                line = i + 1,
                                summary = line.trim(),
                                tags = emptyList(),
                                deps = emptyList(),
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun normalizeKind(k: String): String = when (k) {
        "class" -> "class"
        "interface" -> "interface"
        "object" -> "object"
        "fun" -> "function"
        else -> k
    }

    private fun extractDeps(line: String): List<String> {
        return Regex("""\b([A-Z][A-Za-z0-9]+)\b""").findAll(line)
            .map { it.groupValues[1] }
            .filter { it.length > 2 }
            .toSet().toList().take(12)
    }
}
