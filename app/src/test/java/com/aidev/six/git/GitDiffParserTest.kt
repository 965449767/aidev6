package com.aidev.six.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun `parseNumstat handles normal and binary and rename`() {
        val input = """
            12	3	app/src/main/java/com/aidev/six/ui/Foo.kt
            0	0	app/src/main/res/drawable/icon.png
            -	-	app/src/main/assets/binary.bin
            5	1	old/path/Bar.kt => new/path/Bar.kt
        """.trimIndent()
        val diff = GitDiffParser.parseNumstat(input)
        assertEquals(4, diff.size)
        assertEquals("app/src/main/java/com/aidev/six/ui/Foo.kt", diff[0].path)
        assertEquals(12, diff[0].additions)
        assertEquals(3, diff[0].deletions)
        assertEquals(GitDiffParser.DiffCategory.COMPOSE, diff[0].category)
        assertEquals("app/src/main/res/drawable/icon.png", diff[1].path)
        assertEquals("app/src/main/assets/binary.bin", diff[2].path)
        assertEquals("new/path/Bar.kt", diff[3].path)
    }

    @Test
    fun `build gradle gets high risk`() {
        val input = "2\t1\tapp/build.gradle.kts\n10\t0\tapp/src/main/java/com/aidev/six/Ui.kt\n"
        val diff = GitDiffParser.parseNumstat(input)
        val gradle = diff.first { it.path.endsWith("build.gradle.kts") }
        assertTrue("build.gradle should be >=3 risk, was ${gradle.riskStars}", gradle.riskStars >= 3)
        val ui = diff.first { it.path.endsWith("Ui.kt") }
        assertTrue("small ui change should be low risk, was ${ui.riskStars}", ui.riskStars <= 2)
    }

    @Test
    fun `manifest permission bumps risk and adds note`() {
        val input = "3\t0\tapp/src/main/AndroidManifest.xml\n"
        val diff = GitDiffParser.parseNumstat(input)
        assertEquals(1, diff.size)
        assertTrue(diff[0].riskStars >= 4)
        assertTrue(diff[0].notes.any { it.contains("清单") })
    }

    @Test
    fun `empty diff produces empty summary`() {
        val summary = GitDiffParser.summarize(emptyList())
        assertEquals(0, summary.files)
        assertEquals(0, summary.maxRisk)
        assertTrue(GitDiffParser.toReviewPrompt(emptyList()).contains("无未提交改动"))
    }

    @Test
    fun `risk stars clamped to 1-5`() {
        val tiny = GitDiffParser.parseNumstat("1\t0\tdocs/README.md\n")
        assertEquals(1, tiny[0].riskStars)
        val huge = GitDiffParser.parseNumstat("900\t800\tapp/build.gradle\n")
        assertEquals(5, huge[0].riskStars)
    }
}
