package com.aidev.six

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 桥接传输抽象。把「监听 + 收发帧」与具体实现解耦，使核心路由/分发逻辑可在纯 JVM 单测中覆盖。
 *
 * [handler] 收到请求帧后返回响应帧（可为 null 表示无即时响应，结果经文件通道异步回传）。
 */
interface BridgeTransport : Closeable {
    fun start(handler: (BridgeFrame) -> BridgeFrame?)
    fun stop()
    val isRunning: Boolean
}

/**
 * TCP loopback 传输（生产 + 单测共用）：绑定 127.0.0.1，仅本机 IPC。
 *
 * 选用 TCP loopback 而非 Unix Domain Socket 的原因：PRoot 侧 bash 客户端可用 `nc` /
 * bash `/dev/tcp` 零依赖连接，无需 socat/python3/UDS 工具；仍属本机局部通信。
 * `port = 0` 时分配临时端口，供单测使用；生产用固定端口（见 [Constants.BRIDGE_SOCKET_PORT]）。
 */
class TcpBridgeTransport(
    private val host: String = "127.0.0.1",
    private val port: Int = 0
) : BridgeTransport {
    private val server = ServerSocket(port, 0, InetAddress.getByName(host))
    val localPort: Int = server.localPort
    @Volatile private var thread: Thread? = null
    @Volatile override var isRunning: Boolean = false

    override fun start(handler: (BridgeFrame) -> BridgeFrame?) {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            while (isRunning) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                Thread { handle(sock, handler) }.start()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handle(sock: Socket, handler: (BridgeFrame) -> BridgeFrame?) {
        runCatching {
            sock.getInputStream().use { ins ->
                sock.getOutputStream().use { outs ->
                    val req = BridgeFrame.readFrom(ins) ?: return@runCatching
                    runCatching { handler(req) }.getOrNull()?.let { resp ->
                        runCatching { resp.writeTo(outs) }
                    }
                }
            }
        }.also { runCatching { sock.close() } }
    }

    override fun stop() {
        isRunning = false
        runCatching { server.close() }
        thread?.interrupt()
        thread = null
    }

    override fun close() = stop()
}

/**
 * 测试 / 脚本客户端：连 TCP，发一帧并读回响应帧。
 */
class TcpBridgeClient(host: String = "127.0.0.1", port: Int) {
    private val socket = Socket(host, port)
    private val out = socket.getOutputStream()
    private val inp = socket.getInputStream()

    fun request(frame: BridgeFrame): BridgeFrame? {
        frame.writeTo(out)
        return BridgeFrame.readFrom(inp)
    }

    fun close() = runCatching { socket.close() }
}
