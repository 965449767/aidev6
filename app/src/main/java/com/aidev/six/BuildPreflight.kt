package com.aidev.six

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
     * 源码预检：检查 import 引用、Manifest 组件声明、资源引用。
     * @param projectDir 项目根目录
     * @return 告警消息列表（空 = 无问题）
     */
    fun inspectSourceCode(projectDir: File): List<String> {
        val messages = mutableListOf<String>()
        val srcDir = File(projectDir, "app/src/main")
        if (!srcDir.isDirectory) return emptyList()

        // 收集所有 Kotlin/Java 源文件中的 import
        val sourceFiles = srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

        // 收集项目中定义的所有全限定类名
        val definedClasses = mutableSetOf<String>()
        for (file in sourceFiles) {
            val pkg = extractPackage(file.readText())
            val className = file.nameWithoutExtension
            if (pkg != null) definedClasses.add("$pkg.$className")
        }

        // 检查 import 是否引用了不存在的本地类（排除 Android/Java/Kotlin 标准库和第三方库）
        for (file in sourceFiles) {
            val content = file.readText()
            val imports = Regex("""import\s+([\w.]+)""").findAll(content)
            for (match in imports) {
                val fqn = match.groupValues[1]
                // 只检查项目包下的 import
                val rootPkg = extractPackage(content)?.substringBeforeLast('.')
                if (rootPkg != null && fqn.startsWith(rootPkg) && fqn !in definedClasses) {
                    messages += "⚠ 源码预检：${file.name} import 了不存在的类「$fqn」，编译将报 Unresolved reference"
                }
            }
        }

        // 检查 Manifest 中声明的 Activity/Service 是否在源码中存在
        val manifestFile = File(srcDir, "AndroidManifest.xml")
        if (manifestFile.isFile) {
            val manifest = manifestFile.readText()
            val componentPattern = Regex("""android:name="(\.[\w.]+)"""")
            for (match in componentPattern.findAll(manifest)) {
                val name = match.groupValues[1]
                if (name.startsWith(".")) {
                    val manifestPkg = Regex("""package="([\w.]+)"""").find(manifest)?.groupValues?.get(1)
                    if (manifestPkg != null) {
                        val fqn = manifestPkg + name
                        if (fqn !in definedClasses) {
                            messages += "⚠ 源码预检：Manifest 声明了组件「$fqn」但源码中未找到对应类"
                        }
                    }
                }
            }
        }

        // 检查资源引用：扫描 layout XML 中的 @color/@drawable/@string/@dimen/@style 引用
        messages.addAll(inspectResourceRefs(projectDir))

        return messages
    }

    /**
     * 扫描 layout XML 中的资源引用，检查是否在 res/values/ 中定义。
     * 只检查 @color/、@string/ 两类（最常见的 vibe coding 缺失资源）。
     */
    private fun inspectResourceRefs(projectDir: File): List<String> {
        val messages = mutableListOf<String>()
        val srcDir = File(projectDir, "app/src/main")
        val resDir = File(srcDir, "res")
        if (!resDir.isDirectory) return emptyList()

        // 1. 收集 res/values/ 中已定义的资源名
        val definedResources = mutableMapOf<String, MutableSet<String>>() // type -> names
        val valuesDir = File(resDir, "values")
        if (valuesDir.isDirectory) {
            for (f in valuesDir.listFiles()?.filter { it.extension == "xml" } ?: emptyList()) {
                val content = f.readText()
                // <string name="app_name">...</string>
                Regex("""<(string|color|dimen|style|drawable|integer|bool|array|attr)\s+name="(\w+)"""")
                    .findAll(content).forEach { match ->
                        definedResources.getOrPut(match.groupValues[1]) { mutableSetOf() }.add(match.groupValues[2])
                    }
                // <style name="AppTheme" ...>（style 可能没有 type 属性）
                Regex("""<style\s+name="(\w+)"""").findAll(content).forEach { match ->
                    definedResources.getOrPut("style") { mutableSetOf() }.add(match.groupValues[1])
                }
            }
        }

        // 2. 扫描 layout XML 中的 @type/name 引用
        val layoutDir = File(resDir, "layout")
        if (layoutDir.isDirectory) {
            for (f in layoutDir.listFiles()?.filter { it.extension == "xml" } ?: emptyList()) {
                val content = f.readText()
                // @color/calc_background, @string/app_name, @drawable/icon
                Regex("""@(\w+)/(\w+)""").findAll(content).forEach { match ->
                    val type = match.groupValues[1]
                    val name = match.groupValues[2]
                    // 只检查我们已收集的资源类型
                    if (type in definedResources && name !in definedResources[type]!!) {
                        messages += "⚠ 资源预检：${f.name} 引用了 @type/$name，但 res/values/ 中未定义该资源，编译将报 not found"
                    }
                }
            }
        }

        return messages
    }

    private fun extractPackage(content: String): String? {
        val match = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
            .find(content) ?: return null
        return match.groupValues[1]
    }

    /**
     * 设备/宇宙B 硬禁止的权限（来源：AGENTS.md 约束）。
     * 命中即构建或安装必失败，应在构建前明确拦截，而不是浪费数分钟编译后报错。
     */
    val HARD_BLOCKER_PERMISSIONS: List<String> = listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
    )

    /** 构建前护栏结果：hardErrors 命中即应阻断构建；warnings 仅提示。 */
    data class PreconditionResult(val hardErrors: List<String>, val warnings: List<String>)

    /**
     * 构建前护栏（vibe coding 护栏的「硬」一层）：
     *  1) Manifest 含 HARD_BLOCKER 权限 → hardErrors（设备/宇宙B 禁止）
     *  2) 离线且基线依赖未预缓存 → warnings（提示先 aidev-precache，避免缺包失败）
     * 纯逻辑、可单测；不修改任何文件。
     */
    fun checkPreconditions(projectDir: File): PreconditionResult {
        val hard = mutableListOf<String>()
        val warn = mutableListOf<String>()

        val manifest = File(projectDir, "app/src/main/AndroidManifest.xml")
        if (manifest.isFile) {
            val text = runCatching { manifest.readText() }.getOrDefault("")
            HARD_BLOCKER_PERMISSIONS.forEach { perm ->
                if (Regex("""android:name="$perm"""").containsMatchIn(text)) {
                    hard += "✖ 硬限制命中：Manifest 声明了受限权限 $perm（设备/宇宙B 禁止，构建或安装必失败）。" +
                        "请改用其他方案，或在合规环境单独处理。"
                }
            }
        }

        if (isOffline() && !baselineDepsCached()) {
            warn += "⚠ 当前离线且基线依赖未预缓存（material-icons-extended 等缺失）。" +
                "若构建报『Could not resolve』，请联网后运行 aidev-precache 再构建。"
        }

        return PreconditionResult(hard, warn)
    }

    /** 粗粒度离线探测（1.5s 超时）。仅用于「离线+缺基线」的软提示。 */
    private fun isOffline(): Boolean = try {
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getByName("maven.aliyun.com"), 443), 1500)
        socket.close()
        false
    } catch (_: Exception) {
        true
    }

    /** 基线依赖是否已预缓存：检查 material-icons-extended（模板基线独有标记）是否在任一 Gradle 缓存中。 */
    private fun baselineDepsCached(): Boolean {
        val homes = listOf(
            System.getProperty("user.home") + "/.gradle",
            "/host-home/gradle-cache",
        )
        val marker = "caches/modules-2/files-2.1/androidx.compose.material/material-icons-extended"
        return homes.any { File(it, marker).isDirectory }
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
