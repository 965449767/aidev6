package com.aidev.six

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LongBridgeDispatchTest {

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
    }

    @After
    fun tearDown() {
        AIDevLogger.enabled = true
    }

    private fun newTempDir(prefix: String): File {
        val f = File.createTempFile(prefix, "")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun setRequestDir(service: Any, dir: File) {
        val field = service::class.java.getDeclaredField("requestDir")
        field.isAccessible = true
        field.set(service, dir)
    }

    @Test
    fun buildDispatchEnqueuesFileAndReturnsAccepted() {
        val dir = newTempDir("build-disp")
        try {
            setRequestDir(BuildBridgeService, dir)
            val resp = BuildBridgeService.dispatch(
                BridgeFrame("build", "b-1", """{"id":"b-1","project":"MyApp"}""")
            )
            assertNotNull(resp)
            assertEquals("b-1", resp?.id)
            assertEquals("accepted", resp?.payload)
            val req = File(dir, "req-b-1.json")
            assertTrue("请求文件应已落盘", req.isFile)
            assertEquals("""{"id":"b-1","project":"MyApp"}""", req.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deployDispatchEnqueuesFileAndReturnsAccepted() {
        val dir = newTempDir("deploy-disp")
        try {
            setRequestDir(DeployBridgeService, dir)
            val resp = DeployBridgeService.dispatch(
                BridgeFrame("deploy", "d-1", """{"id":"d-1","apk":"/x.apk","pkg":"com.x"}""")
            )
            assertNotNull(resp)
            assertEquals("accepted", resp?.payload)
            assertTrue(File(dir, "req-d-1.json").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashDispatchEnqueuesFileAndReturnsAccepted() {
        val dir = newTempDir("crash-disp")
        try {
            setRequestDir(CrashReportBridgeService, dir)
            val resp = CrashReportBridgeService.dispatch(
                BridgeFrame("crash", "c-1", """{"id":"c-1","package":"com.x","lines":500}""")
            )
            assertNotNull(resp)
            assertEquals("accepted", resp?.payload)
            assertTrue(File(dir, "req-c-1.json").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun bridgeNamesRegistered() {
        assertEquals("build", BuildBridgeService.bridgeName)
        assertEquals("deploy", DeployBridgeService.bridgeName)
        assertEquals("crash", CrashReportBridgeService.bridgeName)
    }
}
