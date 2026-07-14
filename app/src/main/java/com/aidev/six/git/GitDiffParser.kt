package com.aidev.six.git

/**
 * 纯 Kotlin 的 Git diff 评审解析器（无 Android 依赖，可 JVM 单测）。
 *
 * 输入为 `git diff --numstat HEAD` 的文本输出，输出按文件聚合的改动统计 +
 * 启发式风险星（1–5）+ 分类（Compose/Shell/Build/Doc/Native/Other）+ 关注点。
 *
 * 复用约定：与 CodeIndexer 同属「AI 上下文」地基，目标是让 DevCenter 在提交前
 * 给出「这次改动改了什么、哪里高风险」，并作为 Prompt Builder / AI Review 的输入。
 */
object GitDiffParser {

    enum class DiffCategory { COMPOSE, SHELL, BUILD, DOC, NATIVE, OTHER }

    data class FileDiff(
        val path: String,
        val additions: Int,
        val deletions: Int,
        val category: DiffCategory,
        val riskStars: Int,
        val notes: List<String>,
    )

    data class ReviewSummary(
        val files: Int,
        val additions: Int,
        val deletions: Int,
        val highRisk: Int,
        val midRisk: Int,
        val lowRisk: Int,
        val maxRisk: Int,
    )

    /** 解析 `git diff --numstat` 的每一行：`adds\tdels\tpath`，二进制为 `-`，重命名为 `old => new`。 */
    fun parseNumstat(text: String): List<FileDiff> {
        val result = mutableListOf<FileDiff>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val addsRaw = parts[0].trim()
            val delsRaw = parts[1].trim()
            val rawPath = parts[2].trim()
            val additions = if (addsRaw == "-") 0 else addsRaw.toIntOrNull() ?: 0
            val deletions = if (delsRaw == "-") 0 else delsRaw.toIntOrNull() ?: 0
            val path = if (" => " in rawPath) {
                rawPath.substringAfterLast(" => ").trim()
            } else {
                rawPath
            }
            result.add(buildFileDiff(path, additions, deletions))
        }
        return result
    }

    fun summarize(diff: List<FileDiff>): ReviewSummary {
        val adds = diff.sumOf { it.additions }
        val dels = diff.sumOf { it.deletions }
        val high = diff.count { it.riskStars >= 4 }
        val mid = diff.count { it.riskStars == 3 }
        val low = diff.count { it.riskStars <= 2 }
        val max = diff.maxOfOrNull { it.riskStars } ?: 0
        return ReviewSummary(diff.size, adds, dels, high, mid, low, max)
    }

    /** 把全部 diff 拼成供 AI 评审的纯文本（numstat 概览 + 关注点）。 */
    fun toReviewPrompt(diff: List<FileDiff>): String {
        if (diff.isEmpty()) return "本次无未提交改动（git diff HEAD 为空）。"
        val sb = StringBuilder()
        sb.appendLine("请评审以下未提交改动（git diff HEAD --numstat），按风险从高到低：")
        for (f in diff) {
            sb.appendLine("- [${"★".repeat(f.riskStars)}${"☆".repeat(5 - f.riskStars)}] ${f.path} (+${f.additions}/-${f.deletions}) [${f.category}]")
            for (n in f.notes) sb.appendLine("    · $n")
        }
        sb.appendLine("请给出：1) 整体风险评级；2) 每块需重点检查的点；3) 是否建议拆分提交。")
        return sb.toString()
    }

    private fun buildFileDiff(path: String, additions: Int, deletions: Int): FileDiff {
        val category = classify(path)
        val risk = riskStars(path, category, additions, deletions)
        return FileDiff(path, additions, deletions, category, risk, notesFor(path, category))
    }

    private fun classify(path: String): DiffCategory {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".sh") || lower.endsWith(".bash") -> DiffCategory.SHELL
            lower.contains("gradle") || lower.endsWith("androidmanifest.xml") ||
                lower.contains("proguard") || lower.contains("build.gradle") -> DiffCategory.BUILD
            lower.endsWith(".so") || lower.contains("jnilibs") || lower.contains("/cpp/") ||
                lower.contains("cmakelists") -> DiffCategory.NATIVE
            lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".yaml") ||
                lower.endsWith(".yml") || lower.endsWith(".json") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".svg") ||
                (lower.endsWith(".xml") && !lower.endsWith("androidmanifest.xml")) -> DiffCategory.DOC
            lower.endsWith(".kt") || lower.endsWith(".java") -> DiffCategory.COMPOSE
            else -> DiffCategory.OTHER
        }
    }

    private fun riskStars(path: String, category: DiffCategory, additions: Int, deletions: Int): Int {
        val size = additions + deletions
        var base = when {
            size <= 10 -> 1
            size <= 50 -> 2
            size <= 200 -> 3
            size <= 500 -> 4
            else -> 5
        }
        base += when (category) {
            DiffCategory.BUILD -> 1
            DiffCategory.NATIVE -> 1
            DiffCategory.SHELL -> 1
            DiffCategory.DOC -> -1
            else -> 0
        }
        val lower = path.lowercase()
        if (lower.contains("androidmanifest") || lower.contains("permission") || lower.contains("shizuku")) base += 2
        if (lower.contains("provider") || lower.contains("exported")) base += 1
        base = when (category) {
            DiffCategory.BUILD, DiffCategory.NATIVE -> base.coerceAtLeast(3)
            DiffCategory.SHELL -> base.coerceAtLeast(2)
            else -> base
        }
        return base.coerceIn(1, 5)
    }

    private fun notesFor(path: String, category: DiffCategory): List<String> {
        val lower = path.lowercase()
        val notes = mutableListOf<String>()
        when (category) {
            DiffCategory.BUILD -> notes.add("涉及构建/依赖配置，注意版本锁与 Gradle 配置不被破坏")
            DiffCategory.NATIVE -> notes.add("涉及原生库/NDK，需确认 ABI 与打包（useLegacyPackaging）")
            DiffCategory.SHELL -> notes.add("涉及脚本，注意路径引号与执行权限")
            DiffCategory.DOC -> notes.add("文档/资源改动，风险较低，留意字符串/资源引用")
            else -> Unit
        }
        if (lower.contains("androidmanifest") || lower.contains("permission") || lower.contains("exported")) {
            notes.add("涉及权限或组件导出，需重新审计清单与隐私合规")
        }
        if (lower.contains("activity") || lower.contains("service") || lower.contains("viewmodel") || lower.contains("repository")) {
            notes.add("涉及生命周期/状态，注意内存泄漏、线程与并发")
        }
        if (lower.contains("compose") && category == DiffCategory.COMPOSE) {
            notes.add("涉及 Compose UI，留意重组性能与状态提升")
        }
        return notes.distinct()
    }
}
