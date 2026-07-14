package com.aidev.six

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun excludesBuildAndBinary() = runBlocking {
        val root = tmp.newFolder("proj")
        File(root, "build").mkdirs(); File(root, "build/output.txt").writeText("x")
        File(root, ".gradle").mkdirs()
        File(root, "src/main/java").mkdirs()
        File(root, "src/main/java/Main.kt").writeText("fun main(){}")
        File(root, "icon.png").writeText("binary")
        val out = File(tmp.root, "out.md")
        ProjectExporter.exportToFile(root, out, ProjectExporter.Options())
        val text = out.readText()
        assertTrue(text.contains("Main.kt"))
        assertFalse(text.contains("build/output.txt"))
        assertFalse(text.contains("icon.png"))
    }

    @Test
    fun gitIncludedWhenRequested() = runBlocking {
        val root = tmp.newFolder("proj")
        File(root, ".git").mkdirs(); File(root, ".git/config").writeText("[core]")
        File(root, "a.txt").writeText("hi")
        val out = File(tmp.root, "out.md")
        ProjectExporter.exportToFile(root, out, ProjectExporter.Options(includeGit = true))
        assertTrue(out.readText().contains(".git/config"))
        val out2 = File(tmp.root, "out2.md")
        ProjectExporter.exportToFile(root, out2, ProjectExporter.Options())
        assertFalse(out2.readText().contains(".git/config"))
    }

    @Test
    fun plainTextMode() = runBlocking {
        val root = tmp.newFolder("proj")
        File(root, "a.kt").writeText("x")
        val out = File(tmp.root, "out.txt")
        ProjectExporter.exportToFile(root, out, ProjectExporter.Options(plainText = true))
        val t = out.readText()
        assertTrue(t.contains("PROJECT SOURCE"))
        assertFalse(t.contains("```kotlin"))
    }

    @Test
    fun missingProjectThrows() = runBlocking {
        val out = File(tmp.root, "out.md")
        var threw = false
        try {
            ProjectExporter.exportToFile(File(tmp.root, "nope"), out)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
