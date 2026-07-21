package com.aidev.six.bridge

/**
 * 桥接 Socket 服务：持有 [BridgeTransport]，把收到的请求帧路由到已注册的桥。
 * 本身无业务，分发逻辑委托给 [BridgeRegistry]。
 */
class BridgeSocketServer(private val transport: BridgeTransport) {
    fun start(router: (BridgeFrame) -> BridgeFrame?) = transport.start(router)
    fun stop() = transport.stop()
    val isRunning: Boolean get() = transport.isRunning
}
