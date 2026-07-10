package com.aidev.six

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Immutable
data class SftpConnection(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val label: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    val authType: AuthType = AuthType.PASSWORD,
)

enum class AuthType { PASSWORD, KEY }

@Immutable
data class TransferTask(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val remotePath: String = "",
    val localPath: String = "",
    val direction: TransferDirection = TransferDirection.DOWNLOAD,
    val progress: Float = 0f,
    val status: TransferStatus = TransferStatus.PENDING,
)

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { PENDING, ACTIVE, DONE, FAILED }

object SFtpState {
    var selectedConnection by mutableStateOf<SftpConnection?>(null)
    val connections = mutableStateListOf<SftpConnection>()
    val transferQueue = mutableStateListOf<TransferTask>()

    fun addConnection(conn: SftpConnection) { connections.add(conn) }
    fun removeConnection(conn: SftpConnection) { connections.remove(conn) }
    fun enqueue(task: TransferTask) { transferQueue.add(task) }
    fun clearDone() { transferQueue.removeAll { it.status == TransferStatus.DONE } }
}
