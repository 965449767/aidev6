package com.aidev.six

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BridgeFrameTest {

    @Test
    fun roundTrip() {
        val f = BridgeFrame("notify", "id1", "hello")
        val bytes = ByteArrayOutputStream().apply { f.writeTo(this) }.toByteArray()
        val back = BridgeFrame.readFrom(ByteArrayInputStream(bytes))!!
        assertEquals("notify", back.bridge)
        assertEquals("id1", back.id)
        assertEquals("hello", back.payload)
    }

    @Test
    fun missingFieldsDefaultEmpty() {
        val json = JSONObject().put("b", "x").toString()
        val f = BridgeFrame.parse(json)!!
        assertEquals("x", f.bridge)
        assertEquals("", f.id)
        assertEquals("", f.payload)
    }

    @Test
    fun truncatedLengthReturnsNull() {
        // 仅 3 字节，读不满 4 字节长度头
        assertNull(BridgeFrame.readFrom(ByteArrayInputStream(byteArrayOf(0, 0, 0))))
    }

    @Test
    fun badLengthReturnsNull() {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0); out.write(100) // 声明 100 字节
        out.write("ab".toByteArray()) // 实际只有 2 字节
        assertNull(BridgeFrame.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun emptyStreamReturnsNull() {
        assertNull(BridgeFrame.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun unicodePayloadPreserved() {
        val payload = "中文 payload \uD83D\uDE00 \n\t"
        val f = BridgeFrame("shizuku", "u1", payload)
        val bytes = ByteArrayOutputStream().apply { f.writeTo(this) }.toByteArray()
        val back = BridgeFrame.readFrom(ByteArrayInputStream(bytes))!!
        assertEquals(payload, back.payload)
    }
}
