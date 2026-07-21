package com.aidev.six.bridge

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

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
 *
 * 弹性与安全性：
 *  - 有界固定线程池 + [Semaphore] 限连，避免慢客户端/空连接耗尽线程（本地 slow-loris）。
 *  - 每连接 [socketTimeoutMs] 的 `soTimeout`，空连接最多占用一个线程该时长即被回收。
 *  - [authToken] 非空时校验请求帧 [BridgeFrame.auth]，不符立即丢弃（仅本机源认证）。
 */
class TcpBridgeTransport(
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val authToken: String? = null,
    private val maxConnections: Int = 16,
    private val socketTimeoutMs: Int = 30_000
) : BridgeTransport {
    private val server = ServerSocket().apply {
        // SO_REUSEADDR：stop→quick restart 时避免 BindException（地址仍被旧 socket 占用）。
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(host), port))
    }
    val localPort: Int = server.localPort
    @Volatile private var thread: Thread? = null
    @Volatile override var isRunning: Boolean = false
    private val executor = Executors.newFixedThreadPool(maxConnections)
    private val semaphore = Semaphore(maxConnections)

    override fun start(handler: (BridgeFrame) -> BridgeFrame?) {
        if (isRunning) return
        isRunning = true
        thread = Thread {
            while (isRunning) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                if (!semaphore.tryAcquire()) {
                    runCatching { sock.close() } // 超出并发上限，直接拒绝
                    continue
                }
                executor.execute {
                    try {
                        handle(sock, handler)
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handle(sock: Socket, handler: (BridgeFrame) -> BridgeFrame?) {
        runCatching {
            sock.soTimeout = socketTimeoutMs
            sock.getInputStream().use { ins ->
                sock.getOutputStream().use { outs ->
                    val req = BridgeFrame.readFrom(ins) ?: return@runCatching
                    if (authToken != null && req.auth != authToken) {
                        AIDevLogger.w("TcpBridgeTransport", "bridge 鉴权失败，丢弃连接")
                        return@runCatching
                    }
                    runCatching { handler(req) }.getOrNull()?.let { resp ->
                        runCatching { resp.writeTo(outs) }
                    }
                }
            }
        }.also { runCatching { sock.close() } }
    }

    override fun stop() {
        isRunning = false
        runCatching { executor.shutdownNow() }
        runCatching { server.close() }
        thread?.interrupt()
        thread = null
    }

    override fun close() = stop()
}

/**
 * 测试 / 脚本客户端：连 TCP，发一帧并读回响应帧。
 * [authToken] 非空时随帧携带，供服务端校验。
 */
class TcpBridgeClient(host: String = "127.0.0.1", port: Int, private val authToken: String = "") {
    private val socket = Socket(host, port)
    private val out = socket.getOutputStream()
    private val inp = socket.getInputStream()

    fun request(frame: BridgeFrame): BridgeFrame? {
        BridgeFrame(frame.bridge, frame.id, frame.payload, authToken).writeTo(out)
        return BridgeFrame.readFrom(inp)
    }

    fun close() = runCatching { socket.close() }
}
