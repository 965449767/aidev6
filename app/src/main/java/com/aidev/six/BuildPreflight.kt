package com.aidev.six

/**
 * 预构建体检的纯逻辑（无 Android 依赖，可单测）。
 *
 * 针对 vibe coding 小白：OpenCode 生成的 `app/build.gradle.kts` 常犯几类宇宙B 必失败的错误，
 * 编译前扫描并尽力自动修复 + 产出中文告警。
 */
object BuildPreflight {

    /** 体检结果：修复后的文本（若无改动则与入参一致）+ 面向用户的中文提示。 */
    data class Result(val fixedText: String, val messages: List<String>)

    /**
     * @param appGradle app/build.gradle.kts 内容
     * @param rootGradle 根 build.gradle.kts 内容（判断 Compose 插件用；缺省空）
     */
    fun inspect(appGradle: String, rootGradle: String = ""): Result {
        val messages = mutableListOf<String>()
        var text = appGradle

        val stripped = stripTopLevelBlock(text, "repositories")
        if (stripped != text) {
            text = stripped
            messages += "⚠ 体检：已自动移除 app/build.gradle.kts 里的模块级 repositories{}（宇宙B 统一用 settings 的阿里云镜像，模块级会触发 FAIL_ON_PROJECT_REPOS 硬失败）"
        }

        Regex("""compileSdk\s*=?\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { sdk ->
            if (sdk != 36) messages += "⚠ 体检：compileSdk=$sdk 与宇宙B 已装的 android-36 不符，建议改回 36（缺对应 SDK 平台会编译失败）"
        }

        val usesCompose = text.contains("androidx.compose") || text.contains("compose = true") || text.contains("compose=true")
        if (usesCompose) {
            val hasComposePlugin = text.contains("plugin.compose") || rootGradle.contains("plugin.compose")
            val hasBuildFeature = text.contains("compose = true") || text.contains("compose=true")
            if (!hasComposePlugin || !hasBuildFeature) {
                messages += "⚠ 体检：疑似用了 Jetpack Compose 但配置不完整（需 org.jetbrains.kotlin.plugin.compose 插件 + buildFeatures{compose=true} + compose-bom），编译可能失败"
            }
        }

        return Result(text, messages)
    }

    /**
     * 移除源码中所有出现在【顶层（大括号深度 0）】的 `<keyword> { ... }` 块。
     * 字符级大括号配平，避免破坏嵌套；不处理字符串/注释内的伪命中（极罕见）。
     */
    fun stripTopLevelBlock(source: String, keyword: String): String {
        val out = StringBuilder()
        var depth = 0
        var i = 0
        val n = source.length
        while (i < n) {
            if (depth == 0 && source.startsWith(keyword, i)) {
                val prevOk = out.isEmpty() || out.last().let { !it.isLetterOrDigit() && it != '_' }
                val afterIdx = i + keyword.length
                val afterOk = afterIdx >= n || source[afterIdx].let { !it.isLetterOrDigit() && it != '_' }
                if (prevOk && afterOk) {
                    var j = afterIdx
                    while (j < n && source[j].isWhitespace()) j++
                    if (j < n && source[j] == '{') {
                        var d = 0
                        var k = j
                        while (k < n) {
                            when (source[k]) {
                                '{' -> d++
                                '}' -> { d--; if (d == 0) { k++; break } }
                            }
                            k++
                        }
                        while (k < n && (source[k] == '\n' || source[k] == '\r')) k++
                        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.deleteCharAt(out.length - 1)
                        i = k
                        continue
                    }
                }
            }
            val c = source[i]
            if (c == '{') depth++ else if (c == '}') depth = (depth - 1).coerceAtLeast(0)
            out.append(c)
            i++
        }
        return out.toString()
    }
}
