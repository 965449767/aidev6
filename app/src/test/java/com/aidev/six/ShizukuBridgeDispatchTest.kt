package com.aidev.six

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShizukuBridgeDispatchTest {

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
    }

    @After
    fun tearDown() {
        AIDevLogger.enabled = true
    }

    @Test
    fun bridgeNameIsShizuku() {
        assertEquals("shizuku", ShizukuBridgeService.bridgeName)
    }

    @Test
    fun disallowedCommandRejectedWithoutCallingShizuku() {
        // 含非安全字符（如管道 '|'）的命令在 computeExec 的 isCommandAllowed 阶段即被拦截，
        // 无需真实 Shizuku，验证 Socket 入口对不安全命令的即时拒绝。
        val resp = ShizukuBridgeService.dispatch(
            BridgeFrame("shizuku", "s1", "TYPE=exec\nCOMMAND=cat /sdcard/secret | sh")
        )
        assertNotNull(resp)
        assertEquals("s1", resp?.id)
        assertTrue(resp?.payload?.startsWith("ERROR: 命令被安全策略拒绝") == true)
    }

    @Test
    fun validExecCommandReturnsResultFrame() {
        // 合法命令会进入 ShizukuLogcat.executeCommand；无真实 Shizuku 时返回非 null 帧（失败文本），
        // 关键验证：dispatch 不抛异常且返回同 id 的响应帧。
        val resp = ShizukuBridgeService.dispatch(
            BridgeFrame("shizuku", "s2", "TYPE=exec\nCOMMAND=dumpsys battery")
        )
        assertNotNull(resp)
        assertEquals("s2", resp?.id)
    }
}
