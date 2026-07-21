package com.aidev.six

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * P6-02: DeployBridgeService JSON 请求/响应与输入校验单元测试。
 *
 * 部署黑盒的纯逻辑部分（路径校验 shEscape/isValidApkPath/isValidPkg/toProotPath/
 * parseDeployJson/validateDeployScript/writeResult）不依赖 PRoot 执行，通过反射调用测试。
 */
class DeployBridgeServiceTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
        tempDir = newTempDir("deploy-bridge-test")
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
        val m = DeployBridgeService::class.java.getDeclaredMethod(name, *types)
        m.isAccessible = true
        return m.invoke(DeployBridgeService, *args)
    }

    @Test
    fun shEscape_quotesSingleQuotes() {
        val r = call("shEscape", "it's") as String
        assertEquals("it'\\''s", r)
    }

    @Test
    fun isValidApkPath_acceptsCleanApk() {
        assertTrue(call("isValidApkPath", "/workspace/app/app-debug.apk") as Boolean)
    }

    @Test
    fun isValidApkPath_rejectsNonApkAndShellMeta() {
        assertFalse(call("isValidApkPath", "/workspace/app/foo.txt") as Boolean)
        assertFalse(call("isValidApkPath", "/workspace/app/a;b.apk") as Boolean)
        assertFalse(call("isValidApkPath", "/workspace/app/`x`.apk") as Boolean)
        assertFalse(call("isValidApkPath", "") as Boolean)
    }

    @Test
    fun isValidPkg_acceptsValidPackage() {
        assertTrue(call("isValidPkg", "com.example.myapp") as Boolean)
        assertTrue(call("isValidPkg", "com.aidev.app.test123") as Boolean)
    }

    @Test
    fun isValidPkg_rejectsIllegalChars() {
        assertFalse(call("isValidPkg", "com.example.app;rm") as Boolean)
        assertFalse(call("isValidPkg", "com/example/app") as Boolean)
        assertFalse(call("isValidPkg", "") as Boolean)
    }

    @Test
    fun toProotPath_workspaceMappedToWorkspace() {
        val ws = File("/data/data/com.aidev.six/files/home/workspace")
        @Suppress("UNCHECKED_CAST")
        val pair = call("toProotPath", "/data/data/com.aidev.six/files/home/workspace/MyApp/app.apk", ws) as Pair<String, List<*>>
        assertEquals("/workspace/MyApp/app.apk", pair.first)
        assertTrue(pair.second.isEmpty())
    }

    @Test
    fun toProotPath_sdcardPassedThrough() {
        val ws = File("/data/data/com.aidev.six/files/home/workspace")
        @Suppress("UNCHECKED_CAST")
        val pair = call("toProotPath", "/sdcard/AIDev/app-debug.apk", ws) as Pair<String, List<*>>
        assertEquals("/sdcard/AIDev/app-debug.apk", pair.first)
        assertTrue(pair.second.isEmpty())
    }

    @Test
    fun toProotPath_externalPathGetsMntBind() {
        val ws = File("/data/data/com.aidev.six/files/home/workspace")
        @Suppress("UNCHECKED_CAST")
        val pair = call("toProotPath", "/data/local/tmp/app-debug.apk", ws) as Pair<String, List<*>>
        assertEquals("/mnt/apk/app-debug.apk", pair.first)
        assertEquals(1, pair.second.size)
    }

    @Test
    fun parseDeployJson_extractsFirstJsonLine() {
        val stdout = """
            some noise line
            {"installed":true,"launched":true,"activity":"com.x/.Main"}
            trailing garbage
        """.trimIndent()
        val json = call("parseDeployJson", stdout) as org.json.JSONObject?
        assertNotNull(json)
        assertEquals(true, json!!.optBoolean("installed"))
        assertEquals("com.x/.Main", json.optString("activity"))
    }

    @Test
    fun parseDeployJson_returnsNullWhenNoJson() {
        assertNull(call("parseDeployJson", "no json here\njust text"))
    }

    @Test
    fun validateDeployScript_missingScriptReturnsError() {
        val home = File(tempDir, "home").apply { mkdirs() }
        val err = call("validateDeployScript", home) as String?
        assertNotNull(err)
        assertTrue(err!!.contains("缺失"))
    }

    @Test
    fun validateDeployScript_md5MismatchReturnsError() {
        val home = File(tempDir, "home").apply { mkdirs() }
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        File(bin, "aidev-autoinstall").writeText("old-script-content")
        File(bin, "aidev-autoinstall.md5").writeText("deadbeef")
        val err = call("validateDeployScript", home) as String?
        assertNotNull(err)
        assertTrue(err!!.contains("MD5"))
    }

    @Test
    fun validateDeployScript_validReturnsNull() {
        val home = File(tempDir, "home").apply { mkdirs() }
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        val script = File(bin, "aidev-autoinstall").apply { writeText("script-body") }
        val md5 = md5Of(script)
        File(bin, "aidev-autoinstall.md5").writeText(md5)
        val err = call("validateDeployScript", home) as String?
        assertNull(err)
    }

    @Test
    fun writeResult_writesWellFormedJson() {
        // 直接设置 requestDir 字段，绕过 onStart 的 assets 落地副作用
        val field = DeployBridgeService::class.java.getDeclaredField("requestDir")
        field.isAccessible = true
        field.set(DeployBridgeService, tempDir)

        val ctx = mock(Context::class.java)
        val writeMethod = DeployBridgeService::class.java.getDeclaredMethod(
            "writeResult", Context::class.java, String::class.java, Boolean::class.java,
            String::class.java, String::class.java, String::class.java, Boolean::class.java,
            Boolean::class.java, String::class.java, String::class.java
        )
        writeMethod.isAccessible = true
        writeMethod.invoke(
            DeployBridgeService, ctx, "req-1", true, "部署成功",
            "/workspace/app/app-debug.apk", "com.example.app", true, true, "com.example.app/.Main", null
        )

        val result = File(tempDir, "result-req-1.json")
        assertTrue(result.isFile)
        val json = org.json.JSONObject(result.readText())
        assertEquals("req-1", json.getString("id"))
        assertEquals(true, json.getBoolean("success"))
        assertEquals("com.example.app", json.getString("pkg"))
        assertEquals(true, json.getBoolean("installed"))
        assertEquals(true, json.getBoolean("launched"))
        assertEquals("com.example.app/.Main", json.getString("activity"))
        assertEquals(false, json.has("error") && !json.isNull("error"))
    }

    private fun md5Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
