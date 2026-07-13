package com.aidev.six.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import org.json.JSONObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.content.ClipboardManager as AndroidClipboardManager
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import com.aidev.six.Constants
import com.aidev.six.chat.ChatAgent
import com.aidev.six.chat.ChatCommand
import com.aidev.six.chat.FileContent
import com.aidev.six.chat.FileDiff
import com.aidev.six.chat.FileNode
import com.aidev.six.chat.ChatMessage
import com.aidev.six.chat.ChatPart
import com.aidev.six.chat.ChatSession
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.chat.PermissionRequest
import com.aidev.six.chat.ProjectDir
import com.aidev.six.chat.QuestionRequest
import com.aidev.six.chat.ProjectRepository
import com.aidev.six.chat.BuildProgressFeed
import com.aidev.six.chat.BuildProgressInfo
import com.aidev.six.ui.components.MainView
import com.aidev.six.ui.components.MainViewSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackendState { CONNECTING, READY, FAILED }

/** 会话状态持有器：提升到 AppNavHost 保存，切换终端/AI 时不丢失。 */
class ChatUiState {
    var backend by mutableStateOf(BackendState.CONNECTING)
    val sessions = mutableStateListOf<ChatSession>()
    var currentSessionId by mutableStateOf<String?>(null)
    val messages = mutableStateListOf<ChatMessage>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var model by mutableStateOf(Constants.SELF_EVOLUTION_DEFAULT_MODEL)
    // 当前 agent 模式：OpenCode 默认主代理为 build（可执行）；plan 为规划模式(禁编辑)。
    // 默认 build，与 OpenCode 后端默认行为一致，确保聊天内可真正执行构建命令。
    var agent by mutableStateOf("build")
    val agents = mutableStateListOf<ChatAgent>()
    // 动态模型列表：优先后端 GET /provider 下发，失败回退硬编码
    val models = mutableStateListOf<String>().apply { addAll(Constants.SELF_EVOLUTION_MODELS) }
    // 斜杠命令列表：GET /command
    val commands = mutableStateListOf<ChatCommand>()
    // 文件变更查看：GET /session/:id/diff
    var diffFiles by mutableStateOf<List<FileDiff>?>(null)
    var gitBranch by mutableStateOf<String?>(null)
    // 文件浏览：GET /file、/find、/file/content
    var fileBrowserOpen by mutableStateOf(false)
    var browsePath by mutableStateOf("")
    var fileList by mutableStateOf<List<FileNode>>(emptyList())
    var searchResults by mutableStateOf<List<String>?>(null)
    var openedFile by mutableStateOf<FileContent?>(null)
    var browseLoading by mutableStateOf(false)
    var browseError by mutableStateOf<String?>(null)
    // 每种 agent 推荐模型：切换 agent 时自动跟随（用户可手动覆盖）。官方客户端 build/plan 各选不同模型。
    val agentModels = mutableStateMapOf<String, String>(
        "build" to Constants.SELF_EVOLUTION_DEFAULT_MODEL,
        "plan" to "opencode/deepseek-v4-flash-free",
    )
    var diagnostic by mutableStateOf("")
    var retryToken by mutableStateOf(0)
    var initialized by mutableStateOf(false)
    // Phase 1：项目目录绑定
    val projects = mutableStateListOf<ProjectDir>()
    var currentProjectDir by mutableStateOf(ProjectRepository.WORKSPACE_ROOT)
    // Phase 2：权限审批
    var pendingPermission by mutableStateOf<PermissionRequest?>(null)
    // Phase 2b：AI 追问（不处理会静默阻塞 agent loop）
    var pendingQuestion by mutableStateOf<QuestionRequest?>(null)
    // Phase 6：错误反馈
    var sendError by mutableStateOf<String?>(null)
    var connLost by mutableStateOf(false)
    // 构建进度投射（BuildBridge → 聊天）
    var buildTask by mutableStateOf<BuildProgressInfo?>(null)
    // 构建看门狗提示：用户明确要求构建但 AI 未真正提交请求时给出手动补救方式
    var buildHint by mutableStateOf<String?>(null)
    // 流式刷新节流：避免 message.part.updated 过频导致 recomposition 风暴
    var lastRefreshMs by mutableStateOf(0L)
    // 流式 delta 缓冲：SSE delta 事件逐字追加，作为 part.updated 到达前的中间态
    val streamingText = mutableStateMapOf<String, String>()
    // 停止生成状态：点击停止按钮后短暂显示"停止中…"
    var stopping by mutableStateOf(false)
}

