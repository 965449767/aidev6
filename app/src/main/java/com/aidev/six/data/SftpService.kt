package com.aidev.six.data

import androidx.compose.runtime.Immutable
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import android.util.Log
import java.io.File

@Immutable
data class SftpFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

class SftpService(
    private val jsch: JSch = JSch(),
) {
    fun connect(
        host: String,
        port: Int = 22,
        user: String,
        password: String? = null,
        keyPath: String? = null,
    ): Result<Session> = runCatching {
        val session = jsch.getSession(user, host, port)
        if (!password.isNullOrEmpty()) session.setPassword(password)
        if (!keyPath.isNullOrEmpty()) jsch.addIdentity(keyPath)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(15000)
        session
    }

    fun disconnect(session: Session) {
        try {
            if (session.isConnected) session.disconnect()
        } catch (_: Exception) {}
    }

    fun listFiles(session: Session, path: String): Result<List<SftpFile>> = runCatching {
        val channel = session.openChannel("sftp") as? ChannelSftp
            ?: return@runCatching throw Exception("SFTP channel creation failed")
        channel.connect()
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
            entries
        } finally {
            runCatching { channel.disconnect() }
        }
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry ->
                val attrs = entry.attrs
                SftpFile(
                    name = entry.filename,
                    isDirectory = attrs.isDir(),
                    size = attrs.getSize(),
                    lastModified = attrs.getMTime() * 1000L,
                )
            }
            .sortedWith(compareByDescending<SftpFile> { it.isDirectory }.thenBy { it.name })
    }

    fun upload(
        session: Session,
        localPath: String,
        remotePath: String,
        monitor: SftpProgressMonitor? = null,
    ): Result<Boolean> = runCatching {
        val channel = session.openChannel("sftp") as? ChannelSftp
            ?: return@runCatching throw Exception("SFTP channel creation failed")
        channel.connect()
        try {
            channel.put(localPath, remotePath, monitor)
            true
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    fun download(
        session: Session,
        remotePath: String,
        localPath: String,
        monitor: SftpProgressMonitor? = null,
    ): Result<Boolean> = runCatching {
        val localFile = File(localPath)
        localFile.parentFile?.mkdirs()
        val channel = session.openChannel("sftp") as? ChannelSftp
            ?: return@runCatching throw Exception("SFTP channel creation failed")
        channel.connect()
        try {
            channel.get(remotePath, localPath, monitor)
            true
        } finally {
            runCatching { channel.disconnect() }
        }
    }
}
