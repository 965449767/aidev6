package com.aidev.six

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildPreflightSourceTest {

    @Test
    fun `detects missing import class`() {
        val tmpDir = Files.createTempDirectory("source-check").toFile()
        val srcDir = File(tmpDir, "app/src/main/java/com/example/app").apply { mkdirs() }
        File(srcDir, "MainActivity.kt").writeText(
            """
            package com.example.app
            import com.example.app MissingClass
            class MainActivity
            """.trimIndent()
        )
        val messages = BuildPreflight.inspectSourceCode(tmpDir)
        assertTrue("应检测到缺失的 import", messages.any { it.contains("MissingClass") })
        tmpDir.deleteRecursively()
    }

    @Test
    fun `valid imports produce no warnings`() {
        val tmpDir = Files.createTempDirectory("source-check-ok").toFile()
        val srcDir = File(tmpDir, "app/src/main/java/com/example/app").apply { mkdirs() }
        File(srcDir, "MainActivity.kt").writeText(
            """
            package com.example.app
            import android.os.Bundle
            class MainActivity
            """.trimIndent()
        )
        val messages = BuildPreflight.inspectSourceCode(tmpDir)
        assertTrue("合法 import 不应告警: $messages", messages.isEmpty())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `manifest declares missing activity`() {
        val tmpDir = Files.createTempDirectory("manifest-check").toFile()
        val srcDir = File(tmpDir, "app/src/main").apply { mkdirs() }
        File(srcDir, "AndroidManifest.xml").writeText(
            """
            <manifest package="com.example.app">
                <application>
                    <activity android:name=".MissingActivity" />
                </application>
            </manifest>
            """.trimIndent()
        )
        val messages = BuildPreflight.inspectSourceCode(tmpDir)
        assertTrue("应检测到 Manifest 中缺失的 Activity", messages.any { it.contains("MissingActivity") })
        tmpDir.deleteRecursively()
    }

    @Test
    fun `manifest declares existing activity`() {
        val tmpDir = Files.createTempDirectory("manifest-ok").toFile()
        val srcDir = File(tmpDir, "app/src/main").apply { mkdirs() }
        File(srcDir, "AndroidManifest.xml").writeText(
            """
            <manifest package="com.example.app">
                <application>
                    <activity android:name=".MainActivity" />
                </application>
            </manifest>
            """.trimIndent()
        )
        val javaDir = File(tmpDir, "app/src/main/java/com/example/app").apply { mkdirs() }
        File(javaDir, "MainActivity.kt").writeText("package com.example.app\nclass MainActivity")
        val messages = BuildPreflight.inspectSourceCode(tmpDir)
        assertTrue("存在的 Activity 不应告警: $messages", messages.none { it.contains("MainActivity") })
        tmpDir.deleteRecursively()
    }
}
