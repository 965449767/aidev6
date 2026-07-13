package com.aidev.six.chat

/** OpenCode 会话（对应 GET /session 的 Session）。 */
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val directory: String? = null,
    val agent: String? = null,
)

/** OpenCode agent 定义（对应 GET /agent）。build=默认主代理(可执行)，plan=规划(禁编辑)。 */
data class ChatAgent(
    val name: String,
    val description: String?,
    val mode: String?,
    val native: Boolean,
)

/** 可选的项目工作目录（proot 内路径，如 /workspace/myapp）。 */
data class ProjectDir(
    val name: String,
    val path: String,
)

/** 工具权限请求（对应 permission.asked / permission.v2.asked 事件）。
 *  v2 字段名不同：action→permission, resources→pattern, source→tool。 */
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val title: String,        // v1: permission 工具名; v2: action
    val pattern: String?,     // v1: patterns[0]; v2: resources[0]
    val patterns: List<String> = emptyList(),  // 全部资源/模式
    val v2: Boolean = false,  // 是否为 v2 权限系统
    val save: List<String> = emptyList(),  // v2: 可保存的"总是允许"模式
)

/** 一条消息内的可渲染片段，对应 OpenCode 的 Part union。 */
sealed class ChatPart {
    data class Text(val text: String, val partId: String? = null) : ChatPart()
    data class Reasoning(val text: String) : ChatPart()
    data class Tool(
        val name: String,
        val status: String,
        val title: String?,
        val output: String?,
        val errorText: String?,
        val input: Map<String, Any?> = emptyMap(),
        val toolCallId: String? = null,
    ) : ChatPart()
    data class File(val path: String, val type: String) : ChatPart()
    data class Patch(val files: List<String>) : ChatPart()
    /** 引用源 URL（websearch/codesearch 返回的链接）。 */
    data class SourceUrl(val sourceId: String, val url: String, val title: String?) : ChatPart()
    /** 步骤分隔标记（多步推理的视觉分隔）。 */
    data class StepStart(val stepName: String?) : ChatPart()
    /** 上下文压缩分隔符。 */
    data class Compaction(val message: String?) : ChatPart()
    /** Agent 引用（标注使用的 agent）。 */
    data class Agent(val name: String, val description: String?) : ChatPart()
    /** 消息内嵌 Q&A（问题+回答）。 */
    data class QuestionAnswer(val question: String, val answers: List<String>) : ChatPart()
    data class Other(val type: String) : ChatPart()
}

/** 聊天消息，对应 { info: Message, parts: Part[] }。 */
data class ChatMessage(
    val id: String,
    val role: String,
    val parts: List<ChatPart>,
    val completed: Boolean,
    val error: String?,
    val createdAt: Long? = null,
)

/** SSE 事件（仅解析原生 UI 需要的字段）。 */
data class OcEvent(
    val type: String,
    val sessionID: String?,
    val status: String?,
    val permission: PermissionRequest? = null,
    val question: QuestionRequest? = null,
    val deltaText: String? = null,
    val deltaPartId: String? = null,
    val deltaMessageId: String? = null,
    val updatedMessage: ChatMessage? = null,
)

/** AI 追问请求（对应 question.asked / question.v2.asked 事件）。
 *  AI 发起追问时若不处理，agent loop 会静默阻塞。 */
data class QuestionRequest(
    val id: String,
    val sessionID: String,
    val question: String,
    val header: String?,
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = false,
    val v2: Boolean = false,
)

data class QuestionOption(
    val label: String,
    val description: String,
)

/** OpenCode 斜杠命令（对应 GET /command）。 */
data class ChatCommand(
    val name: String,
    val description: String?,
    val source: String?,
    val agent: String?,
)

/** 文件变更（对应 GET /session/:id/diff 的 SnapshotFileDiff）。 */
data class FileDiff(
    val file: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val status: String?,
)

/** 文件/目录节点（对应 GET /file 的 FileNode）。 */
data class FileNode(
    val name: String,
    val path: String,
    val type: String, // "file" | "directory"
    val ignored: Boolean,
)

/** 文件内容（对应 GET /file/content 的 FileContent）。 */
data class FileContent(
    val type: String, // "text" | "binary"
    val content: String,
)
