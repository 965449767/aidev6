package com.aidev.six

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 桥接请求/响应的统一信封。
 *
 * 采用「4 字节大端长度 + UTF-8 JSON」的裸字节帧，与具体传输（LocalSocket / TCP loopback）解耦，
 * 因此核心编解码逻辑可在 JVM 单测中完全覆盖（见 [BridgeFrameTest]）。
 *
 * 字段：
 *  - [bridge] 目标桥名：`shizuku`|`build`|`deploy`|`notify`|`crash`
 *  - [id]     请求 id（透传，便于客户端匹配）
 *  - [payload] 原始内容（直接承载原有 KEY=VALUE 或 JSON 文本，业务层零改造）
 *  - [auth]   静态共享密钥（见 [Constants.BRIDGE_SOCKET_TOKEN]），服务端校验来源；留空表示不校验
 */
data class BridgeFrame(
    val bridge: String,
    val id: String,
    val payload: String,
    val auth: String = ""
) {
    fun toJsonString(): String = JSONObject().apply {
        put("b", bridge)
        put("i", id)
        put("p", payload)
        put("a", auth)
    }.toString()

    fun writeTo(out: OutputStream) {
        val json = toJsonString().toByteArray(StandardCharsets.UTF_8)
        if (json.size > MAX_PAYLOAD) throw IOException("bridge frame too large: ${json.size}")
        out.write((json.size shr 24) and 0xff)
        out.write((json.size shr 16) and 0xff)
        out.write((json.size shr 8) and 0xff)
        out.write(json.size and 0xff)
        out.write(json)
        out.flush()
    }

    companion object {
        const val MAX_PAYLOAD = 16 * 1024 * 1024

        fun parse(json: String): BridgeFrame? = runCatching {
            val o = JSONObject(json)
            BridgeFrame(
                bridge = o.optString("b", ""),
                id = o.optString("i", ""),
                payload = o.optString("p", ""),
                auth = o.optString("a", "")
            )
        }.getOrNull()

        fun readFrom(inp: InputStream): BridgeFrame? {
            val lenBuf = ByteArray(4)
            if (!inp.readFully(lenBuf, 4)) return null
            val len = ((lenBuf[0].toInt() and 0xff) shl 24) or
                ((lenBuf[1].toInt() and 0xff) shl 16) or
                ((lenBuf[2].toInt() and 0xff) shl 8) or
                (lenBuf[3].toInt() and 0xff)
            if (len <= 0 || len > MAX_PAYLOAD) return null
            val body = ByteArray(len)
            if (!inp.readFully(body, len)) return null
            return parse(String(body, StandardCharsets.UTF_8))
        }
    }
}

private fun InputStream.readFully(buf: ByteArray, n: Int): Boolean {
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) return false
        off += r
    }
    return true
}
