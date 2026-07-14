package com.aidev.six.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeIndexerTest {

    @Test
    fun `indexes classes interfaces objects functions and tags`() {
        val dir = File.createTempFile("aidev-idx", "").apply { delete(); mkdirs() }
        try {
            File(dir, "Foo.kt").writeText(
                """
                package com.example
                /** A composable screen. */
                @Composable
                fun FooScreen(bar: Bar) { }
                class Bar { }
                interface Baz { }
                object Qux { }
                """.trimIndent()
            )

            val symbols = CodeIndexer.indexDirectory(dir)
            val names = symbols.map { it.name }

            assertTrue("FooScreen indexed", names.contains("FooScreen"))
            assertTrue("Bar indexed", names.contains("Bar"))
            assertTrue("Baz indexed", names.contains("Baz"))
            assertTrue("Qux indexed", names.contains("Qux"))

            assertEquals("function", symbols.first { it.name == "FooScreen" }.kind)
            assertEquals("class", symbols.first { it.name == "Bar" }.kind)
            assertEquals("interface", symbols.first { it.name == "Baz" }.kind)
            assertEquals("object", symbols.first { it.name == "Qux" }.kind)

            val foo = symbols.first { it.name == "FooScreen" }
            assertTrue("@Composable tag captured", foo.tags.contains("Composable"))
            assertTrue("dependency Bar captured", foo.deps.contains("Bar"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `skips build and gradle directories`() {
        val dir = File.createTempFile("aidev-idx", "").apply { delete(); mkdirs() }
        try {
            File(dir, "src/Main.kt").apply { parentFile.mkdirs() }.writeText("class Keep { }\n")
            File(dir, "build/Generated.kt").apply { parentFile.mkdirs() }.writeText("class Drop { }\n")
            File(dir, ".gradle/Cache.kt").apply { parentFile.mkdirs() }.writeText("class Drop2 { }\n")

            val symbols = CodeIndexer.indexDirectory(dir)
            val names = symbols.map { it.name }
            assertTrue(names.contains("Keep"))
            assertEquals("build/gradle excluded", 1, names.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `extracts manifest components`() {
        val dir = File.createTempFile("aidev-idx", "").apply { delete(); mkdirs() }
        try {
            File(dir, "app/src/main").mkdirs()
            File(dir, "app/src/main/AndroidManifest.xml").writeText(
                """<manifest><application>
                    <activity android:name=".MainActivity"/>
                    <service android:name=".MyService"/>
                </application></manifest>"""
            )
            val symbols = CodeIndexer.indexDirectory(dir)
            val components = symbols.filter { it.kind == "component" }.map { it.name }
            assertTrue(components.contains(".MainActivity"))
            assertTrue(components.contains(".MyService"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
