package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPreflightTest {

    private val goodAppGradle = """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
        }

        android {
            namespace = "com.aidev.app.todo"
            compileSdk = 36
            defaultConfig {
                applicationId = "com.aidev.app.todo"
                minSdk = 26
                targetSdk = 36
            }
        }

        dependencies {
            implementation("androidx.core:core-ktx:1.12.0")
        }
    """.trimIndent()

    @Test
    fun `合规项目不产生告警也不改动`() {
        val r = BuildPreflight.inspect(goodAppGradle)
        assertTrue("合规项目不应有告警: ${r.messages}", r.messages.isEmpty())
        assertEquals(goodAppGradle, r.fixedText)
    }

    @Test
    fun `移除模块级 repositories 块`() {
        val bad = """
            plugins {
                id("com.android.application")
            }

            repositories {
                google()
                mavenCentral()
            }

            android {
                namespace = "com.x.y"
                compileSdk = 36
            }
        """.trimIndent()
        val r = BuildPreflight.inspect(bad)
        assertFalse("应已移除 repositories 块", r.fixedText.contains("repositories"))
        assertTrue("应仍保留 android 块", r.fixedText.contains("android {"))
        assertTrue("应仍保留 plugins 块", r.fixedText.contains("plugins {"))
        assertTrue("应有移除告警", r.messages.any { it.contains("repositories") })
    }

    @Test
    fun `不误删嵌套在其它块内的同名标识`() {
        // 顶层无 repositories 块；buildscript 内的不该被当作模块级（此处验证顶层扫描不误伤深层）
        val nested = """
            android {
                compileSdk = 36
                testOptions {
                    // repositories 只是注释里的词，且不在顶层
                }
            }
        """.trimIndent()
        val r = BuildPreflight.inspect(nested)
        assertEquals("嵌套/注释不应被改动", nested, r.fixedText)
    }

    @Test
    fun `compileSdk 非 36 给出告警`() {
        val r = BuildPreflight.inspect(goodAppGradle.replace("compileSdk = 36", "compileSdk = 34"))
        assertTrue("应提示 compileSdk 不符", r.messages.any { it.contains("compileSdk") })
    }

    @Test
    fun `Compose 配置不完整给出告警`() {
        val compose = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                compileSdk = 36
            }
            dependencies {
                implementation("androidx.compose.ui:ui")
            }
        """.trimIndent()
        val r = BuildPreflight.inspect(compose, rootGradle = "")
        assertTrue("应提示 Compose 配置不完整", r.messages.any { it.contains("Compose") })
    }

    @Test
    fun `Compose 配置完整不告警`() {
        val root = """plugins { id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false }"""
        val compose = """
            android {
                compileSdk = 36
                buildFeatures {
                    compose = true
                }
            }
            dependencies {
                implementation(platform("androidx.compose:compose-bom:2024.06.00"))
                implementation("androidx.compose.ui:ui")
            }
        """.trimIndent()
        val r = BuildPreflight.inspect(compose, rootGradle = root)
        assertTrue("完整 Compose 配置不应告警: ${r.messages}", r.messages.none { it.contains("Compose") })
    }

    @Test
    fun `stripTopLevelBlock 只移除顶层块`() {
        val src = "a {\n  repositories { x }\n}\nrepositories { y }\n"
        val out = BuildPreflight.stripTopLevelBlock(src, "repositories")
        assertTrue("顶层 repositories 应被移除", !out.contains("y"))
        assertTrue("嵌套 repositories 应保留", out.contains("x"))
    }
}
