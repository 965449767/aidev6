package com.aidev.six.monitor

/**
 * AI 编码引擎抽象（target-architecture.md 定义的 AIEngine 契约）。
 *
 * 当前唯一实现为 [OpenCodeEngine]（OpenCode，固定 127.0.0.1 + 动态/默认端口）。
 * 未来若支持 Claude Code / Gemini CLI / Codex / Qwen / Aider 等，仅需新增实现，
 * 不动现有闭环逻辑（文件契约 / SSE 协议解析语义 / 通知行为均保持）。
 */
interface AIEngine {

    /** 引擎基址，如 http://127.0.0.1:4096 */
    val baseUrl: String

    /** GET /global/health —— 引擎是否在线 */
    fun isServerUp(): Boolean

    /** 启动 SSE 监控循环（在引擎在线后由调用方触发） */
    fun startMonitoring()

    /** 停止监控（取消协程子任务） */
    fun stopMonitoring()

    /** GET /session/active —— 轮询活跃会话并标记 busy */
    fun pollActiveSessions()

    /** POST /session/{id}/abort —— 中止指定会话 */
    fun abortSession(sessionId: String)
}
