package com.aidev.six

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShizukuBridgeServiceTest {

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
        ShizukuBridgeService.stop()
    }

    @After
    fun tearDown() {
        ShizukuBridgeService.stop()
    }

    @Test
    fun testStartStopWithInvalidDir() {
        val tempDir = java.io.File.createTempFile("test", "bridge")
        tempDir.delete()
        ShizukuBridgeService.start(null, tempDir)
        assertTrue(ShizukuBridgeService.isRunning)
        ShizukuBridgeService.stop()
        assertFalse(ShizukuBridgeService.isRunning)
    }
}
