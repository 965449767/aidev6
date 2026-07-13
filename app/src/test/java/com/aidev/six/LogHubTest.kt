package com.aidev.six

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogHubTest {

    private fun tempLogsDir(): File = Files.createTempDirectory("loghub-test").toFile()

    // ── LogWriter 基础行为 ──────────────────────────────────────────

    @Test
    fun `openBuildLog creates build log in project subdirectory`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "MyApp")
        writer.finish()

        val file = File(dir, "MyApp/build.log")
        assertTrue("日志文件应存在于子目录", file.exists())
        val content = file.readText()
        assertTrue("应包含项目名", content.contains("MyApp"))
        assertTrue("应包含完成标记", content.contains("完成"))
        dir.deleteRecursively()
    }

    @Test
    fun `openCrashLog creates temp file in root`() {
        val dir = tempLogsDir()
        val writer = LogHub.openCrashLog(dir, "req-999")
        writer.finish()

        val file = File(dir, "crash-req-999.log")
        assertTrue("临时日志文件应存在于根目录", file.exists())
        val content = file.readText()
        assertTrue("应包含完成标记", content.contains("完成"))
        dir.deleteRecursively()
    }

    @Test
    fun `moveTo moves crash log to pkg subdirectory`() {
        val dir = tempLogsDir()
        val writer = LogHub.openCrashLog(dir, "req-123")
        writer.append("测试内容")
        writer.finish()

        val pkgDir = LogHub.subdir(dir, "com.example.app")
        val moved = writer.moveTo(pkgDir, "crash.log")

        assertTrue("新文件应存在", moved.exists())
        assertFalse("旧临时文件应不存在", File(dir, "crash-req-123.log").exists())
        assertEquals("文件名应为 crash.log", "crash.log", moved.name)
        assertTrue("内容应保留", moved.readText().contains("测试内容"))
        dir.deleteRecursively()
    }

    @Test
    fun `append writes timestamped lines`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "App")
        writer.append("第一步")
        writer.append("第二步")
        writer.finish()

        val file = File(dir, "App/build.log")
        val lines = file.readLines()
        val contentLines = lines.filter { it.contains("第一步") || it.contains("第二步") }
        assertEquals("应有 2 条追加行", 2, contentLines.size)
        contentLines.forEach { line ->
            assertTrue("每行应有时间戳 [HH:mm:ss.SSS]", line.contains("[") && line.contains("]"))
        }
        dir.deleteRecursively()
    }

    // ── StepTimer 阶段计时 ──────────────────────────────────────────

    @Test
    fun `beginStep endStep records timing`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "App")
        val timer = LogHub.StepTimer(writer)

        timer.beginStep("准备")
        Thread.sleep(30)
        timer.endStep("准备完成")
        timer.beginStep("编译")
        Thread.sleep(30)
        timer.endStep("编译成功")
        writer.finish()

        val content = File(dir, "App/build.log").readText()
        assertTrue("应包含阶段开始标记", content.contains("▶ 阶段开始: 准备"))
        assertTrue("应包含阶段结束标记", content.contains("◀ 阶段结束: 准备"))
        assertTrue("应包含阶段开始标记", content.contains("▶ 阶段开始: 编译"))
        assertTrue("应包含阶段结束标记", content.contains("◀ 阶段结束: 编译"))

        val profile = timer.profileJson()
        assertTrue("profile 应包含 totalMs", profile.contains("totalMs"))
        assertTrue("profile 应包含 steps 数组", profile.contains("steps"))
        assertTrue("profile 应包含 步骤名 准备", profile.contains("准备"))
        assertTrue("profile 应包含 步骤名 编译", profile.contains("编译"))
        dir.deleteRecursively()
    }

    @Test
    fun `profileJson includes percent per step`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "App")
        val timer = LogHub.StepTimer(writer)

        timer.beginStep("长步骤")
        Thread.sleep(80)
        timer.endStep("完成")
        timer.beginStep("短步骤")
        Thread.sleep(20)
        timer.endStep("完成")
        writer.finish()

        val profile = timer.profileJson()
        assertTrue("长步骤 percent 应 ≥60%", Regex(""""percent"\s*:\s*(\d+)""").findAll(profile).any {
            it.groupValues[1].toInt() >= 60
        })
        dir.deleteRecursively()
    }

    @Test
    fun `beginStep auto-closes previous step`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "App")
        val timer = LogHub.StepTimer(writer)

        timer.beginStep("步骤A")
        Thread.sleep(20)
        timer.beginStep("步骤B")
        timer.endStep("B完成")
        writer.finish()

        val profile = timer.profileJson()
        assertTrue("应包含步骤A", profile.contains("步骤A"))
        assertTrue("应包含步骤B", profile.contains("步骤B"))
        dir.deleteRecursively()
    }

    // ── saveProfile ────────────────────────────────────────────────

    @Test
    fun `saveProfile writes latest file to project subdirectory`() {
        val dir = tempLogsDir()
        val writer = LogHub.openBuildLog(dir, "MyApp")
        val timer = LogHub.StepTimer(writer)
        timer.beginStep("test")
        timer.endStep("done")
        writer.finish()

        LogHub.saveProfile(dir, timer.profileJson(), "build", "MyApp")

        val latest = File(dir, "MyApp/latest-build-profile.json")
        assertTrue("latest-build-profile.json 应存在于子目录", latest.exists())
        assertTrue("应包含 totalMs", latest.readText().contains("totalMs"))
        dir.deleteRecursively()
    }

    // ── 完整闭环模拟 ────────────────────────────────────────────────

    @Test
    fun `full build cycle with project subdirectory`() {
        val dir = tempLogsDir()

        val writer = LogHub.openBuildLog(dir, "MyAndroidProject")
        val timer = LogHub.StepTimer(writer)

        timer.beginStep("准备宇宙 B")
        writer.append("确保 rootfs + JDK 17")
        Thread.sleep(20)
        timer.endStep("rootfs + JDK + aapt2")

        timer.beginStep("编译 (全量编译)")
        writer.append("cd /workspace/myapp && ./gradlew assembleDebug")
        Thread.sleep(40)
        timer.endStep("编译成功")

        timer.beginStep("安装")
        writer.append("Shizuku pm install")
        Thread.sleep(20)
        timer.endStep("安装成功")

        timer.beginStep("拉起")
        writer.append("am start com.example.app/.MainActivity")
        Thread.sleep(10)
        timer.endStep("拉起成功")

        writer.finish()

        LogHub.saveProfile(dir, timer.profileJson(), "build", "MyAndroidProject")

        // 验证日志文件在项目子目录
        val logFile = File(dir, "MyAndroidProject/build.log")
        assertTrue("日志文件应存在于子目录", logFile.exists())
        val logContent = logFile.readText()
        assertTrue("应包含项目名", logContent.contains("MyAndroidProject"))
        assertTrue("应包含 4 个阶段", logContent.count { it == '▶' } == 4)
        assertTrue("应包含完成标记", logContent.contains("完成"))

        // 验证 profile 文件
        val latest = File(dir, "MyAndroidProject/latest-build-profile.json")
        assertTrue("latest-build-profile.json 应存在", latest.exists())
        val profile = latest.readText()
        assertTrue("profile 应包含 totalMs", profile.contains("totalMs"))
        assertTrue("profile 应包含 4 个步骤", profile.contains("准备宇宙 B"))

        dir.deleteRecursively()
    }

    @Test
    fun `full crash cycle with pkg subdirectory`() {
        val dir = tempLogsDir()

        val writer = LogHub.openCrashLog(dir, "req-20260710")
        val timer = LogHub.StepTimer(writer)

        timer.beginStep("解析请求")
        writer.append("目标包名: com.example.app")
        timer.endStep("pkg=com.example.app, lines=1000")

        timer.beginStep("抓取 logcat")
        writer.append("logcat 原始数据 12345 字符")
        timer.endStep("抓取成功, 12345 字符")

        writer.finish()

        // 模拟解析出 pkg 后移到子目录
        val pkgDir = LogHub.subdir(dir, "com.example.app")
        writer.moveTo(pkgDir, "crash.log")

        LogHub.saveProfile(dir, timer.profileJson(), "crash", "com.example.app")

        // 验证日志文件在包名子目录
        val logFile = File(dir, "com.example.app/crash.log")
        assertTrue("日志文件应存在于包名子目录", logFile.exists())
        val logContent = logFile.readText()
        assertTrue("应包含包名", logContent.contains("com.example.app"))
        assertTrue("应包含 2 个阶段", logContent.count { it == '▶' } == 2)
        assertTrue("应包含完成标记", logContent.contains("完成"))

        // 验证 profile 文件
        val latest = File(dir, "com.example.app/latest-crash-profile.json")
        assertTrue("latest-crash-profile.json 应存在", latest.exists())

        dir.deleteRecursively()
    }

    // ── GradleFilter 过滤 ────────────────────────────────────────────

    @Test
    fun `GradleFilter keeps error lines`() {
        val filter = LogHub.GradleFilter()
        assertTrue("应保留 error 行", filter.shouldKeep("error: resource color/calc_background not found"))
        assertTrue("应保留 FAILED", filter.shouldKeep("BUILD FAILED in 45s"))
        assertTrue("应保留 Exception", filter.shouldKeep("Execution failed for task"))
    }

    @Test
    fun `GradleFilter collapses warnings`() {
        val filter = LogHub.GradleFilter()
        assertFalse("WARNING 行应被折叠", filter.shouldKeep("WARNING: The option setting is experimental."))
        assertFalse("WARNING 行应被折叠", filter.shouldKeep("WARNING: We recommend using a newer Android Gradle plugin"))
        val summary = filter.flushWarnings()
        assertNotNull("应有警告摘要", summary)
        assertTrue("摘要应包含计数", summary?.contains("2") == true)
    }

    @Test
    fun `GradleFilter drops deprecated warnings`() {
        val filter = LogHub.GradleFilter()
        assertFalse("Deprecated 行应被丢弃", filter.shouldKeep("Deprecated Gradle features were used in this build"))
        assertFalse("warning-mode 行应被丢弃", filter.shouldKeep("You can use '--warning-mode all' to show"))
    }

    @Test
    fun `GradleFilter keeps our own lines`() {
        val filter = LogHub.GradleFilter()
        assertTrue("时间戳行应保留", filter.shouldKeep("[12:00:01.000 +0ms] 编译中"))
        assertTrue("阶段标记应保留", filter.shouldKeep("▶ 阶段开始: 编译"))
        assertTrue("header 应保留", filter.shouldKeep("=== 构建 MyApp ==="))
        assertTrue("箭头行应保留", filter.shouldKeep("→ aapt2 就绪"))
    }
}
