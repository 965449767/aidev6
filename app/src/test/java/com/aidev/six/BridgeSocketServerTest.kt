package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class BridgeSocketServerTest {

    @Test
    fun startAcceptDispatchStop() {
        val transport = LoopbackTcpTransport(0)
        val server = BridgeSocketServer(transport)
        val got = AtomicReference<BridgeFrame>()
        server.start { frame ->
            got.set(frame)
            BridgeFrame("echo", frame.id, frame.payload.reversed())
        }

        val client = LoopbackTcpClient(port = transport.localPort)
        val resp = client.request(BridgeFrame("notify", "r1", "abc"))
        client.close()
        server.stop()

        assertEquals("r1", got.get().id)
        assertEquals("abc", got.get().payload)
        assertEquals("cba", resp?.payload)
        assertFalse(server.isRunning)
    }

    @Test
    fun handlerReturnsNullNoResponse() {
        val transport = LoopbackTcpTransport(0)
        val server = BridgeSocketServer(transport)
        server.start { null }

        val client = LoopbackTcpClient(port = transport.localPort)
        val resp = client.request(BridgeFrame("x", "y", "z"))
        client.close()
        server.stop()

        assertNull(resp)
    }

    @Test
    fun doubleStartIsNoOp() {
        val transport = LoopbackTcpTransport(0)
        val server = BridgeSocketServer(transport)
        server.start { null }
        server.start { null } // 第二次应被忽略，不抛异常
        assertTrue(server.isRunning)
        server.stop()
    }
}
