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

    @Test
    fun safePipelineAllowed() {
        // aidev-dumpsys 发送 "dumpsys meminfo 2>/dev/null | head -40"：
        // 前缀为白名单动词 + 管道下游为安全读取器 head，应放行（不被安全策略拒绝）。
        // 注意 '>' 重定向仍属注入元字符会被拒，这里用不带重定向的等价命令验证管道放行。
        val resp = ShizukuBridgeService.dispatch(
            BridgeFrame("shizuku", "s3", "TYPE=exec\nCOMMAND=dumpsys meminfo | head -40")
        )
        assertNotNull(resp)
        assertEquals("s3", resp?.id)
        assertTrue(
            "安全管道 dumpsys | head 不应被安全策略拒绝",
            resp?.payload?.startsWith("ERROR: 命令被安全策略拒绝") != true
        )
    }

    @Test
    fun unsafePipelineStillRejected() {
        // 管道下游为非白名单程序（如 sh / rm）仍应被拒。
        val resp = ShizukuBridgeService.dispatch(
            BridgeFrame("shizuku", "s4", "TYPE=exec\nCOMMAND=dumpsys meminfo | sh")
        )
        assertNotNull(resp)
        assertTrue(resp?.payload?.startsWith("ERROR: 命令被安全策略拒绝") == true)
    }
}
