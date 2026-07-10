package com.aidev.six.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidev.six.*
import com.aidev.six.data.SftpFile
import com.aidev.six.data.SftpService
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SFtpDialog(onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val service = remember { SftpService() }
    val passwords = remember { mutableStateMapOf<String, String>() }

    var activeSession by remember { mutableStateOf<Session?>(null) }
    var currentPath by remember { mutableStateOf("/") }
    var fileList by remember { mutableStateOf<List<SftpFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun disconnect() {
        activeSession?.let { service.disconnect(it) }
        activeSession = null
        currentPath = "/"
        fileList = emptyList()
        errorMessage = null
    }

    fun loadDir(session: Session, path: String) {
        isLoading = true
        errorMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                service.listFiles(session, path)
            }
            result.onSuccess {
                fileList = it
                currentPath = path
            }.onFailure {
                errorMessage = "读取目录失败: ${it.message}"
            }
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = {
            disconnect()
            onDismiss()
        },
        title = { Text("SFTP 文件传输") },
        text = {
            Column {
                TabRow(tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("连接管理") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("传输队列") })
                }
                Spacer(Modifier.height(8.dp))
                when (tab) {
                    0 -> {
                        val session = activeSession
                        if (session != null) {
                            SftpFileBrowser(
                                currentPath = currentPath,
                                files = fileList,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                onEnterDir = { loadDir(session, it) },
                                onGoUp = {
                                    val parent = currentPath.trimEnd('/').substringBeforeLast('/')
                                    loadDir(session, parent.ifEmpty { "/" })
                                },
                                onDownload = { file ->
                                    val remote = "${currentPath.trimEnd('/')}/${file.name}"
                                    val local = "/sdcard/Download/${file.name}"
                                    val task = TransferTask(
                                        remotePath = remote,
                                        localPath = local,
                                        direction = TransferDirection.DOWNLOAD,
                                    )
                                    SFtpState.enqueue(task)
                                    scope.launch {
                                        var progress = 0f
                                        val monitor = TransferProgressMonitor { p ->
                                            progress = p
                                            val i = SFtpState.transferQueue.indexOfFirst { it.id == task.id }
                                            if (i >= 0) {
                                                SFtpState.transferQueue[i] = SFtpState.transferQueue[i].copy(
                                                    progress = p,
                                                    status = TransferStatus.ACTIVE,
                                                )
                                            }
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            service.download(session, remote, local, monitor)
                                        }
                                        val i = SFtpState.transferQueue.indexOfFirst { it.id == task.id }
                                        if (i >= 0) {
                                            SFtpState.transferQueue[i] = SFtpState.transferQueue[i].copy(
                                                status = if (result.isSuccess) TransferStatus.DONE else TransferStatus.FAILED,
                                                progress = if (result.isSuccess) 1f else progress,
                                            )
                                        }
                                    }
                                },
                                onDisconnect = { disconnect() },
                            )
                        } else {
                            SftpConnectionList(
                                passwords = passwords,
                                onConnect = { conn, password ->
                                    isLoading = true
                                    errorMessage = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            service.connect(conn.host, conn.port, conn.user, password)
                                        }
                                        result.onSuccess { session ->
                                            activeSession = session
                                            currentPath = "/"
                                            loadDir(session, "/")
                                        }.onFailure {
                                            errorMessage = "连接失败: ${it.message}"
                                        }
                                        isLoading = false
                                    }
                                },
                            )
                        }
                    }
                    1 -> SftpTransferQueue()
                }
                if (errorMessage != null && activeSession == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                disconnect()
                onDismiss()
            }) { Text("关闭") }
        },
    )
}

@Composable
private fun SftpConnectionList(
    passwords: MutableMap<String, String>,
    onConnect: (SftpConnection, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = SFtpState
    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { state.connections.add(SftpConnection()) }) { Text("+ 添加") }
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(state.connections, key = { it.id }) { conn ->
                var editing by remember { mutableStateOf(false) }
                if (editing) {
                    SftpConnectionForm(
                        conn = conn,
                        password = passwords[conn.id] ?: "",
                        onPasswordChange = { passwords[conn.id] = it },
                        onSave = { editing = false },
                        onDelete = {
                            state.removeConnection(conn)
                            editing = false
                        },
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(conn.label.ifBlank { conn.host.ifBlank { "新连接" } }) },
                        supportingContent = { Text("${conn.user}@${conn.host}:${conn.port}") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { editing = true }) { Text("编辑") }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = { onConnect(conn, passwords[conn.id]) }) { Text("连接") }
                            }
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SftpConnectionForm(
    conn: SftpConnection,
    password: String,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by remember { mutableStateOf(conn.label) }
    var host by remember { mutableStateOf(conn.host) }
    var port by remember { mutableStateOf(conn.port.toString()) }
    var user by remember { mutableStateOf(conn.user) }

    Column(modifier.padding(8.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("主机") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = {
                val idx = SFtpState.connections.indexOf(conn)
                if (idx >= 0) {
                    SFtpState.connections[idx] = conn.copy(
                        label = label,
                        host = host,
                        port = port.toIntOrNull() ?: 22,
                        user = user,
                    )
                }
                onSave()
            }) { Text("保存") }
        }
    }
}

@Composable
private fun SftpFileBrowser(
    currentPath: String,
    files: List<SftpFile>,
    isLoading: Boolean,
    errorMessage: String?,
    onEnterDir: (String) -> Unit,
    onGoUp: () -> Unit,
    onDownload: (SftpFile) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                currentPath,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDisconnect) { Text("断开") }
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
        }
        if (currentPath != "/") {
            TextButton(onClick = onGoUp, modifier = Modifier.fillMaxWidth()) { Text(".. 返回上级") }
        }
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(files, key = { it.name }) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = file.isDirectory) {
                            onEnterDir("${currentPath.trimEnd('/')}/${file.name}")
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (file.isDirectory) {
                                Text("目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(formatTimestamp(file.lastModified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!file.isDirectory) {
                        TextButton(onClick = { onDownload(file) }) { Text("下载") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatTimestamp(millis: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(millis))
    } catch (_: Exception) { "" }
}

@Composable
private fun SftpTransferQueue(modifier: Modifier = Modifier) {
    val queue = SFtpState.transferQueue
    if (queue.isEmpty()) {
        Text("暂无传输任务", modifier = modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier.heightIn(max = 320.dp)) {
        items(queue, key = { it.id }) { task ->
            ListItem(
                headlineContent = { Text(if (task.direction == TransferDirection.UPLOAD) "↑ ${task.localPath}" else "↓ ${task.remotePath}") },
                supportingContent = {
                    LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                },
                trailingContent = {
                    Text(when (task.status) {
                        TransferStatus.PENDING -> "等待"
                        TransferStatus.ACTIVE -> "传输中"
                        TransferStatus.DONE -> "完成"
                        TransferStatus.FAILED -> "失败"
                    })
                },
            )
            HorizontalDivider()
        }
    }
}

private class TransferProgressMonitor(
    private val onProgress: (Float) -> Unit,
) : SftpProgressMonitor {
    private var total = 0L
    private var transferred = 0L

    override fun init(op: Int, src: String, dest: String, max: Long) {
        total = max
    }

    override fun count(count: Long): Boolean {
        transferred += count
        onProgress(if (total > 0) transferred.toFloat() / total else 0f)
        return true
    }

    override fun end() {
        onProgress(1f)
    }
}
