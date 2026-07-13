package com.aidev.six.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenCode HTTP/SSE 客户端（无第三方依赖，纯 HttpURLConnection + org.json）。
 *
 * 后端事实见 docs/opencode-architecture.md、docs/opencode-web-ui-reference.md。
 * 默认连本机 opencode serve（127.0.0.1:4096），由 OpenCodeServerManager 负责拉起。
 */
class OpenCodeClient(
    private val baseUrl: String = com.aidev.six.Constants.OPENCODE_BASE_URL,
) {
    /** 当前项目工作目录（proot 内路径，如 /workspace/app）。为 null 时用后端默认目录。 */
    @Volatile var directory: String? = null

    // ---- 基础 HTTP ----

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.setMethodCompat(method)
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/json")
        directory?.let { conn.setRequestProperty("x-opencode-directory", it) }
        return conn
    }

    /** HttpURLConnection 原生不支持 PATCH，用反射兜底覆盖方法字段。 */
    private fun HttpURLConnection.setMethodCompat(method: String) {
        runCatching { requestMethod = method }.onFailure {
            runCatching {
                val f = HttpURLConnection::class.java.getDeclaredField("method")
                f.isAccessible = true
                f.set(this, method)
            }
        }
    }

    private fun httpGet(path: String): String? = runCatching {
        val conn = open(path, "GET")
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun httpPost(path: String, body: JSONObject?): String? = runCatching {
        val conn = open(path, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write((body?.toString() ?: "{}").toByteArray()) }
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // ---- API ----

    fun health(): Boolean {
        val txt = httpGet("/global/health") ?: return false
        return runCatching { JSONObject(txt).optBoolean("healthy", false) }.getOrDefault(false)
    }

    fun listSessions(): List<ChatSession> {
        val txt = httpGet("/session") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i -> parseSession(arr.optJSONObject(i)) }
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun createSession(title: String? = null): ChatSession? {
        val body = JSONObject().apply { if (!title.isNullOrBlank()) put("title", title) }
        val txt = httpPost("/session", body) ?: return null
        return runCatching { parseSession(JSONObject(txt)) }.getOrNull()
    }

    fun listAgents(): List<ChatAgent> {
        val txt = httpGet("/agent") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                ChatAgent(
                    name = name,
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    mode = o.optString("mode").takeIf { it.isNotBlank() },
                    native = o.optBoolean("native"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 动态拉取可用模型列表（替代硬编码）。GET /provider 返回
     * { all: [{ id, name, models: { [mid]: { id, name } } }] }。
     * 失败或为空时返回 null，调用方回退到 Constants.SELF_EVOLUTION_MODELS。
     */
    fun listProviders(): List<String>? {
        val txt = httpGet("/provider") ?: return null
        return runCatching {
            val obj = JSONObject(txt)
            val all = obj.optJSONArray("all") ?: return@runCatching null
            val models = mutableListOf<String>()
            for (i in 0 until all.length()) {
                val prov = all.optJSONObject(i) ?: continue
                val provId = prov.optString("id").ifBlank { continue }
                val ms = prov.optJSONObject("models") ?: continue
                val keys = ms.keys()
                while (keys.hasNext()) {
                    val mid = keys.next()
                    val m = ms.optJSONObject(mid) ?: continue
                    val modelId = m.optString("id").ifBlank { mid }
                    models.add("$provId/$modelId")
                }
            }
            models.distinct().ifEmpty { null }
        }.getOrNull()
    }

    fun listMessages(sessionId: String): List<ChatMessage> {
        val txt = httpGet("/session/$sessionId/message") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i -> parseMessage(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    /** 异步提交，立即返回；结果通过 SSE 事件流增量到达。 */
    fun sendPromptAsync(sessionId: String, text: String, providerID: String?, modelID: String?, agent: String?): Boolean {
        val body = JSONObject().apply {
            if (!agent.isNullOrBlank()) put("agent", agent)
            if (!providerID.isNullOrBlank() && !modelID.isNullOrBlank()) {
                put("model", JSONObject().put("providerID", providerID).put("modelID", modelID))
            }
            put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        }
        // prompt_async 返回 204 No Content；httpPost 对 2xx 返回非 null（可能是空串）
        return runCatching {
            val conn = open("/session/$sessionId/prompt_async", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    fun abort(sessionId: String): Boolean = httpPost("/session/$sessionId/abort", null) != null

    /**
     * 向终端 TUI 的提示词输入框追加文本（不直接提交）。对应 POST /tui/append-prompt。
     * 用于把构建修复命令「打」进终端 OpenCode 的命令行，所见即所得。
     * 返回后端原始响应（便于诊断），失败/无 TUI 返回 null。
     */
    fun appendTuiPrompt(text: String): String? {
        val body = JSONObject().apply { put("text", text) }
        val resp = httpPost("/tui/append-prompt", body)
        com.aidev.six.AIDevLogger.i("OpenCodeFix", "appendTuiPrompt resp=${resp?.take(200)}")
        com.aidev.six.LoopTrace.log("TUI", "append-prompt 状态码(2xx=成功) resp=${resp?.take(200)}")
        return resp
    }

    /** 提交终端 TUI 当前提示词（对应 POST /tui/submit-prompt），触发 OpenCode 处理。 */
    fun submitTuiPrompt(): String? {
        val resp = httpPost("/tui/submit-prompt", JSONObject().apply { put("text", "") })
        com.aidev.six.AIDevLogger.i("OpenCodeFix", "submitTuiPrompt resp=${resp?.take(200)}")
        com.aidev.six.LoopTrace.log("TUI", "submit-prompt 状态码(2xx=成功) resp=${resp?.take(200)}")
        return resp
    }

    /** 重命名会话：PATCH /session/:id { title }。 */
    fun renameSession(sessionId: String, title: String): Boolean = runCatching {
        val conn = open("/session/$sessionId", "PATCH")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("title", title).toString().toByteArray()) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)

    /** 删除会话：DELETE /session/:id。 */
    fun deleteSession(sessionId: String): Boolean = runCatching {
        val conn = open("/session/$sessionId", "DELETE")
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)

    /**
     * 回应权限请求。兼容 v1/v2 两套权限系统：
     * - v1: POST /session/:id/permissions/:permissionID { response }
     * - v2: POST /session/:id/permission/:requestID { reply, save? }  (ID 形如 per_xxx)
     * [response] ∈ "once" | "always" | "reject"。
     * [savePatterns]：v2 "always" 时可携带要保存的规则模式列表。
     */
    fun replyPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        savePatterns: List<String>? = null,
    ): Boolean {
        val body = if (permissionId.startsWith("per_")) {
            JSONObject().apply {
                put("reply", response)
                if (response == "always" && !savePatterns.isNullOrEmpty()) {
                    put("save", JSONArray(savePatterns))
                }
            }
        } else {
            JSONObject().put("response", response)
        }
        val path = if (permissionId.startsWith("per_")) {
            "/session/$sessionId/permission/$permissionId"
        } else {
            "/session/$sessionId/permissions/$permissionId"
        }
        return httpPost(path, body) != null
    }

    /**
     * 会话压缩（compaction）：POST /api/session/:id/compact。
     * 压缩历史消息为摘要以释放 token 空间。
     */
    fun compactSession(sessionId: String): Boolean {
        return httpPost("/api/session/$sessionId/compact", JSONObject()) != null
    }

    /**
     * 阻塞式读取 SSE /event 事件流，逐条回调。应在 IO 协程中调用。
     * [shouldContinue] 返回 false 时优雅退出。
     */
    fun streamEvents(shouldContinue: () -> Boolean, onEvent: (OcEvent) -> Unit) {
        val conn = URL("$baseUrl/event").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 0
        conn.setRequestProperty("Accept", "text/event-stream")
        directory?.let { conn.setRequestProperty("x-opencode-directory", it) }
        try {
            if (conn.responseCode !in 200..299) return
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            while (shouldContinue()) {
                val l = reader.readLine() ?: break
                if (!l.startsWith("data:")) continue
                val json = l.substringAfter("data:").trim()
                if (json.isEmpty()) continue
                val ev = runCatching { parseEvent(JSONObject(json)) }.getOrNull() ?: continue
                onEvent(ev)
            }
        } catch (_: Exception) {
            // 连接断开/超时：由调用方决定重连
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---- 解析 ----

    private fun parseSession(o: JSONObject?): ChatSession? {
        o ?: return null
        val id = o.optString("id").ifBlank { return null }
        val title = o.optString("title").ifBlank { "未命名会话" }
        val time = o.optJSONObject("time")
        val updated = time?.optLong("updated") ?: time?.optLong("created") ?: 0L
        val dir = o.optString("directory").takeIf { it.isNotBlank() }
        val agent = o.optString("agent").takeIf { it.isNotBlank() }
        return ChatSession(id, title, updated, dir, agent)
    }

    private fun parseMessage(o: JSONObject?): ChatMessage? {
        o ?: return null
        val info = o.optJSONObject("info") ?: return null
        val id = info.optString("id").ifBlank { return null }
        val role = info.optString("role", "assistant")
        val time = info.optJSONObject("time")
        val completed = role == "user" || (time?.has("completed") == true)
        val createdAt = time?.optLong("created", 0L)?.takeIf { it > 0 }
        val error = info.optJSONObject("error")?.optJSONObject("data")?.optString("message")
        val partsArr = o.optJSONArray("parts")
        val parts = mutableListOf<ChatPart>()
        if (partsArr != null) {
            for (i in 0 until partsArr.length()) {
                parsePart(partsArr.optJSONObject(i))?.let { parts.add(it) }
            }
        }
        return ChatMessage(id, role, parts, completed, error?.takeIf { it.isNotBlank() }, createdAt)
    }

    private fun parsePart(p: JSONObject?): ChatPart? {
        p ?: return null
        return when (p.optString("type")) {
            "text" -> {
                if (p.optBoolean("ignored", false) || p.optBoolean("synthetic", false)) null
                else p.optString("text").takeIf { it.isNotBlank() }?.let {
                    ChatPart.Text(it, p.optString("partID").takeIf { id -> id.isNotBlank() })
                }
            }
            "reasoning" -> p.optString("text").takeIf { it.isNotBlank() }?.let { ChatPart.Reasoning(it) }
            "tool" -> {
                val state = p.optJSONObject("state")
                val status = state?.optString("status") ?: "pending"
                val inputObj = state?.optJSONObject("input")
                val inputMap = mutableMapOf<String, Any?>()
                inputObj?.keys()?.forEach { k -> inputMap[k] = inputObj.opt(k) }
                ChatPart.Tool(
                    name = p.optString("tool", "tool"),
                    status = status,
                    title = state?.optString("title")?.takeIf { it.isNotBlank() },
                    output = state?.optString("output")?.takeIf { it.isNotBlank() },
                    errorText = state?.optString("error")?.takeIf { it.isNotBlank() },
                    input = inputMap,
                    toolCallId = p.optString("toolCallID").takeIf { it.isNotBlank() },
                )
            }
            "file" -> {
                val path = p.optString("path").takeIf { it.isNotBlank() } ?: return null
                ChatPart.File(path, p.optString("type", "file"))
            }
            "patch" -> {
                val filesArr = p.optJSONArray("files")
                val files = if (filesArr != null && filesArr.length() > 0) {
                    (0 until filesArr.length()).mapNotNull { filesArr.optString(it).takeIf { s -> s.isNotBlank() } }
                } else emptyList()
                if (files.isEmpty()) null else ChatPart.Patch(files)
            }
            "source-url" -> {
                val url = p.optString("url").takeIf { it.isNotBlank() } ?: return null
                ChatPart.SourceUrl(
                    sourceId = p.optString("sourceId").takeIf { it.isNotBlank() } ?: "",
                    url = url,
                    title = p.optString("title").takeIf { it.isNotBlank() },
                )
            }
            "step-start" -> {
                ChatPart.StepStart(p.optString("stepName").takeIf { it.isNotBlank() })
            }
            "compaction" -> {
                ChatPart.Compaction(p.optString("message").takeIf { it.isNotBlank() })
            }
            "agent" -> {
                val name = p.optString("agent").takeIf { it.isNotBlank() } ?: return null
                ChatPart.Agent(name, p.optString("description").takeIf { it.isNotBlank() })
            }
            "question-answer" -> {
                val question = p.optString("question").takeIf { it.isNotBlank() } ?: return null
                val answersArr = p.optJSONArray("answers")
                val answers = if (answersArr != null && answersArr.length() > 0) {
                    (0 until answersArr.length()).mapNotNull { answersArr.optString(it).takeIf { s -> s.isNotBlank() } }
                } else emptyList()
                ChatPart.QuestionAnswer(question, answers)
            }
            else -> null
        }
    }

    private fun parseEvent(o: JSONObject): OcEvent {
        val type = o.optString("type")
        val props = o.optJSONObject("properties")
        val sid = props?.optString("sessionID")?.takeIf { it.isNotBlank() }
        val status = props?.optString("status")?.takeIf { it.isNotBlank() }
        val permission = if (type.startsWith("permission.")) parsePermission(props, sid, type) else null
        val question = if (type.startsWith("question.")) parseQuestion(props, sid, type) else null
        // 流式 delta：message.part.delta 事件携带 data.delta 文本片段
        val deltaText = if (type == "message.part.delta") {
            props?.optString("delta")?.takeIf { it.isNotBlank() }
                ?: props?.optJSONObject("data")?.optString("delta")?.takeIf { it.isNotBlank() }
        } else null
        val deltaPartId = if (type == "message.part.delta") {
            props?.optString("partID")?.takeIf { it.isNotBlank() }
                ?: props?.optJSONObject("data")?.optString("partID")?.takeIf { it.isNotBlank() }
        } else null
        val deltaMessageId = if (type == "message.part.delta") {
            props?.optString("messageID")?.takeIf { it.isNotBlank() }
                ?: props?.optJSONObject("data")?.optString("messageID")?.takeIf { it.isNotBlank() }
        } else null
        // message.part.updated / message.updated：解析完整消息用于增量替换
        val updatedMessage = if (type == "message.part.updated" || type == "message.updated") {
            parseMessage(props)
        } else null
        return OcEvent(type, sid ?: permission?.sessionID ?: question?.sessionID, status, permission, question, deltaText, deltaPartId, deltaMessageId, updatedMessage)
    }

    /**
     * permission.asked / permission.v2.asked 的 payload 解析。
     * v1: { id, sessionID, permission:"bash", patterns:[...], always:[...] }
     * v2: { id:"per_xxx", sessionID, action:"bash", resources:[...], save:[...] }
     * 两者都可能嵌在 properties.permission 里。
     */
    private fun parsePermission(props: JSONObject?, fallbackSid: String?, type: String): PermissionRequest? {
        props ?: return null
        val p = props.optJSONObject("permission") ?: props
        // asked: id=perm_xxx / per_xxx；replied: requestID=per_xxx（v2 回复事件无 id 字段）
        val id = p.optString("id").ifBlank { p.optString("requestID") }.ifBlank { return null }
        val sid = p.optString("sessionID").ifBlank { fallbackSid ?: return null }
        val isV2 = type.contains("v2") || id.startsWith("per_")
        // v1 用 permission 字段；v2 用 action 字段
        val actionOrTool = if (isV2) {
            p.optString("action").ifBlank { p.optString("permission") }
        } else {
            p.optString("permission").ifBlank { p.optString("type") }
        }
        // v1 用 patterns；v2 用 resources
        val resourceKey = if (isV2) "resources" else "patterns"
        val patternsArr = p.optJSONArray(resourceKey)
        val allPatterns = if (patternsArr != null && patternsArr.length() > 0) {
            (0 until patternsArr.length()).mapNotNull { patternsArr.optString(it).takeIf { s -> s.isNotBlank() } }
        } else {
            val single = p.optString("pattern").takeIf { it.isNotBlank() }
            if (single != null) listOf(single) else emptyList()
        }
        val pattern = allPatterns.firstOrNull()
        val title = actionOrTool.takeIf { it.isNotBlank() } ?: "工具操作"
        val saveArr = p.optJSONArray("save")
        val save = if (saveArr != null && saveArr.length() > 0) {
            (0 until saveArr.length()).mapNotNull { saveArr.optString(it).takeIf { s -> s.isNotBlank() } }
        } else emptyList()
        return PermissionRequest(
            id = id,
            sessionID = sid,
            title = title,
            pattern = pattern,
            patterns = allPatterns,
            v2 = isV2,
            save = save,
        )
    }

    /**
     * question.asked / question.v2.asked 的 payload 解析。
     * v1/v2 字段基本一致：{ id, sessionID, question, header?, options:[{label,description}], multiple?, custom? }
     */
    private fun parseQuestion(props: JSONObject?, fallbackSid: String?, type: String): QuestionRequest? {
        props ?: return null
        val p = props.optJSONObject("question") ?: props
        val id = p.optString("id").ifBlank { p.optString("requestID") }.ifBlank { return null }
        val sid = p.optString("sessionID").ifBlank { fallbackSid ?: return null }
        val isV2 = type.contains("v2") || id.startsWith("qu_") || id.startsWith("q_")
        val question = p.optString("question").ifBlank { return null }
        val header = p.optString("header").takeIf { it.isNotBlank() }
        val optsArr = p.optJSONArray("options")
        val options = if (optsArr != null && optsArr.length() > 0) {
            (0 until optsArr.length()).mapNotNull { i ->
                val o = optsArr.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("label").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionOption(label, o.optString("description").takeIf { it.isNotBlank() } ?: "")
            }
        } else emptyList()
        val multiple = p.optBoolean("multiple")
        val custom = p.optBoolean("custom")
        return QuestionRequest(
            id = id,
            sessionID = sid,
            question = question,
            header = header,
            options = options,
            multiple = multiple,
            custom = custom,
            v2 = isV2,
        )
    }

    /**
     * 回复 AI 追问。兼容 v1/v2：
     * - v1: POST /question/:requestID/reply { answers: [["a","b"]] }
     * - v2: POST /v2/session/:id/question/:requestID/reply { questionV2Reply: { answers: [["a","b"]] } }
     * [answers] 为用户选中的 label 列表（多选时为多个，单选时为 1 个；custom 自由输入也为 1 个）。
     */
    fun replyQuestion(sessionId: String, requestId: String, answers: List<String>, v2: Boolean): Boolean {
        val answersArr = JSONArray().put(JSONArray(answers))
        val body = if (v2) {
            JSONObject().put("questionV2Reply", JSONObject().put("answers", answersArr))
        } else {
            JSONObject().put("answers", answersArr)
        }
        val path = if (v2) "/v2/session/$sessionId/question/$requestId/reply" else "/question/$requestId/reply"
        return httpPost(path, body) != null
    }

    /**
     * 拒绝 AI 追问。
     * - v1: POST /question/:requestID/reject
     * - v2: POST /v2/session/:id/question/:requestID/reject
     */
    fun rejectQuestion(sessionId: String, requestId: String, v2: Boolean): Boolean {
        val path = if (v2) "/v2/session/$sessionId/question/$requestId/reject" else "/question/$requestId/reject"
        return httpPost(path, null) != null
    }

    /** 列出可用斜杠命令：GET /command → Array<Command>。 */
    fun listCommands(): List<ChatCommand> {
        val txt = httpGet("/command") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").ifBlank { return@mapNotNull null }
                ChatCommand(
                    name = name,
                    description = o.optString("description").takeIf { it.isNotBlank() },
                    source = o.optString("source").takeIf { it.isNotBlank() },
                    agent = o.optString("agent").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 发送斜杠命令：POST /session/:id/command { command, arguments?, agent?, model? }。
     * [raw] 形如 "/build --project x" 或 "/compact"。
     */
    fun sendCommand(sessionId: String, raw: String, agent: String?, providerID: String?, modelID: String?): Boolean {
        val body = JSONObject()
        // raw 形如 "/build arg1 arg2" → command="build", arguments=["arg1","arg2"]
        val trimmed = raw.removePrefix("/").trim()
        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmd = parts.getOrElse(0) { "" }
        val args = parts.getOrElse(1) { "" }
        body.put("command", cmd)
        body.put("arguments", args)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (!providerID.isNullOrBlank() && !modelID.isNullOrBlank()) {
            body.put("model", JSONObject().put("providerID", providerID).put("modelID", modelID))
        }
        return runCatching {
            val conn = open("/session/$sessionId/command", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /**
     * 在会话上下文内执行 shell 命令：POST /session/:id/shell { command }。
     * [raw] 形如 "/sh ls -la"。返回的消息通过后续 refresh() 展示。
     */
    fun sendShell(sessionId: String, command: String, agent: String? = null): Boolean {
        val body = JSONObject().apply {
            put("command", command)
            if (!agent.isNullOrBlank()) put("agent", agent)
        }
        return runCatching {
            val conn = open("/session/$sessionId/shell", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /**
     * 获取会话的文件变更：GET /session/:id/diff?messageID=。
     * 返回每个文件的 unified diff（patch 字段）。无变更时返回空列表。
     */
    fun getSessionDiff(sessionId: String, messageId: String? = null): List<FileDiff> {
        val url = buildString {
            append("/session/$sessionId/diff")
            if (!messageId.isNullOrBlank()) append("?messageID=").append(messageId)
        }
        val txt = httpGet(url) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FileDiff(
                    file = file,
                    patch = o.optString("patch").takeIf { it.isNotBlank() } ?: "",
                    additions = o.optInt("additions"),
                    deletions = o.optInt("deletions"),
                    status = o.optString("status").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 列出目录内容：GET /file?path= （path 相对当前工作目录，必填，默认 "."）。 */
    fun listFiles(path: String): List<FileNode> {
        val effectivePath = path.ifBlank { "." }
        val url = "/file?path=${URLEncoder.encode(effectivePath, "UTF-8")}"
        val txt = httpGet(url) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                val p = o.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = o.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FileNode(name = name, path = p, type = type, ignored = o.optBoolean("ignored"))
            }
        }.getOrDefault(emptyList())
    }

    /** 按名称模式查找文件：GET /find?query= （返回相对路径字符串数组）。 */
    fun findFiles(query: String, limit: Int = 50): List<String> {
        val url = "/find?query=${URLEncoder.encode(query, "UTF-8")}&limit=$limit"
        val txt = httpGet(url) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    /** 读取文件内容：GET /file/content?path= 。 */
    fun readFile(path: String): FileContent? {
        val url = "/file/content?path=${URLEncoder.encode(path, "UTF-8")}"
        val txt = httpGet(url) ?: return null
        return runCatching {
            val o = JSONObject(txt)
            val type = o.optString("type").takeIf { it.isNotBlank() } ?: "text"
            val content = o.optString("content")
            FileContent(type = type, content = content)
        }.getOrDefault(null)
    }

    /** VCS 信息（当前分支）：GET /vcs 。 */
    fun getVcsInfo(): Pair<String?, String?> {
        val txt = httpGet("/vcs") ?: return null to null
        return runCatching {
            val o = JSONObject(txt)
            (o.optString("branch").takeIf { it.isNotBlank() }) to (o.optString("default_branch").takeIf { it.isNotBlank() })
        }.getOrDefault(null to null)
    }

    /** VCS 工作区变更文件（无 patch）：GET /vcs/status → Array<VcsFileStatus>。 */
    fun getVcsStatus(): List<FileDiff> {
        val txt = httpGet("/vcs/status") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FileDiff(file, "", o.optInt("additions"), o.optInt("deletions"), o.optString("status").takeIf { it.isNotBlank() })
            }
        }.getOrDefault(emptyList())
    }

    /** VCS 当前 diff（含 patch）：GET /vcs/diff?mode=git → Array<VcsFileDiff>。 */
    fun getVcsDiff(): List<FileDiff> {
        val txt = httpGet("/vcs/diff?mode=git") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FileDiff(
                    file = file,
                    patch = o.optString("patch").takeIf { it.isNotBlank() } ?: "",
                    additions = o.optInt("additions"),
                    deletions = o.optInt("deletions"),
                    status = o.optString("status").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 复制会话（在指定消息处 fork）：POST /session/:id/fork → 新会话 id。 */
    fun forkSession(sessionId: String, messageId: String? = null): String? {
        val body = JSONObject().apply {
            if (!messageId.isNullOrBlank()) put("messageID", messageId)
        }
        return runCatching {
            val conn = open("/session/$sessionId/fork", "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val txt = if (code in 200..299) BufferedReader(InputStreamReader(conn.inputStream)).readText() else null
            conn.disconnect()
            txt?.let { JSONObject(it).optString("id").takeIf { id -> id.isNotBlank() } }
        }.getOrDefault(null)
    }
}
