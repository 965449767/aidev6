package com.aidev.six.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-01: OpenCodeClient SSE 协议解析单元测试。
 *
 * `parseMessage` / `parseEvent` 是 private 方法，通过反射调用，覆盖：
 *  - 正常事件流（文本 / 工具 / 增量 delta / 权限 / 追问 / 完整消息替换）
 *  - 不完整 chunk（丢失字段、空 properties、data 兜底）
 *  - 错误字段（info.error.data.message）
 *  - 断线/异常场景的解析兜底（任何合法 JSONObject 都不抛、返回非 null 事件）
 */
class OpenCodeClientTest {

    private val client = OpenCodeClient("http://localhost:4096")

    private fun parseMessage(json: String): ChatMessage? {
        val m = OpenCodeClient::class.java.getDeclaredMethod("parseMessage", JSONObject::class.java)
        m.isAccessible = true
        return m.invoke(client, JSONObject(json)) as? ChatMessage
    }

    private fun parseEvent(json: String): OcEvent {
        val m = OpenCodeClient::class.java.getDeclaredMethod("parseEvent", JSONObject::class.java)
        m.isAccessible = true
        return m.invoke(client, JSONObject(json)) as OcEvent
    }

    // ---- parseMessage ----

    @Test
    fun parseMessage_normalTextAndTool() {
        val msg = parseMessage(
            """{
              "info": {"id":"m1","role":"assistant","time":{"completed":123}},
              "parts": [
                {"type":"text","text":"hello"},
                {"type":"tool","tool":"bash","state":{"status":"success","output":"out"}}
              ]
            }"""
        )
        assertNotNull(msg)
        assertEquals("m1", msg!!.id)
        assertEquals("assistant", msg.role)
        assertTrue(msg.completed)
        assertEquals(2, msg.parts.size)
        assertTrue(msg.parts[0] is ChatPart.Text)
        assertEquals("hello", (msg.parts[0] as ChatPart.Text).text)
        assertTrue(msg.parts[1] is ChatPart.Tool)
        assertEquals("bash", (msg.parts[1] as ChatPart.Tool).name)
        assertEquals("out", (msg.parts[1] as ChatPart.Tool).output)
    }

    @Test
    fun parseMessage_ignoredAndSyntheticTextFiltered() {
        val msg = parseMessage(
            """{
              "info": {"id":"m2","role":"assistant"},
              "parts": [
                {"type":"text","text":"hidden","ignored":true},
                {"type":"text","text":"alsohidden","synthetic":true},
                {"type":"text","text":"shown"}
              ]
            }"""
        )
        assertNotNull(msg)
        assertEquals(1, msg!!.parts.size)
        assertEquals("shown", (msg.parts[0] as ChatPart.Text).text)
    }

    @Test
    fun parseMessage_errorFieldParsed() {
        val msg = parseMessage(
            """{
              "info": {"id":"m3","role":"assistant","error":{"data":{"message":"boom"}}}
            }"""
        )
        assertNotNull(msg)
        assertEquals("boom", msg!!.error)
        assertTrue(msg.parts.isEmpty())
    }

    @Test
    fun parseMessage_nullInfoReturnsNull() {
        assertNull(parseMessage("""{"parts":[{"type":"text","text":"x"}]}"""))
    }

    @Test
    fun parseMessage_blankIdReturnsNull() {
        assertNull(parseMessage("""{"info":{"id":"","role":"assistant"}}"""))
    }

    // ---- parseEvent：增量 delta（正常 / 不完整 / 兜底） ----

    @Test
    fun parseEvent_deltaNormal() {
        val ev = parseEvent(
            """{"type":"message.part.delta","properties":{"sessionID":"s1","delta":"Hi","partID":"p1","messageID":"m1"}}"""
        )
        assertEquals("message.part.delta", ev.type)
        assertEquals("s1", ev.sessionID)
        assertEquals("Hi", ev.deltaText)
        assertEquals("p1", ev.deltaPartId)
        assertEquals("m1", ev.deltaMessageId)
    }

    @Test
    fun parseEvent_deltaFallsBackToDataObject() {
        val ev = parseEvent(
            """{"type":"message.part.delta","properties":{"data":{"delta":"x","partID":"pp","messageID":"mm"}}}"""
        )
        assertEquals("x", ev.deltaText)
        assertEquals("pp", ev.deltaPartId)
        assertEquals("mm", ev.deltaMessageId)
    }

    @Test
    fun parseEvent_incompleteMissingPropertiesDoesNotThrow() {
        val ev = parseEvent("""{"type":"ping"}""")
        assertEquals("ping", ev.type)
        assertNull(ev.sessionID)
        assertNull(ev.permission)
        assertNull(ev.question)
        assertNull(ev.deltaText)
    }

    @Test
    fun parseEvent_emptyJsonReturnsNonNullEvent() {
        val ev = parseEvent("{}")
        assertEquals("", ev.type)
        assertNotNull(ev)
    }

    // ---- parseEvent：权限（v1 / v2） ----

    @Test
    fun parseEvent_permissionV1() {
        val ev = parseEvent(
            """{"type":"permission.asked","properties":{"sessionID":"s1","permission":{"id":"perm_1","permission":"bash","patterns":["git *"]}}}"""
        )
        assertNotNull(ev.permission)
        with(ev.permission!!) {
            assertEquals("perm_1", id)
            assertEquals("bash", title)
            assertEquals("git *", pattern)
            assertEquals(listOf("git *"), patterns)
            assertFalse(v2)
        }
    }

    @Test
    fun parseEvent_permissionV2() {
        val ev = parseEvent(
            """{"type":"permission.v2.asked","properties":{"sessionID":"s1","permission":{"id":"per_1","action":"bash","resources":["/sdcard/*"]}}}"""
        )
        assertNotNull(ev.permission)
        with(ev.permission!!) {
            assertEquals("per_1", id)
            assertEquals("bash", title)
            assertEquals("/sdcard/*", pattern)
            assertEquals(listOf("/sdcard/*"), patterns)
            assertTrue(v2)
        }
    }

    // ---- parseEvent：追问（v1） ----

    @Test
    fun parseEvent_questionV1() {
        val ev = parseEvent(
            """{"type":"question.asked","properties":{"sessionID":"s1","question":{"id":"q1","question":"continue?","options":[{"label":"yes"},{"label":"no"}]}}}"""
        )
        assertNotNull(ev.question)
        with(ev.question!!) {
            assertEquals("q1", id)
            assertEquals("continue?", question)
            assertEquals(2, options.size)
            assertEquals("yes", options[0].label)
        }
    }

    // ---- parseEvent：完整消息替换（message.updated / message.part.updated） ----

    @Test
    fun parseEvent_updatedMessageParsed() {
        val ev = parseEvent(
            """{"type":"message.updated","properties":{"info":{"id":"m1","role":"assistant"},"parts":[{"type":"text","text":"final"}]}}"""
        )
        assertNotNull(ev.updatedMessage)
        assertEquals("m1", ev.updatedMessage!!.id)
        assertEquals(1, ev.updatedMessage!!.parts.size)
        assertEquals("final", (ev.updatedMessage!!.parts[0] as ChatPart.Text).text)
    }
}
