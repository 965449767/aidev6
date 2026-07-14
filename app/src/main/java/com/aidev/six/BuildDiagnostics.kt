package com.aidev.six

/**
 * 构建错误诊断工具：解析常见编译错误，返回中文修复建议。
 * 纯逻辑，无 Android 依赖，可单测。
 */
object BuildDiagnostics {

    /** 解析常见编译错误，返回所有匹配的中文修复建议列表；无匹配返回空列表。 */
    fun diagnoseBuildErrors(log: String): List<String> {
        val hints = mutableListOf<String>()
        val lines = log.lines()

        // 资源链接失败（逐条匹配所有缺失资源）
        // 兼容两种格式：
        //   - "resource color/calc_background (aka com.example:color/calc_background) not found"
        //   - "Android resource linking failed\ncolor/calc_background not found"（Gradle 真实输出，无 "resource" 词）
        val missingResources = Regex(
            """(?:resource )?((?:color|string|layout|drawable|dimen|style|mipmap|id|menu|anim|attr|array|integer|bool|font|raw|xml|navigation|transition)/(?:\S+?))(?:\s*\([^)]*\))?\s+not found"""
        ).findAll(log).map { it.groupValues[1] }.distinct().toList()
        if (missingResources.isNotEmpty()) {
            missingResources.forEach { res ->
                hints.add("资源缺失「$res」：检查 res/values/ 目录是否定义了该资源，或 XML 中引用是否拼写正确")
            }
        }

        // Unresolved reference
        lines.filter { it.contains("Unresolved reference:") }.forEach { line ->
            val ref = line.substringAfter("Unresolved reference:").trim().split("\\s".toRegex()).first()
            hints.add("未找到引用「$ref」：检查 import 语句是否正确，或该类/函数是否在依赖中")
        }

        // Type mismatch
        lines.filter { it.contains("Type mismatch:") }.forEach { line ->
            val detail = line.substringAfter("Type mismatch:").trim().take(80)
            hints.add("类型不匹配：$detail — 检查赋值或参数类型是否正确")
        }

        // Could not resolve
        lines.filter { it.contains("Could not resolve", ignoreCase = true) }.forEach { line ->
            val dep = line.substringAfter("Could not resolve").trim().take(80)
            hints.add("无法解析依赖「$dep」：检查网络连接和 Maven 仓库配置")
        }

        // 离线/缺缓存导致依赖解析失败 → 提示预缓存（dev-workflow 基线保障）
        val couldNotResolve = lines.filter { it.contains("Could not resolve", ignoreCase = true) }
        if (couldNotResolve.isNotEmpty()) {
            val offline = couldNotResolve.any {
                it.contains("No cached version", ignoreCase = true) || it.contains("Could not get resource", ignoreCase = true)
            }
            val iconsMissing = log.contains("material-icons-extended")
            if (offline || iconsMissing) {
                hints.add("依赖无法解析（可能离线或缓存缺失）：联网后运行 `aidev-precache` 预缓存基线依赖（含 material-icons-extended），之后可断网构建；也可 `aidev-precache --universe-b` 预热宇宙 B 缓存。")
            }
        }

        // Missing class
        lines.filter { it.contains("Missing class") }.forEach { line ->
            val cls = line.substringAfter("Missing class").trim().split("\\s".toRegex()).first()
            hints.add("缺少类「$cls」：可能是依赖缺失或版本不兼容，检查 build.gradle.kts")
        }

        // OutOfMemoryError
        if (log.contains("OutOfMemoryError") || log.contains("java.lang.OutOfMemoryError")) {
            hints.add("内存不足：尝试增大 JVM 堆内存（gradle.properties 中 -Xmx）")
        }

        // Could not find method
        lines.filter { it.contains("Could not find method") }.forEach { line ->
            val method = line.substringAfter("Could not find method").trim().take(60)
            hints.add("找不到方法 $method：检查 DSL 语法和插件版本是否匹配")
        }

        // 签名错误
        if (log.contains("Keystore file") && log.contains("not found")) {
            hints.add("签名文件缺失：检查 keystore 路径配置，debug 构建应使用默认 debug.keystore")
        }
        if (log.contains("keystore password was incorrect") || log.contains("password was incorrect")) {
            hints.add("签名密码错误：检查 keystore 密码是否正确")
        }

        // SDK 路径问题
        if (log.contains("SDK location not found") || log.contains("sdk.dir")) {
            hints.add("SDK 路径未找到：检查 local.properties 中 sdk.dir 配置")
        }

        // Java 版本不兼容
        if (log.contains("source version") && log.contains("target version")) {
            hints.add("Java 版本不兼容：检查 compileOptions 中 sourceCompatibility 和 targetCompatibility 配置")
        }

        // minSdk 版本问题
        if (log.contains("MinSdkVersion") && log.contains("cannot be smaller than")) {
            hints.add("minSdk 版本过低：检查 minSdk 配置是否满足依赖库的最低要求")
        }

        return hints
    }

    /** 兼容旧接口：返回第一条建议，无匹配返回 null。 */
    fun diagnoseBuildError(log: String): String? = diagnoseBuildErrors(log).firstOrNull()
}
