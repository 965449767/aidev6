package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ProjectDetector 单元测试。
 * 覆盖：isAndroidProject / isProjectRoot / findProjectRoot / findAndroidProjects 等。
 */
class ProjectDetectorTest {

    private fun makeTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pd-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ── isAndroidProject ──────────────────────────────────────────

    @Test
    fun `isAndroidProject - Kotlin DSL 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "app").mkdir()
            File(dir, "settings.gradle.kts").writeText("")
            File(dir, "app/build.gradle.kts").writeText("")
            assertTrue(ProjectDetector.isAndroidProject(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isAndroidProject - Groovy DSL 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "app").mkdir()
            File(dir, "settings.gradle").writeText("")
            File(dir, "app/build.gradle").writeText("")
            assertTrue(ProjectDetector.isAndroidProject(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isAndroidProject - 没有app模块不是Android项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "settings.gradle.kts").writeText("")
            assertFalse(ProjectDetector.isAndroidProject(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isAndroidProject - 空目录`() {
        val dir = makeTempDir()
        try {
            assertFalse(ProjectDetector.isAndroidProject(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isAndroidProject - 不存在的目录`() {
        assertFalse(ProjectDetector.isAndroidProject(File("/nonexistent/path")))
    }

    @Test
    fun `isAndroidProject - 文件而非目录`() {
        val tmp = File.createTempFile("test-", ".txt")
        try {
            assertFalse(ProjectDetector.isAndroidProject(tmp))
        } finally {
            tmp.delete()
        }
    }

    // ── isProjectRoot ─────────────────────────────────────────────

    @Test
    fun `isProjectRoot - 有settings gradle`() {
        val dir = makeTempDir()
        try {
            File(dir, "settings.gradle.kts").writeText("")
            assertTrue(ProjectDetector.isProjectRoot(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isProjectRoot - 有git目录`() {
        val dir = makeTempDir()
        try {
            File(dir, ".git").mkdir()
            assertTrue(ProjectDetector.isProjectRoot(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `isProjectRoot - 空目录`() {
        val dir = makeTempDir()
        try {
            assertFalse(ProjectDetector.isProjectRoot(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── findProjectRoot ───────────────────────────────────────────

    @Test
    fun `findProjectRoot - 在项目根目录`() {
        val dir = makeTempDir()
        try {
            File(dir, "settings.gradle.kts").writeText("")
            File(dir, "app").mkdir()
            File(dir, "app/build.gradle.kts").writeText("")
            assertEquals(dir, ProjectDetector.findProjectRoot(File(dir, "app")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findProjectRoot - 嵌套子目录向上查找`() {
        val dir = makeTempDir()
        try {
            File(dir, "settings.gradle.kts").writeText("")
            val nested = File(dir, "app/src/main/java/com/example")
            nested.mkdirs()
            assertEquals(dir, ProjectDetector.findProjectRoot(nested))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findProjectRoot - git作为回退`() {
        val dir = makeTempDir()
        try {
            File(dir, ".git").mkdir()
            val nested = File(dir, "src/main")
            nested.mkdirs()
            assertEquals(dir, ProjectDetector.findProjectRoot(nested))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findProjectRoot - 在workspace边界停止`() {
        val workspace = makeTempDir()
        try {
            // workspace 下有一个项目
            val projectDir = File(workspace, "myproject")
            projectDir.mkdirs()
            File(projectDir, "settings.gradle.kts").writeText("")
            // 直接在 workspace 下（没有项目标记），且父级是 workspace 名称时应停止
            val result = ProjectDetector.findProjectRoot(workspace)
            // workspace 本身没有项目标记，也没有 .git，应该返回 null
            assertNull(result)
        } finally {
            workspace.deleteRecursively()
        }
    }

    // ── findAndroidProjects ────────────────────────────────────────

    @Test
    fun `findAndroidProjects - 空workspace`() {
        val dir = makeTempDir()
        try {
            assertTrue(ProjectDetector.findAndroidProjects(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `findAndroidProjects - 单个Android项目`() {
        val workspace = makeTempDir()
        try {
            val project = File(workspace, "MyApp")
            project.mkdirs()
            File(project, "app").mkdir()
            File(project, "settings.gradle.kts").writeText("")
            File(project, "app/build.gradle.kts").writeText("")

            val result = ProjectDetector.findAndroidProjects(workspace)
            assertEquals(1, result.size)
            assertEquals(project, result[0])
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun `findAndroidProjects - 多个项目按名称排序`() {
        val workspace = makeTempDir()
        try {
            listOf("Zebra", "Alpha", "Middle").forEach { name ->
                val p = File(workspace, name)
                p.mkdirs()
                File(p, "app").mkdir()
                File(p, "settings.gradle.kts").writeText("")
                File(p, "app/build.gradle.kts").writeText("")
            }
            val result = ProjectDetector.findAndroidProjects(workspace)
            assertEquals(3, result.size)
            assertEquals("Alpha", result[0].name)
            assertEquals("Middle", result[1].name)
            assertEquals("Zebra", result[2].name)
        } finally {
            workspace.deleteRecursively()
        }
    }

    // ── getProjectMeta ────────────────────────────────────────────

    @Test
    fun `getProjectMeta - Android 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "app").mkdir()
            File(dir, "settings.gradle.kts").writeText("")
            File(dir, "app/build.gradle.kts").writeText("")
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals(dir, meta.dir)
            assertEquals(dir.name, meta.name)
            assertEquals("Android", meta.language)
            assertFalse(meta.hasApk)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `getProjectMeta - Node 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "package.json").writeText("{}")
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals("Node", meta.language)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `getProjectMeta - Rust 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "Cargo.toml").writeText("")
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals("Rust", meta.language)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `getProjectMeta - Python 项目`() {
        val dir = makeTempDir()
        try {
            File(dir, "requirements.txt").writeText("")
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals("Python", meta.language)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `getProjectMeta - 未知项目`() {
        val dir = makeTempDir()
        try {
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals("Unknown", meta.language)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `getProjectMeta - Kotlin Gradle 项目（非 Android）`() {
        val dir = makeTempDir()
        try {
            File(dir, "settings.gradle.kts").writeText("")
            // 没有 app/build.gradle.kts，所以不是 Android 项目
            val meta = ProjectDetector.getProjectMeta(dir)
            assertEquals("Kotlin", meta.language)
        } finally {
            dir.deleteRecursively()
        }
    }
}
