package com.aidev.six.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildRequestTrackerTest {

    private fun tempStateFile(): File = File.createTempFile("agent-tasks", ".json").apply { deleteOnExit() }

    private fun mockContextWithFilesDir(): Pair<android.content.Context, File> {
        val filesDir = File(System.getProperty("java.io.tmpdir"), "ctx-files-${System.nanoTime()}-${Math.random().hashCode()}").apply { deleteOnExit(); mkdirs() }
        val ctx = mock(android.content.Context::class.java)
        `when`(ctx.filesDir).thenReturn(filesDir)
        `when`(ctx.applicationContext).thenReturn(ctx)
        return ctx to filesDir
    }

    private fun invokePublish(tracker: BuildRequestTracker, report: File, stateFile: File): AgentTaskRecord {
        lateinit var captured: AgentTaskRecord
        tracker.publishCrashRecord(report, stateFile) { r -> captured = r }
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

    @Test
    fun crashReportAlsoWrittenToSharedWorkspaceLoopDir() {
        val (ctx, filesDir) = mockContextWithFilesDir()
        val report = File.createTempFile("crash-", ".json").apply {
            deleteOnExit()
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", 1700000000000L)
                put("fatal", "FATAL EXCEPTION")
                put("stack", JSONArray().apply { put("java.lang.RuntimeException: boom") })
            }.toString())
        }
        val tracker = BuildRequestTracker()
        // 注入 appContext 以便写入共享工作区
        tracker.javaClass.getDeclaredField("appContext").apply { isAccessible = true }
            .set(tracker, ctx)

        invokePublish(tracker, report, tempStateFile())

        val loopFile = File(filesDir, "home/workspace/.aidev-loop/crash-${report.nameWithoutExtension}.json")
        assertTrue(loopFile.exists(), "应在共享工作区写出崩溃回流文件")
        val payload = JSONObject(loopFile.readText())
        assertEquals("self-evolution/crash", payload.optString("type"))
        assertEquals(false, payload.optBoolean("fix_applied"))
        assertEquals("MyAndroidProject", payload.optString("project"))
    }

    @Test
    fun requestRebuildTriggersBuildRequestFile() {
        val (ctx, filesDir) = mockContextWithFilesDir()
        val tracker = BuildRequestTracker()
        val recorded = mutableListOf<AgentTaskRecord>()
        tracker.requestRebuild(ctx, "MyAndroidProject", tempStateFile()) { recorded.add(it) }

        val bridgeDir = File(filesDir, "home/.aidev-build-bridge")
        var req: File? = null
        repeat(50) {
            req = bridgeDir.listFiles()?.firstOrNull { it.name.startsWith("req-") && it.name.endsWith(".json") }
            if (req != null) return@repeat
            Thread.sleep(200)
        }
        assertTrue(req != null, "requestRebuild 应在 build-bridge 写出 req-<id>.json 触发下一轮构建")
        assertTrue(recorded.isNotEmpty(), "requestRebuild 应插入一条 RUNNING 任务记录")
    }

    @Test
    fun autonomousWatchTriggersRebuildOnUnfixedCrash() {
        val (ctx, filesDir) = mockContextWithFilesDir()
        val tracker = BuildRequestTracker()
        tracker.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(tracker, ctx)

        val mcpDir = File(filesDir, "home/.aidev-mcp").apply { mkdirs() }
        val crash = File(mcpDir, "crash-loop.json").apply {
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", System.currentTimeMillis())
                put("fatal", "FATAL")
                put("stack", org.json.JSONArray().apply { put("java.lang.RuntimeException: boom") })
                put("fix_applied", false)
            }.toString())
        }
        val bridgeDir = File(filesDir, "home/.aidev-build-bridge").apply { mkdirs() }
        bridgeDir.listFiles()?.forEach { it.delete() }

        invokeWatch(tracker, filesDir, crash, autonomous = true)
        val triggered = bridgeDir.listFiles()?.firstOrNull { it.name.startsWith("req-") }
        assertTrue(triggered != null, "自治模式：未修复崩溃应自动触发下一轮构建")
    }

    @Test
    fun autonomousWatchStopsWhenCrashFixed() {
        val (ctx, filesDir) = mockContextWithFilesDir()
        val tracker = BuildRequestTracker()
        tracker.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(tracker, ctx)

        val mcpDir = File(filesDir, "home/.aidev-mcp").apply { mkdirs() }
        val crash = File(mcpDir, "crash-fixed.json").apply {
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", System.currentTimeMillis())
                put("fatal", "")
                put("stack", org.json.JSONArray())
                put("fix_applied", true)
            }.toString())
        }
        val bridgeDir = File(filesDir, "home/.aidev-build-bridge").apply { mkdirs() }
        bridgeDir.listFiles()?.forEach { it.delete() }

        invokeWatch(tracker, filesDir, crash, autonomous = true)
        val triggered = bridgeDir.listFiles()?.firstOrNull { it.name.startsWith("req-") }
        assertTrue(triggered == null, "已修复（fix_applied=true）不应再触发构建，闭环收敛")
    }

    @Test
    fun manualWatchDoesNotAutoRebuild() {
        val (ctx, filesDir) = mockContextWithFilesDir()
        val tracker = BuildRequestTracker()
        tracker.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(tracker, ctx)

        val mcpDir = File(filesDir, "home/.aidev-mcp").apply { mkdirs() }
        val crash = File(mcpDir, "crash-manual.json").apply {
            writeText(JSONObject().apply {
                put("package", "com.example.app")
                put("time", System.currentTimeMillis())
                put("fatal", "FATAL")
                put("stack", org.json.JSONArray().apply { put("boom") })
                put("fix_applied", false)
            }.toString())
        }
        val bridgeDir = File(filesDir, "home/.aidev-build-bridge").apply { mkdirs() }
        bridgeDir.listFiles()?.forEach { it.delete() }

        invokeWatch(tracker, filesDir, crash, autonomous = false)
        val triggered = bridgeDir.listFiles()?.firstOrNull { it.name.startsWith("req-") }
        assertTrue(triggered == null, "手动模式：崩溃后不应自动触发下一轮构建（需用户/宇宙A 决策）")
    }

    private fun invokeWatch(tracker: BuildRequestTracker, filesDir: File, crashFile: File, autonomous: Boolean) {
        val home = File(filesDir, "home")
        tracker.watchCrashReport(home, crashFile.lastModified(), tempStateFile(), autonomous) {}
    }
}
