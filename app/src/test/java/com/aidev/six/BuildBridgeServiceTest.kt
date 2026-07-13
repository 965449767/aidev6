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

    private fun call(name: String, vararg args: Any?): Any? {
        val types = args.map { (it?.javaClass ?: Any::class.java) }.toTypedArray()
        val m = BuildBridgeService::class.java.getDeclaredMethod(name, *types)
        m.isAccessible = true
        return m.invoke(BuildBridgeService, *args)
    }

    @Test
    fun resolveProjectDir_workspacePrefix() {
        val ws = File("/data/home/workspace")
        val r = call("resolveProjectDir", ws, "/workspace/MyApp") as File
        assertEquals(File(ws, "MyApp").absolutePath, r.absolutePath)
    }

    @Test
    fun resolveProjectDir_absolutePath() {
        val ws = File("/data/home/workspace")
        val r = call("resolveProjectDir", ws, "/abs/some/App") as File
        assertEquals("/abs/some/App", r.absolutePath)
    }

    @Test
    fun resolveProjectDir_relativeName() {
        val ws = File("/data/home/workspace")
        val r = call("resolveProjectDir", ws, "MyApp") as File
        assertEquals(File(ws, "MyApp").absolutePath, r.absolutePath)
    }

    @Test
    fun derivePackage_normal() {
        assertEquals("com.aidev.app.myandroidproject", call("derivePackage", "MyAndroidProject") as String)
    }

    @Test
    fun derivePackage_leadingDigitPrefixed() {
        assertEquals("com.aidev.app.a123app", call("derivePackage", "123app") as String)
    }

    @Test
    fun derivePackage_emptyFallsBackToApp() {
        assertEquals("com.aidev.app.app", call("derivePackage", "") as String)
    }

    @Test
    fun finish_writesResultJsonWithArtifacts() {
        val field = BuildBridgeService::class.java.getDeclaredField("requestDir")
        field.isAccessible = true
        field.set(BuildBridgeService, tempDir)

        val reqFile = File(tempDir, "req-build-9.json").apply { writeText("{}") }
        val finishMethod = BuildBridgeService::class.java.getDeclaredMethod(
            "finish", Context::class.java, String::class.java, Boolean::class.java, String::class.java,
            StringBuilder::class.java, File::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java
        )
        finishMethod.isAccessible = true
        finishMethod.invoke(
            BuildBridgeService, mock(Context::class.java), "build-9", true, "构建成功: /x/app-debug.apk",
            StringBuilder("ok"), reqFile, "/x/app-debug.apk", "/x/build.log", "com.x.app", "MyApp"
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
        // 请求文件应被消费删除
        assertEquals(false, reqFile.exists())
    }
}
