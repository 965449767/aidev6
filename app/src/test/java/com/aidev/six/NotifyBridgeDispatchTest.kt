package com.aidev.six

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class NotifyBridgeDispatchTest {

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
    }

    @After
    fun tearDown() {
        AIDevLogger.enabled = true
    }

    /**
     * 直接验证 Notify 桥的 Socket 分发入口：payload 承载原 JSON，dispatch 返回同 id 的响应帧。
     * （不走全局 BridgeRegistry，避免与其它并行单测共享单例产生竞态。）
     */
    @Test
    fun dispatchReturnsFrameWithSameId() {
        val resp = NotifyBridgeService.dispatch(
            BridgeFrame("notify", "n1", """{"title":"t","message":"m"}""")
        )
        assertNotNull(resp)
        assertEquals("n1", resp?.id)
        assertEquals("notify", resp?.bridge)
    }

    @Test
    fun invalidJsonDispatchDoesNotThrow() {
        val resp = NotifyBridgeService.dispatch(BridgeFrame("notify", "n2", "not-json"))
        assertNotNull(resp)
        assertEquals("n2", resp?.id)
    }
}
