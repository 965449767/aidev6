package com.aidev.four

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class SftpConnectionTest {

    @Test
    fun `default values are set correctly`() {
        val conn = SftpConnection()
        assertEquals("", conn.label)
        assertEquals("", conn.host)
        assertEquals(22, conn.port)
        assertEquals("root", conn.user)
        assertEquals(AuthType.PASSWORD, conn.authType)
        assertNotNull(conn.id)
        assertTrue(conn.id.length <= 8)
    }

    @Test
    fun `constructor parameters are stored correctly`() {
        val conn = SftpConnection(
            id = "test001",
            label = "My Server",
            host = "192.168.1.1",
            port = 2222,
            user = "admin",
            authType = AuthType.KEY,
        )
        assertEquals("test001", conn.id)
        assertEquals("My Server", conn.label)
        assertEquals("192.168.1.1", conn.host)
        assertEquals(2222, conn.port)
        assertEquals("admin", conn.user)
        assertEquals(AuthType.KEY, conn.authType)
    }

    @Test
    fun `copy creates a new instance with overridden fields`() {
        val conn = SftpConnection(label = "original", host = "10.0.0.1")
        val copied = conn.copy(host = "10.0.0.2")
        assertEquals("original", copied.label)
        assertEquals("10.0.0.2", copied.host)
        assertNotEquals(conn, copied)
    }

    @Test
    fun `equals is true for identical fields`() {
        val a = SftpConnection("id1", "srv", "host", 22, "u", AuthType.PASSWORD)
        val b = SftpConnection("id1", "srv", "host", 22, "u", AuthType.PASSWORD)
        assertEquals(a, b)
    }

    @Test
    fun `equals is false when any field differs`() {
        val base = SftpConnection("id", "n", "h", 22, "u", AuthType.PASSWORD)
        assertNotEquals(base, base.copy(id = "other"))
        assertNotEquals(base, base.copy(label = "other"))
        assertNotEquals(base, base.copy(host = "other"))
        assertNotEquals(base, base.copy(port = 99))
        assertNotEquals(base, base.copy(user = "other"))
        assertNotEquals(base, base.copy(authType = AuthType.KEY))
    }

    @Test
    fun `toString contains all fields`() {
        val conn = SftpConnection(id = "abc123", label = "test", host = "1.2.3.4")
        val str = conn.toString()
        assertTrue(str.contains("abc123"))
        assertTrue(str.contains("test"))
        assertTrue(str.contains("1.2.3.4"))
        assertTrue(str.contains("port"))
        assertTrue(str.contains("authType"))
    }

    @Test
    fun `component functions return fields in order`() {
        val conn = SftpConnection("a", "b", "c", 33, "d", AuthType.KEY)
        assertEquals("a", conn.component1())
        assertEquals("b", conn.component2())
        assertEquals("c", conn.component3())
        assertEquals(33, conn.component4())
        assertEquals("d", conn.component5())
        assertEquals(AuthType.KEY, conn.component6())
    }

    @Test
    fun `destructuring declaration works`() {
        val conn = SftpConnection("x", "y", "z", 44, "u", AuthType.PASSWORD)
        val (id, label, host, port, user, authType) = conn
        assertEquals("x", id)
        assertEquals("y", label)
        assertEquals("z", host)
        assertEquals(44, port)
        assertEquals("u", user)
        assertEquals(AuthType.PASSWORD, authType)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = SftpConnection("id", "l", "h", 22, "u", AuthType.PASSWORD)
        val b = SftpConnection("id", "l", "h", 22, "u", AuthType.PASSWORD)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
