package com.aidev.six

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun importsAndCleansBuild() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, "build/output").mkdirs(); File(src, "build/output/o.txt").writeText("x")
        File(src, ".gradle").mkdirs()
        File(src, "src/main").mkdirs(); File(src, "src/main/A.kt").writeText("class A")
        val ws = tmp.newFolder("ws")
        val dest = ProjectImporter.importProject(src, ws, "Imported")
        assertTrue(dest.isDirectory)
        assertEquals("Imported", dest.name)
        assertTrue(File(dest, "src/main/A.kt").isFile)
        assertFalse(File(dest, "build").exists())
        assertFalse(File(dest, ".gradle").exists())
    }

    @Test
    fun renamesOnConflict() = runBlocking {
        val src = tmp.newFolder("src"); File(src, "a.txt").writeText("x")
        val ws = tmp.newFolder("ws"); File(ws, "Imported").mkdirs()
        val dest = ProjectImporter.importProject(src, ws, "Imported")
        assertEquals("Imported-2", dest.name)
    }

    @Test
    fun keepsGitByDefault() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, ".git").mkdirs(); File(src, ".git/config").writeText("x")
        File(src, "a.txt").writeText("x")
        val ws = tmp.newFolder("ws")
        val dest = ProjectImporter.importProject(src, ws, "P")
        assertTrue(File(dest, ".git/config").isFile)
    }
}
