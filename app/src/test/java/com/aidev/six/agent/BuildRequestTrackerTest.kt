package com.aidev.six.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildRequestTrackerTest {

    private fun tempStateFile(): File = File.createTempFile("agent-tasks", ".json").apply { deleteOnExit() }

    private fun invokePublish(tracker: BuildRequestTracker, report: File, stateFile: File): AgentTaskRecord {
        val m = tracker.javaClass.getDeclaredMethod(
            "publishCrashRecord",
            File::class.java,
            File::class.java,
            Function1::class.java
        ).apply { isAccessible = true }
        lateinit var captured: AgentTaskRecord
        m.invoke(tracker, report, stateFile, { r: AgentTaskRecord -> captured = r })
        return captured
    }

    @Test
    fun crashReportPublishesFailedRecordWithStack() {
        val report = File.createTempFile("crash-", ".json").apply {
            deleteOnExit()
            val stack = JSONArray().apply {
                put("java.lang.RuntimeException: boom")
                put("    at com.x.Main.onCreate(Main.kt:12)")
            }
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", 1700000000000L)
                put("fatal", "FATAL EXCEPTION")
                put("stack", stack)
            }.toString())
        }
        val tracker = BuildRequestTracker()
        val record = invokePublish(tracker, report, tempStateFile())

        assertEquals(AgentTaskStatus.FAILED, record.status)
        assertEquals(1, record.exitCode)
        assertTrue(record.log.contains("boom"))
        assertTrue(record.log.contains("RuntimeException"))
        assertTrue(tracker.latestCrash.contains("com.example.app"))
    }

    @Test
    fun emptyStackReportPublishesSuccessRecord() {
        val report = File.createTempFile("crash-", ".json").apply {
            deleteOnExit()
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", 1700000000000L)
                put("fatal", "")
                put("stack", JSONArray())
            }.toString())
        }
        val tracker = BuildRequestTracker()
        val record = invokePublish(tracker, report, tempStateFile())

        assertEquals(AgentTaskStatus.SUCCEEDED, record.status)
        assertEquals(0, record.exitCode)
        assertTrue(record.log.contains("未捕获到崩溃"))
        assertTrue(tracker.latestCrash.contains("✔"))
    }
}
