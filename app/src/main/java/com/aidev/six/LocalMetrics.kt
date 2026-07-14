package com.aidev.six

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 本地成功指标埋点（P2-10）。
 * 以追加写 JSONL 形式记录关键事件（构建成败、耗时），供复盘「自我进化闭环」健康度。
 * 全程 fail-safe：任何异常都吞掉，绝不影响主流程。
 */
object LocalMetrics {
    private const val DIR = ".aidev-metrics"
    private const val FILE = "events.jsonl"

    /** 主入口：从 Context 解析 home 后记录。 */
    fun record(context: Context, event: String, success: Boolean, detail: Map<String, Any> = emptyMap()) {
        runCatching { appendEvent(PathConfig.aidevHome(context.applicationContext), event, success, detail) }
    }

    fun recordBuild(context: Context, project: String, success: Boolean, durationMs: Long) {
        record(context, "build", success, mapOf("project" to project, "durationMs" to durationMs))
    }

    /** 测试用核心实现：直接对给定 home 写 JSONL（不依赖 Android Context）。 */
    internal fun appendEvent(home: File, event: String, success: Boolean, detail: Map<String, Any>) {
        val dir = File(home, DIR).apply { mkdirs() }
        val line = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("event", event)
            put("ok", success)
            for ((k, v) in detail) {
                when (v) {
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }.toString()
        File(dir, FILE).appendText(line + "\n")
    }
}
