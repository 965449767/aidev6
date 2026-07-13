package com.aidev.six.terminal

import com.aidev.six.AIDevLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * P6-03: SessionManager 会话生命周期与 PWD 跟踪单元测试。
 *
 * 通过反射把 `_sessions` 直接播种为带 mock TerminalSession 的 EmbeddedTermSession，
 * 避免 doCreateSession 真正 spawn /system/bin/sh 进程；仅验证纯逻辑：
 *   - switchSession 索引切换
 *   - closeSession 删除与会话索引重排（不破坏当前选中）
 *   - toggleLock 锁定翻转
 *   - cachedCompletionPwd 经 CompletionEngine 的读写
 */
class SessionManagerTest {

    private lateinit var sm: SessionManager
    private lateinit var completion: CompletionEngine

    @Before
    fun setUp() {
        AIDevLogger.enabled = false
        completion = mock(CompletionEngine::class.java)
        sm = SessionManager(completionEngine = completion)
    }

    private fun seed(vararg ids: Int) {
        val f = SessionManager::class.java.getDeclaredField("_sessions")
        f.isAccessible = true
        val list = f.get(sm) as MutableList<EmbeddedTermSession>
        list.clear()
        ids.forEach { id ->
            val ts = mock(com.termux.terminal.TerminalSession::class.java)
            list.add(EmbeddedTermSession(id, "会话$id", ts))
        }
    }

    private fun setCurrentIndex(i: Int) {
        val f = SessionManager::class.java.getDeclaredField("_currentIndex")
        f.isAccessible = true
        val state = f.get(sm)
        state.javaClass.getMethod("setIntValue", Int::class.java).invoke(state, i)
    }

    @Test
    fun switchSession_updatesCurrentIndex() {
        seed(1, 2, 3)
        setCurrentIndex(0)
        sm.switchSession(3)
        assertEquals(2, sm.currentIndex)
        assertEquals(3, sm.currentSession?.id)
    }

    @Test
    fun switchSession_unknownIdIgnored() {
        seed(1, 2)
        setCurrentIndex(1)
        sm.switchSession(99)
        assertEquals(1, sm.currentIndex)
    }

    @Test
    fun closeSession_removesAndKeepsCurrentValid() {
        seed(1, 2, 3)
        setCurrentIndex(2) // 当前是会话3
        sm.closeSession(1) // 删掉会话1 → 列表变 [2,3]，会话3 移到 index1
        assertEquals(2, sm.sessions.size)
        assertEquals(1, sm.currentIndex)
        assertEquals(3, sm.currentSession?.id)
    }

    @Test
    fun closeSession_currentIsClosed_fallsBackToLast() {
        seed(1, 2)
        setCurrentIndex(1) // 当前会话2
        sm.closeSession(2)
        assertEquals(1, sm.sessions.size)
        assertEquals(0, sm.currentIndex)
        assertEquals(1, sm.currentSession?.id)
    }

    @Test
    fun toggleLock_flipsLockedState() {
        seed(1, 2)
        assertFalse(sm.sessions[1].locked)
        sm.toggleLock(2)
        assertTrue(sm.sessions[1].locked)
        sm.toggleLock(2)
        assertFalse(sm.sessions[1].locked)
    }

    @Test
    fun cachedCompletionPwd_readsAndWritesThroughEngine() {
        whenever(completion.cachedCompletionPwd).thenReturn("/workspace/proj")
        assertEquals("/workspace/proj", sm.cachedCompletionPwd)

        sm.cachedCompletionPwd = "/workspace/other"
        verify(completion).cachedCompletionPwd = "/workspace/other"
    }

    @Test
    fun cachedCompletionPwd_nullEngineReturnsEmpty() {
        val sm2 = SessionManager() // 无 completionEngine
        assertNotNull(sm2)
        assertEquals("", sm2.cachedCompletionPwd)
    }
}
