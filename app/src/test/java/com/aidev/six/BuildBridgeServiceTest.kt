package com.aidev.six

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * P6-02: BuildBridgeService 纯逻辑单元测试（路径解析 / 包名派生 / 结果 JSON 出口）。
 * 编译执行部分（Proot/Gradle）不在本测试范围，这里只验证请求→响应的结构化出口与输入归一化。
 */
class BuildBridgeServiceTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
        tempDir = newTempDir("build-bridge-test")
    }

    private fun newTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun resolveProjectDir_workspacePrefix() {
        val ws = File("/data/home/workspace")
        val r = BuildBridgeService.resolveProjectDir(ws, "/workspace/MyApp")
        assertEquals(File(ws, "MyApp").absolutePath, r.absolutePath)
    }

    @Test
    fun resolveProjectDir_absolutePath() {
        val ws = File("/data/home/workspace")
        val r = BuildBridgeService.resolveProjectDir(ws, "/abs/some/App")
        assertEquals("/abs/some/App", r.absolutePath)
    }

    @Test
    fun resolveProjectDir_relativeName() {
        val ws = File("/data/home/workspace")
        val r = BuildBridgeService.resolveProjectDir(ws, "MyApp")
        assertEquals(File(ws, "MyApp").absolutePath, r.absolutePath)
    }

    @Test
    fun derivePackage_normal() {
        assertEquals("com.aidev.app.myandroidproject", BuildProjectScaffolder.derivePackage("MyAndroidProject"))
    }

    @Test
    fun derivePackage_leadingDigitPrefixed() {
        assertEquals("com.aidev.app.a123app", BuildProjectScaffolder.derivePackage("123app"))
    }

    @Test
    fun derivePackage_emptyFallsBackToApp() {
        assertEquals("com.aidev.app.app", BuildProjectScaffolder.derivePackage(""))
    }

    @Test
    fun finish_writesResultJsonWithArtifacts() {
        val reqFile = File(tempDir, "req-build-9.json").apply { writeText("{}") }

        buildFinish(
            ctx = mock(Context::class.java),
            id = "build-9",
            success = true,
            message = "构建成功: /x/app-debug.apk",
            log = StringBuffer("ok"),
            reqFile = reqFile,
            requestDir = tempDir,
            apkPath = "/x/app-debug.apk",
            logPath = "/x/build.log",
            pkg = "com.x.app",
            project = "MyApp"
        )

        val result = File(tempDir, "result-build-9.json")
        assertTrue(result.isFile)
        val json = org.json.JSONObject(result.readText())
        assertEquals("build-9", json.getString("id"))
        assertEquals(true, json.getBoolean("success"))
        assertEquals("/x/app-debug.apk", json.getString("apk_path"))
        assertEquals("/x/build.log", json.getString("log_path"))
        assertEquals("com.x.app", json.getString("pkg"))
        assertEquals("MyApp", json.getString("project"))
        assertEquals(false, reqFile.exists())
    }
}
