package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildDiagnosticsTest {

    @Test
    fun `unresolved reference extracts class name`() {
        val log = "e: file:///src/Main.kt:5:1 Unresolved reference: FooBar"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应包含类名", hint?.contains("FooBar") == true)
        assertTrue("应为中文", hint?.contains("未找到引用") == true)
    }

    @Test
    fun `type mismatch detected`() {
        val log = "e: file:///src/Main.kt:10:1 Type mismatch: inferred type is String but Int was expected"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示类型不匹配", hint?.contains("类型不匹配") == true)
    }

    @Test
    fun `could not resolve dependency detected`() {
        val log = "Could not resolve androidx.compose:compose-bom:2024.06.00"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示依赖解析失败", hint?.contains("无法解析依赖") == true)
    }

    @Test
    fun `missing class detected`() {
        val log = "Missing class com.google.gson.Gson"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示缺少类", hint?.contains("缺少类") == true)
        assertTrue("应包含类名", hint?.contains("com.google.gson.Gson") == true)
    }

    @Test
    fun `out of memory detected`() {
        val log = "java.lang.OutOfMemoryError: Java heap space"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示内存不足", hint?.contains("内存不足") == true)
    }

    @Test
    fun `could not find method detected`() {
        val log = "Could not find method implementation() for arguments [compose-bom]"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示找不到方法", hint?.contains("找不到方法") == true)
    }

    @Test
    fun `clean log returns null`() {
        val log = "BUILD SUCCESSFUL in 10s"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertNull("正常构建日志不应有诊断", hint)
    }

    @Test
    fun `empty log returns null`() {
        assertNull(BuildDiagnostics.diagnoseBuildError(""))
    }

    @Test
    fun `resource not found detected`() {
        val log = "error: resource color/calc_background (aka com.example.app:color/calc_background) not found."
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示资源缺失", hint?.contains("资源缺失") == true)
        assertTrue("应包含资源名", hint?.contains("calc_background") == true)
    }

    @Test
    fun `resource linking failed detected`() {
        val log = "Android resource linking failed\ncolor/calc_background not found\ncolor/calc_display_text not found"
        val hints = BuildDiagnostics.diagnoseBuildErrors(log)
        assertTrue("应返回多条建议", hints.size >= 2)
        assertTrue("应包含 calc_background", hints.any { it.contains("calc_background") })
        assertTrue("应包含 calc_display_text", hints.any { it.contains("calc_display_text") })
    }

    @Test
    fun `keystore not found detected`() {
        val log = "Keystore file '/path/to/debug.keystore' not found"
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示签名文件缺失", hint?.contains("签名文件缺失") == true)
    }

    @Test
    fun `sdk location not found detected`() {
        val log = "SDK location not found. Define location with sdk.dir in the local.properties file."
        val hint = BuildDiagnostics.diagnoseBuildError(log)
        assertTrue("应提示 SDK 路径问题", hint?.contains("SDK 路径") == true)
    }
}
