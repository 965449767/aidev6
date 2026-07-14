package com.aidev.six

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashGuardTest {

    @Test
    fun writeCrashRequest_writesValidReqJson() {
        val home = File(createTempDir("crashguard"), "home")
        val pkg = "com.aidev.six"

        val ok = CrashGuard.writeCrashRequest(home, pkg)
        assertTrue("写入应成功", ok)

        val bridgeDir = File(home, ".aidev-crash-bridge")
        val reqFiles = bridgeDir.listFiles { f -> f.name.startsWith("req-") && f.name.endsWith(".json") }
        assertNotNull(reqFiles)
        assertEquals(1, reqFiles!!.size)

        val json = JSONObject(reqFiles[0].readText())
        assertEquals(pkg, json.getString("package"))
        assertTrue(json.getInt("lines") > 0)
    }
}
