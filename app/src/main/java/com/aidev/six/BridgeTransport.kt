package com.aidev.six

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket

/**
 * 桥接传输抽象。把「监听 + 收发帧」从具体实现（Android LocalSocket / JVM TCP loopback）解耦，
 * 使核心路由/分发逻辑可在纯 JVM 单测中覆盖，真机再验证 LocalSocket 实现。
 *
 * [handler] 收到请求帧后返回响应帧（可为 null 表示无即时响应，结果经文件通道异步回传）。
 */
interface BridgeTransport : Closeable {
    fun start(handler: (BridgeFrame) -> BridgeFrame?)
    fun stop()
    val isRunning: Boolean
}

/**
 * 真机/模拟器传输：Android 抽象命名空间 Unix Domain Socket（名称 `aidev_bridge`），
 * 无需文件系统路径，规避 SELinux/权限问题。
 */
class LocalSocketTransport(private val name: String) : BridgeTransport {
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile override var isRunning: Boolean = false

    override fun start(handler: (BridgeFrame) -> BridgeFrame?) {
        if (isRunning) return
        val srv = LocalServerSocket(name)
        server = srv
        isRunning = true
        thread = Thread {
            while (isRunning) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                Thread { handle(sock, handler) }.start()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handle(sock: LocalSocket, handler: (BridgeFrame) -> BridgeFrame?) {
        runCatching {
            sock.inputStream.use { ins ->
                sock.outputStream.use { outs ->
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
        runCatching { server?.close() }
        server = null
        thread?.interrupt()
        thread = null
    }

    override fun close() = stop()
}

/**
 * JVM 单测传输：127.0.0.1 TCP loopback，规避 Android LocalSocket 对运行环境的依赖。
 */
class LoopbackTcpTransport(port: Int = 0) : BridgeTransport {
    private val server = ServerSocket(port)
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
 * 测试客户端：连 TCP loopback，发一帧并读回响应帧。
 */
class LoopbackTcpClient(host: String = "127.0.0.1", port: Int) {
    private val socket = Socket(host, port)
    private val out = socket.getOutputStream()
    private val inp = socket.getInputStream()

    fun request(frame: BridgeFrame): BridgeFrame? {
        frame.writeTo(out)
        return BridgeFrame.readFrom(inp)
    }

    fun close() = runCatching { socket.close() }
}
