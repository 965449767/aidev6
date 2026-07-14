package com.aidev.six.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidev.six.chat.ChatMessage
import com.aidev.six.chat.ChatPart
import com.aidev.six.chat.ChatSession
import com.aidev.six.chat.OpenCodeClient
import com.aidev.six.chat.OpenCodeServerManager
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun AiChatPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun client() = OpenCodeClient("http://127.0.0.1:${OpenCodeServerManager.PORT}")

    fun refreshSessions() {
        val list = runCatching { client().listSessions() }.getOrDefault(emptyList())
        sessions = list
        if (selectedId == null && list.isNotEmpty()) selectedId = list.first().id
    }

    fun loadMessages() {
        val id = selectedId ?: return
        messages = runCatching { client().listMessages(id) }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) {
        val ok = OpenCodeServerManager.ensureRunning(context)
        if (!ok) {
            status = "OpenCode 未运行：${OpenCodeServerManager.lastDiagnostic}"
            return@LaunchedEffect
        }
        status = null
        refreshSessions()
        loadMessages()
    }
    LaunchedEffect(selectedId) { loadMessages() }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        scope.launch {
            busy = true
            val c = client()
            var sid = selectedId
            if (sid == null) {
                val s = c.createSession("aidev 对话")
                if (s == null) { status = "创建会话失败"; busy = false; return@launch }
                sid = s.id
                selectedId = sid
                refreshSessions()
            }
            val ok = c.sendPromptAsync(sid, text, null, null, null)
            if (!ok) { status = "发送失败"; busy = false; return@launch }
            input = ""
            repeat(40) {
                if (!isActive) return@repeat
                delay(1500)
                val msgs = c.listMessages(sid)
                val last = msgs.lastOrNull { it.role == "assistant" }
                val txt = last?.parts?.filterIsInstance<ChatPart.Text>()?.joinToString("\n") { it.text }?.trim()
                if (!txt.isNullOrBlank()) { messages = msgs; status = null; listState.scrollToItem(messages.size); return@repeat }
            }
            messages = c.listMessages(sid)
            busy = false
        }
    }

    fun newSession() {
        scope.launch {
            val s = client().createSession("aidev 对话")
            if (s != null) { selectedId = s.id; messages = emptyList(); refreshSessions() }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            if (busy) LinearProgressIndicator(Modifier.width(80.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = { newSession() }) { Text("新对话") }
        }
        if (sessions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                items(sessions, key = { it.id }) { s ->
                    FilterChip(
                        selected = s.id == selectedId,
                        onClick = { selectedId = s.id },
                        label = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(320.dp).padding(vertical = Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            items(messages, key = { it.id }) { m -> ChatBubble(m) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("给 AI 下写代码指令…") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
            )
            IconButton(onClick = { send() }, enabled = !busy && input.trim().isNotBlank()) {
                Icon(Icons.Rounded.Send, "发送", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radius.card))
                .background(bg)
                .padding(Spacing.s12),
        ) {
            val text = m.parts.filterIsInstance<ChatPart.Text>().joinToString("\n") { it.text }
            Text(
                text = text.ifBlank { "（无文本内容）" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace,
            )
        }
    }
}
