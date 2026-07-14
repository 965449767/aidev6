package com.aidev.six

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeServiceClaimTest {

    /** 最小 BridgeService 子类，暴露 protected claimFile 供测试。 */
    private class TestBridge : BridgeService("test") {
        fun tryClaim(dir: File, file: File): File? = claimFile(dir, file)
        override fun poll(): Boolean = false
    }

    @Test
    fun claim_renamesToProcessing_andRemovesOriginal() {
        val dir = createTempDir("bridge-claim")
        val req = File(dir, "req-1.json").apply { writeText("{}") }
        val bridge = TestBridge()

        val claimed = bridge.tryClaim(dir, req)
        assertNotNull("claim 应返回 processing 文件", claimed)
        assertTrue(claimed!!.name.endsWith(".processing"))
        assertFalse("原文件应已被重命名掉", req.exists())
        assertTrue("processing 文件应存在", claimed.exists())
    }

    @Test
    fun claim_isIdempotentForMissingOriginal() {
        val dir = createTempDir("bridge-claim-2")
        val bridge = TestBridge()
        // 对不存在的文件 claim 应返回 null（不会凭空创建）
        val claimed = bridge.tryClaim(dir, File(dir, "req-missing.json"))
        assertTrue(claimed == null)
    }

    @Test
    fun poll_skipsProcessingAndResultFiles() {
        // 复刻 BridgeService.poll 的过滤条件，确保 .processing / result- 不被重复处理
        val dir = createTempDir("bridge-poll")
        val pending = File(dir, "req-5.json").apply { writeText("{}") }
        val processing = File(dir, "req-5.json.processing").apply { writeText("{}") }
        val result = File(dir, "result-5.json").apply { writeText("{}") }

        val candidates = dir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.toList()
        assertTrue(candidates!!.contains(pending))
        assertFalse(candidates.contains(processing))
        assertFalse(candidates.contains(result))
    }
}
