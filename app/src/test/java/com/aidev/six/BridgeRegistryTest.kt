package com.aidev.six

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeRegistryTest {

    class FakeBridge : BridgeService("Fake") {
        var dispatched: BridgeFrame? = null
        override val bridgeName = "fakeb"
        override fun poll() = false
        override fun dispatch(frame: BridgeFrame): BridgeFrame? {
            dispatched = frame
            return BridgeFrame("fakeb", frame.id, "ok")
        }
    }

    @Test
    fun routesToRegisteredBridge() {
        val b = FakeBridge()
        BridgeRegistry.register(b)
        val resp = BridgeRegistry.dispatch(BridgeFrame("fakeb", "1", "p"))
        BridgeRegistry.unregister(b)
        assertEquals("1", b.dispatched?.id)
        assertEquals("ok", resp?.payload)
    }

    @Test
    fun unknownBridgeReturnsNull() {
        assertNull(BridgeRegistry.dispatch(BridgeFrame("nope", "1", "p")))
    }

    @Test
    fun blankNameNotRegistered() {
        val b = FakeBridge()
        // 模拟未覆盖 bridgeName 的默认桥
        val def = object : BridgeService("Def") {
            override fun poll() = false
        }
        BridgeRegistry.register(def)
        assertNull(BridgeRegistry.dispatch(BridgeFrame("", "1", "p")))
        BridgeRegistry.unregister(def)
    }
}