@Composable
fun ChatPanel(
    state: ChatUiState,
    mainView: MainView = MainView.CHAT,
    onSwitchView: (MainView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { OpenCodeClient() }

    val sessions = state.sessions
    val messages = state.messages
    var showSessions by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 自动滚动守卫：用户手动上翻时不强制滚动回底部
    val isUserAtBottom by remember {
        derivedStateOf {
            val lastIdx = listState.layoutInfo.totalItemsCount - 1
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: lastIdx
            (lastIdx - lastVisible) <= 3
        }
    }

    // 每次组合应用当前项目目录到客户端（所有请求带 x-opencode-directory）
    SideEffect { client.directory = state.currentProjectDir }

    suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (now - state.lastRefreshMs < 300) return // 去抖：300ms 内不重复刷新
        state.lastRefreshMs = now
        val sid = state.currentSessionId ?: return
        val fresh = withContext(Dispatchers.IO) { client.listMessages(sid) }
        messages.clear()
        messages.addAll(fresh)
        if (!state.busy) state.streamingText.clear()
    }

    suspend fun reloadSessions() {
        val list = withContext(Dispatchers.IO) { client.listSessions() }
        sessions.clear()
        sessions.addAll(SessionDirectoryStore.fill(context, list))
    }

    // 加载项目列表
    LaunchedEffect(Unit) {
        if (state.projects.isEmpty()) {
            val list = withContext(Dispatchers.IO) { ProjectRepository.list(context) }
            state.projects.clear()
            state.projects.addAll(list)
        }
    }

    // 后端拉起 + 首屏加载（已就绪则跳过，避免切回时重连闪烁与丢会话）
    LaunchedEffect(state.retryToken) {
        if (state.backend == BackendState.READY && state.initialized) return@LaunchedEffect
        state.backend = BackendState.CONNECTING
        val ok = OpenCodeServerManager.ensureRunning(context)
        state.diagnostic = if (ok) "" else OpenCodeServerManager.lastDiagnostic
        state.backend = if (ok) BackendState.READY else BackendState.FAILED
        if (ok) {
            state.initialized = true
            reloadSessions()
            val ags = withContext(Dispatchers.IO) { client.listAgents() }
            state.agents.clear()
            state.agents.addAll(ags)
            // 模型列表：使用 Constants 硬编码的 5 个免费模型，不从 API 拉取（API 返回的免费模型大多不可用）
            // 斜杠命令列表
            val cmds = withContext(Dispatchers.IO) { client.listCommands() }
            state.commands.clear()
            state.commands.addAll(cmds)
            // 默认跟随 OpenCode 默认主代理 build
            if (state.agent.isBlank()) state.agent = "build"
        }
    }

    // 切换项目：按新目录重新拉取会话列表；若当前打开的会话不在新列表内才清空
    LaunchedEffect(state.currentProjectDir, state.backend) {
        if (state.backend != BackendState.READY) return@LaunchedEffect
        client.directory = state.currentProjectDir
        val prev = state.currentSessionId
        messages.clear()
        reloadSessions()
        if (prev != null && sessions.none { it.id == prev }) {
            state.currentSessionId = null
        }
    }

    // 会话切换时加载历史，并同步该会话使用的 agent 模式
    LaunchedEffect(state.currentSessionId, state.backend) {
        if (state.backend == BackendState.READY && state.currentSessionId != null) {
            sessions.find { it.id == state.currentSessionId }?.agent?.let { state.agent = it }
            refresh()
        }
    }

    // 构建进度投射：轮询 BuildBridge 写入的 agent-tasks.json（活动构建），
    // 以及 .aidev-build-bridge/req-*.json（已提交待认领）。发出请求立即有卡片反馈。
    LaunchedEffect(state.backend) {
        if (state.backend != BackendState.READY) return@LaunchedEffect
        BuildProgressFeed.poll(context, 1500) { state.buildTask = it }
    }

    // SSE 事件流：按当前会话增量刷新 + busy 状态 + 权限请求（随项目目录重连）
    LaunchedEffect(state.backend, state.currentProjectDir) {
        if (state.backend != BackendState.READY) return@LaunchedEffect
        client.directory = state.currentProjectDir
        withContext(Dispatchers.IO) {
            var backoffMs = 2000L
            while (true) {
                state.connLost = false
                backoffMs = 2000L
                client.streamEvents({ true }) { ev ->
                    // 权限事件：不限定当前会话，先接住
                    when {
                        ev.type.startsWith("permission.") -> {
                            val perm = ev.permission
                            // replied/removed 事件（含 v2）关闭弹窗；asked 事件弹出
                            if (ev.type.endsWith(".replied") || ev.type.endsWith(".removed")) {
                                if (state.pendingPermission?.id == perm?.id) state.pendingPermission = null
                            } else if (perm != null) {
                                state.pendingPermission = perm
                            }
                            return@streamEvents
                        }
                        ev.type.startsWith("question.") -> {
                            val q = ev.question
                            // replied/rejected 事件（含 v2）关闭弹窗；asked 事件弹出
                            if (ev.type.endsWith(".replied") || ev.type.endsWith(".rejected")) {
                                if (state.pendingQuestion?.id == q?.id) state.pendingQuestion = null
                            } else if (q != null) {
                                state.pendingQuestion = q
                            }
                            return@streamEvents
                        }
                    }
                    // 全局会话生命周期事件：保持会话列表同步（后端创建/改名/删除会话时，不限当前会话）
                    if (ev.type == "session.created" || ev.type == "session.updated" || ev.type == "session.deleted") {
                        scope.launch { reloadSessions() }
                    }
                    val sid = state.currentSessionId ?: return@streamEvents
                    if (ev.sessionID != null && ev.sessionID != sid) return@streamEvents
                    when (ev.type) {
                        "session.status" -> {
                            state.busy = ev.status == "busy"
                            // 部分 opencode 版本会丢 message.part.updated；turn 结束(idle)时兜底全量拉取
                            if (ev.status != "busy") scope.launch { refresh() }
                        }
                        "message.updated", "message.part.updated" -> {
                            // 增量更新：直接用事件携带的完整消息替换本地对应消息的 parts
                            val updated = ev.updatedMessage
                            if (updated != null) {
                                val idx = messages.indexOfFirst { it.id == updated.id }
                                if (idx >= 0) {
                                    messages[idx] = updated
                                    // 清掉该消息的 delta 缓冲（已合并到 parts 中）
                                    state.streamingText.remove(updated.id)
                                } else if (ev.type == "message.updated") {
                                    // 新消息：追加到末尾
                                    messages.add(updated)
                                }
                            } else {
                                // 兜底：无法解析完整消息时全量刷新
                                scope.launch { refresh() }
                            }
                        }
                        "message.part.delta" -> {
                            // 流式：delta 文本追加到本地缓冲，立即渲染，不等 refresh
                            val msgId = ev.deltaMessageId ?: return@streamEvents
                            val delta = ev.deltaText ?: return@streamEvents
                            val old = state.streamingText[msgId] ?: ""
                            state.streamingText[msgId] = old + delta
                        }
                    }
                }
                state.connLost = true
                state.streamingText.clear()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    // busy 超时保护：若 busy 持续 5 分钟无事件更新，自动重置为 idle
    LaunchedEffect(state.busy) {
        if (state.busy) {
            delay(5 * 60 * 1000L)
            if (state.busy) {
                state.busy = false
                state.stopping = false
                scope.launch { refresh() }
            }
        } else {
            // busy→idle 时清除 stopping 状态
            state.stopping = false
        }
    }

    // 自动滚动：有新消息滚到最后消息；有构建进度卡片时滚到卡片（在其后一条），
    // 否则长对话下构建卡片会落在屏幕外而看不到实时进度。
    LaunchedEffect(messages.size, state.buildTask) {
        if (messages.isNotEmpty() && isUserAtBottom) {
            val bt = state.buildTask
            val target = if (bt != null && (bt.isActive || bt.isRecent)) messages.size else messages.size - 1
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    // 构建看门狗：用户明确要求构建且 AI 已回复，但无任构建活动（待认领请求/任务记录）时，
    // 提示 AI 可能只说了"已交给宇宙B"却未真正执行命令，给出手动补救方式。
    LaunchedEffect(state.busy, messages.size, state.buildTask) {
        if (state.busy) { state.buildHint = null; return@LaunchedEffect }
        val lastUser = messages.lastOrNull { it.role == "user" } ?: return@LaunchedEffect
        if (!lastUserMessageHasBuildIntent(lastUser)) { state.buildHint = null; return@LaunchedEffect }
        // 给 BuildBridge（每 500ms 轮询）留出认领并写 agent-tasks.json 的时间，避免误报
        delay(3000)
        if (state.busy) return@LaunchedEffect
        val pending = withContext(Dispatchers.IO) { BuildProgressFeed.hasPendingRequest(context) }
        val task = withContext(Dispatchers.IO) { BuildProgressFeed.latest(context) }
        if (pending || task != null) { state.buildHint = null; return@LaunchedEffect }
        val proj = extractBuildProject(lastUser) ?: "MyAndroidProject"
        state.buildHint = "未检测到构建请求被提交（AI 可能只说了没执行）。可在终端手动运行：\naidev-build-request --project /workspace/$proj"
    }

    fun send() {
        val text = state.input.trim()
        if (text.isEmpty() || state.backend != BackendState.READY || state.busy) return
        state.input = ""
        state.sendError = null
        scope.launch {
            client.directory = state.currentProjectDir
            var sid = state.currentSessionId
            if (sid == null) {
                val s = withContext(Dispatchers.IO) { client.createSession() }
                if (s != null) {
                    SessionDirectoryStore.set(context, s.id, state.currentProjectDir)
                    sessions.add(0, s)
                    state.currentSessionId = s.id
                    sid = s.id
                } else {
                    state.sendError = "创建会话失败，请检查后端连接。"
                    return@launch
                }
            }
            val target = sid ?: return@launch
            messages.add(
                ChatMessage("local-${System.currentTimeMillis()}", "user", listOf(ChatPart.Text(text)), true, null)
            )
            state.busy = true
            val (prov, mid) = splitModel(state.model)
            val ok = when {
                text.startsWith("/sh ") || text == "/sh" -> {
                    // 会话内执行 shell：/sh <command>
                    val cmd = text.removePrefix("/sh").trim()
                    if (cmd.isBlank()) {
                        state.busy = false
                        state.sendError = "用法：/sh <命令>，例如 /sh ls -la"
                        return@launch
                    }
                    withContext(Dispatchers.IO) { client.sendShell(target, cmd, state.agent) }
                }
                text.startsWith("/") -> {
                    // 斜杠命令走专用端点，驱动自进化闭环（/build、/compact 等）
                    withContext(Dispatchers.IO) { client.sendCommand(target, text, state.agent, prov, mid) }
                }
                else -> {
                    withContext(Dispatchers.IO) { client.sendPromptAsync(target, text, prov, mid, state.agent) }
                }
            }
            if (!ok) {
                messages.removeLastOrNull()
                state.busy = false
                state.sendError = if (text.startsWith("/")) "命令发送失败（后端未接受请求）。" else "消息发送失败（后端未接受请求）。"
            }
            delay(400)
            refresh()
        }
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // 顶栏：☰ + 标题 + 模型 + agent + ⋯(更多) + ＋
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showSessions = !showSessions }, modifier = Modifier.size(36.dp)) {
                Text("☰", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = sessions.firstOrNull { it.id == state.currentSessionId }?.title ?: "新对话",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                Crossfade(targetState = state.backend to state.connLost, label = "subtitle") { (backend, connLost) ->
                    Text(
                        text = when {
                            backend == BackendState.FAILED -> "后端未就绪，点此重试"
                            connLost && backend == BackendState.READY -> "连接中断，重连中…"
                            backend == BackendState.CONNECTING -> "正在连接…"
                            backend == BackendState.READY -> "${when (state.agent) { "build" -> "构建" "plan" -> "规划" else -> state.agent }} · ${if (state.busy) "生成中…" else "就绪"}"
                            else -> ""
                        },
                        color = when {
                            backend == BackendState.FAILED -> MaterialTheme.colorScheme.error
                            connLost -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable(enabled = backend == BackendState.FAILED) { state.retryToken++ },
                    )
                }
            }
            // 模型选择器：免费模型优先展示，其余折叠
            Box {
                TextButton(onClick = { showModelMenu = true }, modifier = Modifier.height(32.dp)) {
                    val rec = state.agentModels[state.agent]
                    Text(
                        state.model.substringAfter('/') + if (state.model == rec) " ★" else "",
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                    state.models.forEach { m ->
                        val isRec = state.agentModels[state.agent] == m
                        DropdownMenuItem(
                            text = {
                                Text(
                                    m.substringAfter('/') + if (isRec) " ★推荐" else "",
                                    fontWeight = if (m == state.model) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                state.model = m
                                state.agentModels[state.agent] = m
                                showModelMenu = false
                            },
                        )
                    }
                }
            }
            // Agent 选择器
            Box {
                TextButton(onClick = { showAgentMenu = true }, modifier = Modifier.height(32.dp)) {
                    val label = when (state.agent) {
                        "build" -> "构建"
                        "plan" -> "规划"
                        else -> state.agent
                    }
                    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                DropdownMenu(expanded = showAgentMenu, onDismissRequest = { showAgentMenu = false }) {
                    val userAgents = state.agents
                        .filter { it.mode == "primary" && it.name !in setOf("compaction", "summary", "title") }
                        .ifEmpty {
                            listOf(
                                ChatAgent("build", "构建（默认，可执行命令）", "primary", true),
                                ChatAgent("plan", "规划（禁编辑）", "primary", true),
                            )
                        }
                    userAgents.forEach { a ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        when (a.name) { "build" -> "构建" "plan" -> "规划" else -> a.name },
                                        fontWeight = if (a.name == state.agent) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    a.description?.let {
                                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            },
                            onClick = {
                                state.agent = a.name
                                state.agentModels[a.name]?.let { state.model = it }
                                showAgentMenu = false
                            },
                        )
                    }
                }
            }
            // 更多菜单：项目/文件/变更/Git
            Box {
                IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(36.dp)) {
                    Text("⋯", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    DropdownMenuItem(text = { Text("切换项目") }, onClick = {
                        showMoreMenu = false
                        showProjectMenu = true
                    })
                    DropdownMenuItem(text = { Text("浏览文件") }, onClick = {
                        showMoreMenu = false
                        state.fileBrowserOpen = true
                        state.browsePath = ""
                        state.searchResults = null
                        state.openedFile = null
                        scope.launch {
                            state.browseLoading = true
                            state.fileList = withContext(Dispatchers.IO) { client.listFiles("") }
                            state.browseLoading = false
                        }
                    })
                    DropdownMenuItem(text = { Text("查看会话变更") }, onClick = {
                        showMoreMenu = false
                        val sid = state.currentSessionId ?: return@DropdownMenuItem
                        state.gitBranch = null
                        scope.launch {
                            val diffs = withContext(Dispatchers.IO) { client.getSessionDiff(sid) }
                            state.diffFiles = diffs
                        }
                    })
                    DropdownMenuItem(text = { Text("Git 工作区变更") }, onClick = {
                        showMoreMenu = false
                        scope.launch {
                            val branch = withContext(Dispatchers.IO) { client.getVcsInfo() }
                            val status = withContext(Dispatchers.IO) { client.getVcsStatus() }
                            val diffs = withContext(Dispatchers.IO) { client.getVcsDiff() }
                            val diffFiles = diffs.map { it.file }.toSet()
                            val merged = diffs + status.filter { it.file !in diffFiles }
                            state.diffFiles = merged
                            state.gitBranch = branch.first
                        }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("切换到终端") }, onClick = {
                        showMoreMenu = false
                        onSwitchView(MainView.TERMINAL)
                    })
                }
            }
            // 项目选择弹出菜单
            DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                state.projects.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name + if (p.path == state.currentProjectDir) "  ✓" else "") },
                        onClick = { state.currentProjectDir = p.path; showProjectMenu = false },
                    )
                }
            }
            IconButton(onClick = {
                state.currentSessionId = null
                messages.clear()
            }, modifier = Modifier.size(36.dp)) {
                Text("＋", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
            }
        }

        if (showSessions) {
            SessionList(
                sessions,
                state.currentSessionId,
                onSelect = { id ->
                    val dir = sessions.find { it.id == id }?.directory?.takeIf { it.isNotBlank() }
                    if (dir != null && dir != state.currentProjectDir) state.currentProjectDir = dir
                    state.currentSessionId = id
                    showSessions = false
                },
                onRename = { s, name ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { client.renameSession(s.id, name) }
                        if (ok) {
                            val idx = sessions.indexOf(s)
                            if (idx >= 0) sessions[idx] = s.copy(title = name)
                        }
                    }
                },
                onDelete = { s ->
                    scope.launch {
                        withContext(Dispatchers.IO) { client.deleteSession(s.id) }
                        sessions.remove(s)
                        if (state.currentSessionId == s.id) {
                            state.currentSessionId = null
                            messages.clear()
                        }
                    }
                },
                onFork = { s ->
                    scope.launch {
                        val newId = withContext(Dispatchers.IO) { client.forkSession(s.id) }
                        if (newId != null) {
                            showSessions = false
                            state.currentSessionId = newId
                            // 重新加载会话列表以获取新 fork 的会话
                            val list = withContext(Dispatchers.IO) { client.listSessions() }
                            sessions.clear()
                            sessions.addAll(SessionDirectoryStore.fill(context, list))
                        }
                    }
                },
            )
        }

        // 消息流
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.backend == BackendState.CONNECTING) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("启动 OpenCode 后端…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            } else if (state.backend == BackendState.FAILED) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("后端未就绪", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.diagnostic.ifBlank { "无法连接 OpenCode 服务。" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { state.retryToken++ }) { Text("重试") }
                }
            } else if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🤖", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("AIDev Chat", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "输入消息开始对话，或试试这些命令：",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "/build" to "执行构建",
                        "/compact" to "压缩会话上下文",
                        "/init" to "初始化项目",
                        "/undo" to "撤销上次变更",
                    ).forEach { (cmd, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { state.input = "$cmd " }.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(cmd, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        // 流式文本合并：SSE delta 追加到本地缓冲，作为 part.updated 到达前的中间态
                        val displayMsg = state.streamingText[msg.id]?.let { delta ->
                            if (delta.isEmpty()) msg else {
                                val lastTextIdx = msg.parts.indexOfLast { it is ChatPart.Text }
                                if (lastTextIdx < 0) msg else {
                                    val newParts = msg.parts.toMutableList()
                                    val old = (newParts[lastTextIdx] as ChatPart.Text).text
                                    newParts[lastTextIdx] = ChatPart.Text(old + delta)
                                    msg.copy(parts = newParts)
                                }
                            }
                        } ?: msg
                        // streaming 光标：仅当前最后一条 assistant 消息 + busy 时显示
                        val isLastAssistant = msg.id == messages.lastOrNull()?.id && msg.role == "assistant"
                        Box(modifier = Modifier.animateItem()) {
                            MessageBubble(displayMsg, isStreaming = state.busy && isLastAssistant)
                        }
                    }
                    // 构建/任务进度内嵌到对话流：与消息统一风格，实时投射 BuildBridge 过程
                    val bt = state.buildTask
                    if (bt != null && (bt.isActive || bt.isRecent)) {
                        item(key = "build-progress") {
                            BuildConversationBlock(bt) { state.buildTask = null }
                        }
                    }
                    // AI 打字指示器：busy 时显示 pulsing dots
                    if (state.busy) {
                        item(key = "typing-indicator") {
                            TypingIndicator()
                        }
                    }
                }
                // Scroll-to-bottom FAB：滚动后显示，点击回到底部
                val showScrollDown by remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex < messages.size - 3
                    }
                }
                if (showScrollDown && messages.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp),
                        shape = CircleShape,
                    ) {
                        Text("↓", fontSize = 16.sp)
                    }
                }
            }
        }

        // 发送错误提示
        state.sendError?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { state.sendError = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠ $err（点击关闭）", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, maxLines = 2)
            }
        }

        // 构建看门狗提示：AI 未真正提交构建请求时给出手动补救方式
        state.buildHint?.let { hint ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { state.buildHint = null }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠ $hint（点击关闭）", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, maxLines = 4)
            }
        }

        // 输入区
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            // 斜杠命令建议：输入以 / 开头时列出匹配命令
            if (state.input.startsWith("/") && state.commands.isNotEmpty()) {
                val q = state.input.removePrefix("/").trim()
                val matched = state.commands.filter { it.name.contains(q, ignoreCase = true) }.take(10)
                if (matched.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp)) {
                            matched.forEach { cmd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { state.input = "/${cmd.name} " }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("/${cmd.name}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(cmd.description ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("给 AI 发消息…（/ 触发命令）") },
                maxLines = 5,
                keyboardOptions = KeyboardOptions.Default,
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        Text(
                            "${state.input.length}",
                            fontSize = 10.sp,
                            color = if (state.input.length > 4000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (state.busy || state.stopping) {
                Surface(
                    onClick = {
                        if (!state.stopping) {
                            state.stopping = true
                            scope.launch {
                                state.currentSessionId?.let { sid -> withContext(Dispatchers.IO) { client.abort(sid) } }
                                state.stopping = false
                            }
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.stopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("■", color = MaterialTheme.colorScheme.onError, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                IconButton(onClick = { send() }, enabled = state.input.isNotBlank() && state.backend == BackendState.READY) {
                    Text("➤", color = if (state.input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
            }
            }
        }
    }

    // 权限审批弹窗
    state.pendingPermission?.let { perm ->
        PermissionDialog(
            perm = perm,
            onReply = { response ->
                scope.launch {
                    val save = if (response == "always" && perm.save.isNotEmpty()) perm.save else null
                    withContext(Dispatchers.IO) { client.replyPermission(perm.sessionID, perm.id, response, save) }
                    state.pendingPermission = null
                }
            },
        )
    }

    // AI 追问弹窗
    state.pendingQuestion?.let { q ->
        QuestionDialog(
            q = q,
            onReply = { answers ->
                scope.launch {
                    withContext(Dispatchers.IO) { client.replyQuestion(q.sessionID, q.id, answers, q.v2) }
                    state.pendingQuestion = null
                }
            },
            onReject = {
                scope.launch {
                    withContext(Dispatchers.IO) { client.rejectQuestion(q.sessionID, q.id, q.v2) }
                    state.pendingQuestion = null
                }
            },
        )
    }

    // 文件变更查看弹窗
    state.diffFiles?.let { diffs ->
        val title = state.gitBranch?.let { "Git 变更 · $it（${diffs.size} 个文件）" }
        DiffDialog(files = diffs, onDismiss = { state.diffFiles = null; state.gitBranch = null }, title = title)
    }

    // 文件浏览器弹窗
    if (state.fileBrowserOpen) {
        FileBrowserDialog(state = state, client = client, scope = scope, onDismiss = { state.fileBrowserOpen = false })
    }
}

@Composable
private fun PermissionDialog(perm: PermissionRequest, onReply: (String) -> Unit) {
    BackHandler { /* 阻止返回键关闭，需明确选择 */ }
    AlertDialog(
        onDismissRequest = { /* 需明确选择，不允许点外部关闭 */ },
        title = { Text("AI 请求执行操作") },
        text = {
            Column {
                Text(
                    if (perm.v2) "操作类型：${perm.title}" else "工具：${perm.title}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (perm.patterns.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        perm.patterns.joinToString("\n"),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("是否允许 AI 执行此操作？", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (perm.v2 && perm.save.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "提示：选「总是允许」将保存规则：${perm.save.joinToString(", ")}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onReply("once") }) { Text("允许一次") }
                TextButton(onClick = { onReply("always") }) { Text("总是允许") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onReply("reject") }) { Text("拒绝") }
        },
    )
}

/**
 * AI 追问弹窗：单选/多选选项 + 自由输入（custom）。不回答会静默阻塞 agent loop。
 */
@Composable
private fun QuestionDialog(q: QuestionRequest, onReply: (List<String>) -> Unit, onReject: () -> Unit) {
    BackHandler { /* 阻止返回键关闭，需明确选择 */ }
    val selected = remember(q.id) { mutableStateListOf<String>() }
    var customText by remember(q.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* 需明确选择，不允许点外部关闭 */ },
        title = { Text(q.header?.takeIf { it.isNotBlank() } ?: "AI 提问") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(q.question, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                q.options.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (q.multiple) {
                                    if (selected.contains(opt.label)) selected.remove(opt.label) else selected.add(opt.label)
                                } else {
                                    selected.clear()
                                    selected.add(opt.label)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected.contains(opt.label),
                            onCheckedChange = {
                                if (q.multiple) {
                                    if (it) selected.add(opt.label) else selected.remove(opt.label)
                                } else {
                                    selected.clear()
                                    if (it) selected.add(opt.label)
                                }
                            },
                        )
                        Column {
                            Text(opt.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (opt.description.isNotBlank()) {
                                Text(opt.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (q.custom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("自定义回答…") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() || (q.custom && customText.isNotBlank()),
                onClick = {
                    val answers = if (selected.isNotEmpty()) selected.toList() else listOf(customText)
                    onReply(answers)
                },
            ) { Text("提交") }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("跳过") }
        },
    )
}

/**
 * 文件变更查看弹窗：展示 AI 对本会话的文件改动（unified diff）。
 */
@Composable
private fun DiffDialog(files: List<FileDiff>, onDismiss: () -> Unit, title: String? = null) {
    var expandedFile by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: "文件变更（${files.size} 个文件）") },
        text = {
            if (files.isEmpty()) {
                Text("本会话暂无文件改动。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(files, key = { it.file }) { d ->
                        val isOpen = expandedFile == d.file
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedFile = if (isOpen) null else d.file
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (isOpen) "▾" else "▸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                                Text(d.file, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                Text("+${d.additions}", fontSize = 11.sp, color = Color(0xFF4fd6be))
                                Spacer(Modifier.width(4.dp))
                                Text("-${d.deletions}", fontSize = 11.sp, color = Color(0xFFc53b53))
                            }
                            if (isOpen && d.patch.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    d.patch,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 60,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 文件浏览器弹窗：浏览工作目录（GET /file）、按名查找（GET /find）、查看内容（GET /file/content）。
 */
@Composable
private fun FileBrowserDialog(
    state: ChatUiState,
    client: OpenCodeClient,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember(state.fileBrowserOpen) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 560.dp),
        title = {
            Column {
                Text("文件浏览" + if (state.openedFile == null && state.searchResults == null) " · ${state.browsePath.ifBlank { "/" }}".replace("/", "根目录") else "")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("查找文件…", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        if (searchQuery.isBlank()) {
                            state.searchResults = null
                            return@TextButton
                        }
                        scope.launch {
                            state.browseLoading = true
                            state.openedFile = null
                            state.searchResults = withContext(Dispatchers.IO) { client.findFiles(searchQuery) }
                            state.browseLoading = false
                        }
                    }) { Text("搜", fontSize = 12.sp) }
                }
            }
        },
        text = {
            if (state.browseLoading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.openedFile != null) {
                val fc = state.openedFile!!
                if (fc.type == "binary") {
                    Text("（二进制文件，无法预览）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val lines = fc.content.lines()
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(lines) { ln ->
                            Text(ln, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { state.openedFile = null }) { Text("← 返回", fontSize = 12.sp) }
            } else if (state.searchResults != null) {
                val res = state.searchResults!!
                if (res.isEmpty()) {
                    Text("未找到匹配文件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(res) { p ->
                            TextButton(onClick = {
                                scope.launch {
                                    state.browseLoading = true
                                    state.openedFile = withContext(Dispatchers.IO) { client.readFile(p) }
                                    state.browseLoading = false
                                }
                            }) {
                                Text(p, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { state.searchResults = null }) { Text("← 清除搜索", fontSize = 12.sp) }
            } else {
                if (state.browseError != null) {
                    Text(state.browseError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    // 返回上级（非根目录时）
                    if (state.browsePath.isNotBlank()) {
                        item {
                            TextButton(onClick = {
                                scope.launch {
                                    state.browseLoading = true
                                    state.browsePath = state.browsePath.substringBeforeLast('/', "")
                                    state.fileList = withContext(Dispatchers.IO) { client.listFiles(state.browsePath) }
                                    state.browseLoading = false
                                }
                            }) {
                                Text("📁 ..", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    // 目录优先，再文件，按名称排序
                    val sorted = state.fileList.sortedWith(compareBy({ it.type != "directory" }, { it.name }))
                    items(sorted) { node ->
                        TextButton(onClick = {
                            scope.launch {
                                if (node.type == "directory") {
                                    state.browseLoading = true
                                    val next = if (state.browsePath.isBlank()) node.name else "${state.browsePath}/${node.name}"
                                    state.browsePath = next
                                    state.fileList = withContext(Dispatchers.IO) { client.listFiles(next) }
                                    state.browseLoading = false
                                } else {
                                    state.browseLoading = true
                                    state.openedFile = withContext(Dispatchers.IO) { client.readFile(node.path) }
                                    state.browseLoading = false
                                }
                            }
                        }) {
                            Text(
                                (if (node.type == "directory") "📁 " else "📄 ") + node.name,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (node.ignored) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 本地持久化"会话 → 工作目录"映射。OpenCode 的会话列表未必回传 directory，
 * 这里在创建/打开会话时落盘，确保历史对话能还原当时绑定的工作目录（而非统一显示 Workspace）。
 */
private object SessionDirectoryStore {
    private const val FILE = "session-dirs.json"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)
    private fun load(ctx: Context): MutableMap<String, String> {
        val f = file(ctx)
        if (!f.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(f.readText())
            val m = mutableMapOf<String, String>()
            obj.keys().forEach { m[it] = obj.optString(it) }
            m
        }.getOrDefault(mutableMapOf())
    }
    private fun save(ctx: Context, m: Map<String, String>) {
        runCatching {
            val obj = JSONObject()
            m.forEach { (k, v) -> obj.put(k, v) }
            file(ctx).writeText(obj.toString())
        }
    }
    fun get(ctx: Context, id: String): String? = load(ctx)[id]?.takeIf { it.isNotBlank() }
    fun set(ctx: Context, id: String, dir: String) {
        val m = load(ctx).also { it[id] = dir }
        save(ctx, m)
    }
    fun fill(ctx: Context, sessions: List<ChatSession>): List<ChatSession> {
        val m = load(ctx)
        return sessions.map { s ->
            if (s.directory.isNullOrBlank() && m.containsKey(s.id)) s.copy(directory = m[s.id]) else s
        }
    }
}

private val BUILD_PHASES = listOf("提交", "准备", "编译", "安装", "拉起")

/** 从构建日志推导当前所处阶段，供步进器高亮。 */
private fun buildPhase(log: String, status: String): Int {
    val l = log.lowercase()
    return when {
        status == "PENDING" -> 0
        l.contains("拉起") || l.contains("已拉起") || l.contains("am start") -> 4
        l.contains("pm install") || l.contains("安装") || l.contains("复制 apk") || l.contains("apk 到") -> 3
        l.contains("assembledebug") || l.contains("编译") || l.contains("./gradlew") || l.contains("gradle") -> 2
        else -> 1
    }
}

/** 构建/任务进度作为对话流中的一条消息渲染（左对齐气泡，与助手消息统一风格）。 */
@Composable
private fun BuildConversationBlock(bt: BuildProgressInfo, onDismiss: () -> Unit) {
    val status = bt.status
    val isRunning = status == "RUNNING" || status == "PENDING"
    val color = when (status) {
        "SUCCEEDED" -> MaterialTheme.colorScheme.primary
        "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    var expanded by remember { mutableStateOf(false) }
    val logTail = bt.tailLines(16)
    val phase = buildPhase(bt.log, status)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                } else {
                    Text("●", color = color, fontSize = 13.sp)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "🔧 构建任务",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (isRunning) {
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(4.dp)) {
                        Text(if (expanded) "收起" else "日志", fontSize = 11.sp)
                    }
                } else {
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(4.dp)) {
                        Text("×", fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (status) {
                    "RUNNING" -> if (bt.exitCode < 0) "构建中…" else "收尾中…"
                    "PENDING" -> "排队中，等待宇宙 B 调度…"
                    "SUCCEEDED" -> "✓ 构建成功"
                    "FAILED" -> "✗ 构建失败"
                    "CANCELLED" -> "⏹ 已取消"
                    else -> status
                },
                fontSize = 12.sp, color = color,
            )
            // 阶段步进器：提交→准备→编译→安装→拉起
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BUILD_PHASES.forEachIndexed { i, label ->
                    val reached = i <= phase
                    val current = i == phase && isRunning
                    Surface(
                        color = if (current) {
                            color
                        } else if (reached) {
                            color.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        },
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 10.sp,
                            color = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (isRunning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                )
            } else {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (expanded) {
                Text(
                    if (isRunning) logTail else bt.log,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
            } else if (logTail.isNotBlank()) {
                Text(
                    logTail.lines().last(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<ChatSession>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onRename: (ChatSession, String) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onFork: (ChatSession) -> Unit,
) {
    var menuId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var confirmDeleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp),
    ) {
        if (sessions.size > 5) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索会话…", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(min = 36.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            )
        }
        val filtered = if (searchQuery.isBlank()) sessions else sessions.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                (it.directory ?: "").contains(searchQuery, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            Text("暂无会话", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        filtered.take(20).forEach { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(s.id) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        text = s.title,
                        color = if (s.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                    val dirLabel = ProjectRepository.displayName(s.directory ?: "")
                    if (dirLabel.isNotBlank()) {
                        Text(
                            text = "📁 $dirLabel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
                TextButton(onClick = { menuId = s.id }) { Text("⋯", fontSize = 16.sp) }
                DropdownMenu(expanded = menuId == s.id, onDismissRequest = { menuId = null }) {
                    DropdownMenuItem(text = { Text("复制会话") }, onClick = {
                        menuId = null
                        onFork(s)
                    })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                        menuId = null
                        renameTarget = s
                        renameText = s.title
                    })
                    DropdownMenuItem(text = { Text("删除") }, onClick = {
                        menuId = null
                        confirmDeleteTarget = s
                    })
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) onRename(target, name)
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    confirmDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${target.title}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    confirmDeleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTarget = null }) { Text("取消") } },
        )
    }
}

/** AI 打字指示器：三个圆点依次 pulsing */
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AI", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        TypingDot(infiniteTransition, 0)
        Spacer(Modifier.width(4.dp))
        TypingDot(infiniteTransition, 150)
        Spacer(Modifier.width(4.dp))
        TypingDot(infiniteTransition, 300)
    }
}

@Composable
private fun TypingDot(transition: androidx.compose.animation.core.InfiniteTransition, delayMs: Int) {
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = delayMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatMessage, isStreaming: Boolean = false) {
    val isUser = msg.role == "user"
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as? AndroidClipboardManager
    val fullText = msg.parts.joinToString("\n\n") {
        when (it) {
            is ChatPart.Text -> it.text
            is ChatPart.Tool -> {
                val info = getToolInfo(it.name, it.input)
                "⚙ ${it.name} · ${it.status}${if (info.isNotBlank()) " — $info" else ""}\n${it.output ?: it.errorText ?: ""}"
            }
            is ChatPart.Reasoning -> it.text
            is ChatPart.File -> "📄 ${it.path}"
            is ChatPart.Patch -> "📝 ${it.files.joinToString(", ")}"
            is ChatPart.SourceUrl -> "🔗 ${it.title ?: it.url}"
            is ChatPart.StepStart -> "--- ${it.stepName ?: "step"} ---"
            is ChatPart.Compaction -> "📦 ${it.message ?: "Context Compacted"}"
            is ChatPart.Agent -> "🤖 ${it.name}"
            is ChatPart.QuestionAnswer -> "❓ ${it.question}\n${it.answers.joinToString("\n") { a -> "→ $a" }}"
            is ChatPart.Other -> ""
        }
    }
    val timeLabel = msg.createdAt?.let {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = 320.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("message", fullText)) },
                )
                .padding(10.dp),
        ) {
            if (msg.parts.isEmpty() && !isUser) {
                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            msg.parts.forEach { part -> PartView(part, isUser) }
            if (isStreaming) {
                Text("▍", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
            msg.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            if (timeLabel != null) {
                Text(
                    timeLabel,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start),
                )
            }
        }
    }
}

@Composable
private fun PartView(part: ChatPart, isUser: Boolean) {
    val onColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    when (part) {
        is ChatPart.Text -> MarkdownText(part.text, onColor)
        is ChatPart.Reasoning -> MarkdownText(part.text, MaterialTheme.colorScheme.onSurfaceVariant)
        is ChatPart.Tool -> {
            ToolPartView(part)
        }
        is ChatPart.File -> {
            Text("📄 ${part.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatPart.Patch -> {
            Text("📝 ${part.files.size} file(s): ${part.files.joinToString(", ")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatPart.SourceUrl -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text("🔗 ", fontSize = 11.sp)
                Text(part.title ?: part.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        is ChatPart.StepStart -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                if (part.stepName != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(part.stepName, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        is ChatPart.Compaction -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.width(8.dp))
                Text("📦 ${part.message ?: "Context Compacted"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        is ChatPart.Agent -> {
            Text("🤖 ${part.name}${if (part.description != null) " — ${part.description}" else ""}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatPart.QuestionAnswer -> {
            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("❓ ${part.question}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                part.answers.forEach { ans ->
                    Text("  → $ans", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        is ChatPart.Other -> {}
    }
}

/** 轻量 Markdown：支持 ```代码块```（带复制）、**粗体**、`行内代码`、~~删除线~~、> 引用、| 表格、- [ ] 任务列表、![图片]。过滤 <think> 思维链。 */
private val RE_THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
private val RE_LANG_ID = Regex("^[a-zA-Z0-9+#-]+\$")

@Composable
private fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color) {
    val cleaned = text.replace(RE_THINK, "")
    val segments = cleaned.split("```")
    val hasUnclosed = segments.size % 2 == 0
    segments.forEachIndexed { idx, seg ->
        if (idx % 2 == 1 && idx < segments.size - (if (hasUnclosed) 1 else 0)) {
            val lines = seg.split("\n")
            val lang = lines.firstOrNull()?.takeIf { it.isNotBlank() && it.contains(RE_LANG_ID) }
            val body = if (lang != null) lines.drop(1) else lines
            CodeBlock(body.joinToString("\n").trim('\n'), mono = true, lang = lang)
        } else if (seg.isNotBlank()) {
            val lines = seg.trim('\n').split("\n")
            var i = 0
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                when {
                    // 表格：连续 | 开头的行
                    trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                        val tableRows = mutableListOf<String>()
                        while (i < lines.size && lines[i].trim().let { it.startsWith("|") && it.endsWith("|") }) {
                            tableRows.add(lines[i].trim())
                            i++
                        }
                        TableBlock(tableRows, color)
                        continue
                    }
                    // 引用块
                    trimmed.startsWith("> ") || trimmed == ">" -> {
                        val quoteLines = mutableListOf<String>()
                        while (i < lines.size && lines[i].trim().let { it.startsWith("> ") || it == ">" }) {
                            quoteLines.add(lines[i].trim().removePrefix("> ").trim())
                            i++
                        }
                        BlockquoteBlock(quoteLines.joinToString("\n"), color)
                        continue
                    }
                    // 标题
                    trimmed.startsWith("# ") -> Text(trimmed.removePrefix("# "), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
                    trimmed.startsWith("## ") -> Text(trimmed.removePrefix("## "), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
                    trimmed.startsWith("### ") -> Text(trimmed.removePrefix("### "), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
                    // 任务列表
                    trimmed.matches(Regex("^- \\[[ x]\\]\\s+.*")) -> {
                        val done = trimmed[3] == 'x'
                        val content = trimmed.substringAfter("] ").trim()
                        val displayText = if (done) "~~$content~~" else content
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                            Text(if (done) "☑" else "☐", fontSize = 13.sp, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            InlineText(displayText, color)
                        }
                    }
                    // 无序列表
                    trimmed.matches(Regex("^[-*]\\s+.*")) -> InlineText("  \u2022 ${trimmed.replaceFirst(Regex("^[-*]\\s+"), "")}", color)
                    // 有序列表
                    trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                        val num = trimmed.substringBefore(".")
                        InlineText("  $num. ${trimmed.replaceFirst(Regex("^\\d+\\.\\s+"), "")}", color)
                    }
                    // 水平线
                    trimmed.matches(Regex("^[-*_]{3,}\$")) -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    // 普通段落
                    else -> InlineText(trimmed, color)
                }
                i++
            }
        }
    }
}

@Composable
private fun TableBlock(rows: List<String>, color: androidx.compose.ui.graphics.Color) {
    if (rows.size < 2) return
    val header = rows[0].split("|").map { it.trim() }.filter { it.isNotBlank() }
    val dataRows = rows.drop(2).map { row ->
        row.split("|").map { it.trim() }.filter { it.isNotBlank() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        // header
        Row(modifier = Modifier.fillMaxWidth()) {
            header.forEachIndexed { idx, cell ->
                InlineText(cell, color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (idx < header.lastIndex) Spacer(Modifier.width(6.dp))
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
        // data rows
        dataRows.forEach { cells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                cells.forEachIndexed { idx, cell ->
                    InlineText(cell, color, modifier = Modifier.weight(1f))
                    if (idx < cells.lastIndex) Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun BlockquoteBlock(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 18.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = color.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InlineText(text: String, color: androidx.compose.ui.graphics.Color, fontWeight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val annotated = AnnotatedString.Builder()
    var cursor = 0
    // 合并 inline code、bold、strikethrough、image、link 五种模式
    val combined = Regex("\\*\\*.*?\\*\\*|~~.*?~~|`[^`]+`|!\\[([^]]*)]\\(([^)]+)\\)|\\[([^]]+)]\\(([^)]+)\\)")
    for (m in combined.findAll(text)) {
        if (m.range.first > cursor) annotated.append(text.substring(cursor, m.range.first))
        val tok = m.value
        when {
            tok.startsWith("**") -> annotated.append(AnnotatedString(tok.removeSurrounding("**"), spanStyle = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)))
            tok.startsWith("~~") -> annotated.append(AnnotatedString(tok.removeSurrounding("~~"), spanStyle = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)))
            tok.startsWith("`") -> annotated.append(AnnotatedString(tok.removeSurrounding("`"), spanStyle = androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace)))
            tok.startsWith("![") -> {
                val alt = m.groupValues[1]
                val url = m.groupValues[2]
                annotated.append(AnnotatedString("[$alt]($url)", spanStyle = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                )))
            }
            tok.startsWith("[") -> {
                val display = m.groupValues[3]
                annotated.append(AnnotatedString(display, spanStyle = androidx.compose.ui.text.SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                )))
            }
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) annotated.append(text.substring(cursor))
    Text(annotated.toAnnotatedString(), color = color, fontSize = 14.sp, fontWeight = fontWeight, modifier = modifier)
}

/** 根据工具名和 input 参数提取摘要信息（文件名/命令/路径等）。 */
private fun getToolInfo(name: String, input: Map<String, Any?>): String {
    return when (name) {
        "edit", "write", "read", "delete" -> {
            val fp = input["filePath"] as? String ?: input["path"] as? String ?: ""
            fp.ifBlank { "" }
        }
        "shell", "bash" -> {
            val cmd = input["command"] as? String ?: input["cmd"] as? String ?: ""
            if (cmd.length > 60) cmd.take(57) + "..." else cmd
        }
        "glob", "grep" -> {
            val pattern = input["pattern"] as? String ?: input["query"] as? String ?: ""
            val path = input["path"] as? String ?: ""
            listOfNotNull(pattern.takeIf { it.isNotBlank() }, path.takeIf { it.isNotBlank() }).joinToString(" in ")
        }
        else -> ""
    }
}

@Composable
private fun CodeBlock(code: String, mono: Boolean, maxLines: Int = 8, lang: String? = null) {
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as? AndroidClipboardManager
    var expanded by remember { mutableStateOf(false) }
    val totalLines = code.lines().size
    val isOverMax = totalLines > maxLines || code.length > 800
    val shown = if (expanded || !isOverMax) {
        code
    } else if (totalLines > maxLines) {
        code.lines().take(maxLines).joinToString("\n") + "\n…（共 $totalLines 行，点击展开）"
    } else {
        code.take(800) + "\n…（点击展开全文）"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(lang ?: "代码", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                if (isOverMax) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                }
                TextButton(onClick = { clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("code", code)) }) { Text("复制") }
            }
        }
        Text(
            shown,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 工具专用渲染器：按工具名选择专用 UI 组件。 */
@Composable
private fun ToolPartView(part: ChatPart.Tool) {
    val statusColor = when (part.status) {
        "completed" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var toolExpanded by remember { mutableStateOf(false) }
    val statusIcon = when (part.status) {
        "completed" -> "✓"
        "error" -> "✗"
        "pending" -> "◌"
        else -> "●"
    }

    Column(modifier = Modifier.padding(vertical = 2.dp).clickable { toolExpanded = !toolExpanded }) {
        when (part.name) {
            "bash", "shell" -> BashToolView(part, statusColor, statusIcon, toolExpanded)
            "edit" -> EditToolView(part, statusColor, statusIcon, toolExpanded)
            "write" -> WriteToolView(part, statusColor, statusIcon, toolExpanded)
            "read" -> ReadToolView(part, statusColor, statusIcon, toolExpanded)
            "glob", "grep", "list" -> SearchToolView(part, statusColor, statusIcon, toolExpanded)
            "websearch", "codesearch" -> WebSearchToolView(part, statusColor, statusIcon, toolExpanded)
            "webfetch" -> WebFetchToolView(part, statusColor, statusIcon, toolExpanded)
            "task" -> TaskToolView(part, statusColor, statusIcon, toolExpanded)
            "todowrite" -> TodoToolView(part, statusColor, statusIcon, toolExpanded)
            "question" -> QuestionToolView(part, statusColor, statusIcon, toolExpanded)
            else -> GenericToolView(part, statusColor, statusIcon, toolExpanded)
        }
    }
}

// ── Bash / Shell ──
@Composable
private fun BashToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val cmd = part.input["command"] as? String ?: part.input["cmd"] as? String ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ bash", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (cmd.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(
                if (cmd.length > 50) cmd.take(47) + "..." else cmd,
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        if (cmd.isNotBlank()) {
            CodeBlock("\$ $cmd", mono = true, lang = "bash")
        }
        part.output?.let { CodeBlock(it, mono = true, lang = "output", maxLines = 30) }
        part.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

// ── Edit ──
@Composable
private fun EditToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val fp = part.input["filePath"] as? String ?: ""
    val dir = fp.substringBeforeLast('/')
    val file = fp.substringAfterLast('/')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ edit", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (file.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(file, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (dir.isNotBlank()) {
        Text("  $dir", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { CodeBlock(it, mono = true, lang = "diff", maxLines = 30) }
        part.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

// ── Write ──
@Composable
private fun WriteToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val fp = part.input["filePath"] as? String ?: ""
    val file = fp.substringAfterLast('/')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ write", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (file.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(file, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { CodeBlock(it, mono = true, maxLines = 30) }
        part.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

// ── Read ──
@Composable
private fun ReadToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val fp = part.input["filePath"] as? String ?: ""
    val offset = part.input["offset"] as? Int
    val limit = part.input["limit"] as? Int
    val file = fp.substringAfterLast('/')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ read", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (file.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(file, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (offset != null || limit != null) {
            Spacer(Modifier.width(4.dp))
            Text("${offset ?: 0}–${limit ?: "?"}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { CodeBlock(it, mono = true, maxLines = 30) }
    }
}

// ── Glob / Grep / List ──
@Composable
private fun SearchToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val pattern = part.input["pattern"] as? String ?: part.input["query"] as? String ?: ""
    val path = part.input["path"] as? String ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ ${part.name}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (pattern.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(pattern, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (path.isNotBlank()) {
        Text("  in $path", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1)
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
    }
}

// ── WebSearch / CodeSearch ──
@Composable
private fun WebSearchToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val query = part.input["query"] as? String ?: part.input["search"] as? String ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ ${part.name}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (query.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text("\"$query\"", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
    }
}

// ── WebFetch ──
@Composable
private fun WebFetchToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val url = part.input["url"] as? String ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ fetch", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (url.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(url, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
    }
}

// ── Task (子 agent) ──
@Composable
private fun TaskToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val desc = part.input["description"] as? String ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ task", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (desc.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
    }
}

// ── TodoWrite (任务列表) ──
@Composable
private fun TodoToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ todo", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
        // 也显示 input 中的 todo 项
        val todos = part.input["todos"] as? List<*>
        todos?.forEach { item ->
            @Suppress("UNCHECKED_CAST")
            val todo = item as? Map<String, Any?> ?: return@forEach
            val content = todo["content"] as? String ?: ""
            val todoStatus = todo["status"] as? String ?: "pending"
            val done = todoStatus == "completed"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                Text(if (done) "☑" else "☐", fontSize = 12.sp, color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                InlineText(content, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ── Question (追问结果) ──
@Composable
private fun QuestionToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ question", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
    }
}

// ── Generic (默认) ──
@Composable
private fun GenericToolView(part: ChatPart.Tool, statusColor: Color, statusIcon: String, expanded: Boolean) {
    val summary = getToolInfo(part.name, part.input)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$statusIcon ", fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
        Text("⚙ ${part.name}", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
        if (summary.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (expanded) {
        Spacer(Modifier.height(4.dp))
        if (part.input.isNotEmpty()) {
            val inputText = part.input.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            CodeBlock(inputText, mono = true, maxLines = 10)
        }
        part.output?.let { MarkdownText(it, MaterialTheme.colorScheme.onSurface) }
        part.errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    }
}

private fun splitModel(model: String): Pair<String?, String?> {
    val idx = model.indexOf('/')
    return if (idx <= 0) null to null else model.substring(0, idx) to model.substring(idx + 1)
}

/** 判断消息是否包含构建意图（用户明确要求编译/打包/安装到手机）。 */
private fun lastUserMessageHasBuildIntent(msg: ChatMessage): Boolean {
    val text = msg.parts.joinToString(" ") { (it as? ChatPart.Text)?.text ?: "" }
    if (text.isBlank()) return false
    return text.contains(Regex("构建|编译|打包|出\\s*apk|安装到手机|build|compile|\\bapk\\b", RegexOption.IGNORE_CASE))
}

/** 从用户消息里提取构建项目名（构建 xxx / build xxx），默认 MyAndroidProject。 */
private fun extractBuildProject(msg: ChatMessage): String {
    val text = msg.parts.joinToString(" ") { (it as? ChatPart.Text)?.text ?: "" }
    val m = Regex("(?:构建|编译|build)\\s+(\\S+)", RegexOption.IGNORE_CASE).find(text)
    val raw = m?.groupValues?.getOrNull(1) ?: return "MyAndroidProject"
    return raw.trim('/').substringAfterLast('/').ifBlank { "MyAndroidProject" }
}
