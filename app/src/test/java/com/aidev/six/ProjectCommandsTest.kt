package com.aidev.six

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectCommandsTest {

    @Test
    fun androidProjectTemplatesUseGradleCommands() {
        val dir = Files.createTempDirectory("android-project").toFile()
        File(dir, "build.gradle.kts").writeText("plugins { id('com.android.application') }")

        val templates = ProjectCommands.taskTemplates(dir)

        assertTrue(templates.any { it.name == "Gradle 构建" && it.command == "./gradlew assembleDebug" })
        assertTrue(templates.any { it.name == "Gradle 测试" && it.command == "./gradlew test" })
        assertTrue(templates.any { it.name == "Gradle 健康检查" && it.command == "./gradlew tasks --all | head -80" })
    }

    @Test
    fun unknownProjectFallsBackToShellProbe() {
        val templates = ProjectCommands.taskTemplates(File("/tmp"))

        assertTrue(templates.isNotEmpty())
        assertEquals("探测项目", templates.first().name)
    }
}
