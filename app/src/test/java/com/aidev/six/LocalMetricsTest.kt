package com.aidev.six

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMetricsTest {

    @Test
    fun appendEvent_writesJsonlLine() {
        val home = File(createTempDir("metrics"), "home")
        LocalMetrics.appendEvent(home, "build", true, mapOf("project" to "MyAndroidProject", "durationMs" to 1234L))

        val file = File(home, ".aidev-metrics/events.jsonl")
        assertTrue("指标文件应存在", file.isFile)
        val lines = file.readLines()
        assertEquals(1, lines.size)

        val json = JSONObject(lines[0])
        assertEquals("build", json.getString("event"))
        assertEquals(true, json.getBoolean("ok"))
        assertEquals("MyAndroidProject", json.getString("project"))
        assertEquals(1234L, json.getLong("durationMs"))
    }

    @Test
    fun appendEvent_failSafeOnBadDetail() {
        val home = File(createTempDir("metrics2"), "home")
        // 含非基本类型 detail 也不应抛异常
        LocalMetrics.appendEvent(home, "x", false, mapOf("obj" to listOf(1, 2)))
        assertTrue(File(home, ".aidev-metrics/events.jsonl").isFile)
    }
}
