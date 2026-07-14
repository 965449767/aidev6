package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildPreflightMemoryTest {

    @Test
    fun warnsWhenMemoryLow() {
        val r = BuildPreflight.checkPreconditions(File("/tmp/nonexistent-project-xyz"), memAvailableMb = 1024)
        assertTrue(r.warnings.any { it.contains("可用内存不足") })
    }

    @Test
    fun noMemoryWarningWhenMemoryOk() {
        val r = BuildPreflight.checkPreconditions(File("/tmp/nonexistent-project-xyz"), memAvailableMb = 8192)
        assertFalse(r.warnings.any { it.contains("可用内存不足") })
    }

    @Test
    fun defaultNoMemoryWarning() {
        // 不传内存时不应产生内存相关告警（保持旧行为）
        val r = BuildPreflight.checkPreconditions(File("/tmp/nonexistent-project-xyz"))
        assertFalse(r.warnings.any { it.contains("可用内存不足") })
    }

    @Test
    fun thresholdConstantIsThreeGb() {
        assertEquals(3072L, BuildPreflight.MEM_WATCHDOG_THRESHOLD_MB)
    }
}
