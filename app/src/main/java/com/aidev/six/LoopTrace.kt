package com.aidev.six

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自我进化闭环追踪日志：把「构建失败 → 写回流文件 → 发送到宇宙 A OpenCode」的每一步
 * 无论成败都落盘到 SD 卡，便于在不依赖 logcat 的情况下排查回流为何不生效。
 *
 * 路径：/sdcard/self-evolution-trace.log（SD 卡根目录，随时可读）
 */
object LoopTrace {
    private val file = File("/sdcard/self-evolution-trace.log")
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        runCatching {
            val ts = fmt.format(Date())
            file.appendText("[$ts][$tag] $msg\n")
        }
    }

    @Synchronized
    fun section(title: String) {
        runCatching {
            val ts = fmt.format(Date())
            file.appendText("\n[$ts] ===== $title =====\n")
        }
    }
}
