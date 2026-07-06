package com.aidev.four.ui.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Immutable
data class AuditItem(
    val category: String,
    val name: String,
    val passed: Boolean,
    val detail: String,
    val fixCommand: String? = null,
)

@Stable
class SecurityAuditUiState(private val rootfs: File, private val homeDir: File) {

    var results by mutableStateOf<List<AuditItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    suspend fun runAudit() {
        loading = true
        results = emptyList()
        val items = withContext(Dispatchers.IO) {
            val list = mutableListOf<AuditItem>()
            list.addAll(auditFilePermissions(rootfs))
            list.addAll(auditSshSecurity(homeDir))
            list.addAll(auditSensitiveFiles(homeDir, rootfs))
            list
        }
        results = items
        loading = false
    }

    private fun auditFilePermissions(rootfs: File): List<AuditItem> {
        val items = mutableListOf<AuditItem>()
        if (!rootfs.exists()) {
            items.add(AuditItem("文件权限", "rootfs 存在性", false, "Ubuntu rootfs 未安装", "install-ubuntu"))
            return items
        }
        val openFiles = executeInRootfs(rootfs, "find /root -perm -0777 -type f 2>/dev/null")
        val lines = openFiles.lines().filter { it.isNotBlank() && it.startsWith("/") }
        if (lines.isEmpty()) {
            items.add(AuditItem("文件权限", "开放权限文件", true, "未发现 777/666 权限的文件"))
        } else {
            items.add(AuditItem("文件权限", "开放权限文件", false, "发现 ${lines.size} 个权限过于开放的文件"))
            lines.take(10).forEach { file ->
                val perm = executeInRootfs(rootfs, "stat -c '%a' $file 2>/dev/null").trim()
                items.add(AuditItem("文件权限", file, false, "权限: $perm", "chmod 644 $file"))
            }
            if (lines.size > 10) {
                items.add(AuditItem("文件权限", "更多文件...", false, "共 ${lines.size} 个，仅显示前 10 个"))
            }
        }
        return items
    }

    private fun auditSshSecurity(homeDir: File): List<AuditItem> {
        val items = mutableListOf<AuditItem>()
        val sshDir = File(homeDir, ".ssh")
        if (!sshDir.exists()) {
            items.add(AuditItem("SSH 安全", ".ssh 目录", true, "不存在（未配置 SSH）"))
            return items
        }
        val sshDirPerm = getPermissionsString(sshDir)
        val sshDirOk = sshDirPerm == "drwx------" || sshDirPerm == "drwxr-xr-x"
        items.add(AuditItem("SSH 安全", ".ssh 目录权限", sshDirOk,
            "当前: $sshDirPerm, 建议: 700 (drwx------)",
            if (!sshDirOk) "chmod 700 ~/.ssh" else null))

        val idRsa = File(sshDir, "id_rsa")
        if (idRsa.exists()) {
            val perm = getPermissionsString(idRsa)
            val ok = perm == "-rw-------"
            items.add(AuditItem("SSH 安全", "id_rsa 权限", ok,
                "当前: $perm, 建议: 600 (-rw-------)",
                if (!ok) "chmod 600 ~/.ssh/id_rsa" else null))
        } else {
            items.add(AuditItem("SSH 安全", "id_rsa", true, "不存在（未生成密钥）"))
        }

        val authKeys = File(sshDir, "authorized_keys")
        if (authKeys.exists()) {
            val perm = getPermissionsString(authKeys)
            val ok = perm == "-rw-------"
            items.add(AuditItem("SSH 安全", "authorized_keys 权限", ok,
                "当前: $perm, 建议: 600 (-rw-------)",
                if (!ok) "chmod 600 ~/.ssh/authorized_keys" else null))
        } else {
            items.add(AuditItem("SSH 安全", "authorized_keys", true, "不存在（未配置授权密钥）"))
        }
        return items
    }

    private fun auditSensitiveFiles(homeDir: File, rootfs: File): List<AuditItem> {
        val items = mutableListOf<AuditItem>()
        val bashHistory = File(homeDir, ".bash_history")
        if (bashHistory.exists()) {
            val lineCount = bashHistory.readLines().size
            items.add(AuditItem("敏感文件", ".bash_history", false,
                "存在，共 $lineCount 行历史记录",
                "rm -f ~/.bash_history && history -c"))
        } else {
            items.add(AuditItem("敏感文件", ".bash_history", true, "不存在"))
        }

        val netrc = File(homeDir, ".netrc")
        items.add(AuditItem("敏感文件", ".netrc", !netrc.exists(),
            if (netrc.exists()) "存在，可能包含明文凭据" else "不存在",
            if (netrc.exists()) "chmod 600 ~/.netrc" else null))

        val tmpDir = File(rootfs, "tmp")
        if (tmpDir.exists()) {
            val scripts = tmpDir.listFiles()?.filter {
                it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".py") || it.name.endsWith(".pl"))
            }?.take(10) ?: emptyList()
            if (scripts.isEmpty()) {
                items.add(AuditItem("敏感文件", "/tmp 可疑脚本", true, "未发现可疑脚本文件"))
            } else {
                items.add(AuditItem("敏感文件", "/tmp 可疑脚本", false,
                    "发现 ${scripts.size} 个脚本文件: ${scripts.joinToString(", ") { it.name }}"))
            }
        } else {
            items.add(AuditItem("敏感文件", "/tmp 目录", true, "不存在"))
        }
        return items
    }

    companion object {
        private fun executeInRootfs(rootfs: File, command: String): String {
            val shellOperators = Regex("[;|&`\$()]")
            if (command.contains(shellOperators)) return ""
            val cmdName = command.trim().split(Regex("\\s+")).firstOrNull() ?: return ""
            val whitelist = setOf("find", "stat", "ls", "cat", "id", "whoami", "uname", "df", "du", "chmod", "chown", "grep", "wc", "head", "tail")
            if (cmdName !in whitelist) return ""
            var process: Process? = null
            return try {
                val mapped = command.replace("/root", rootfs.resolve("root").absolutePath).replace("/tmp", rootfs.resolve("tmp").absolutePath)
                process = Runtime.getRuntime().exec(arrayOf("sh", "-c", mapped))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
                output
            } catch (_: Exception) { "" } finally {
                process?.destroy()
            }
        }

        private fun getPermissionsString(file: File): String {
            var proc: Process? = null
            return try {
                proc = Runtime.getRuntime().exec(arrayOf("ls", "-la", "-d", file.absolutePath))
                val output = proc.inputStream.bufferedReader().use { it.readLine() } ?: return "????------"
                if (!proc.waitFor(15, TimeUnit.SECONDS)) proc.destroyForcibly()
                val parts = output.split(Regex("\\s+"))
                if (parts.size >= 1) parts[0] else "????------"
            } catch (_: Exception) { "????------" } finally {
                proc?.destroy()
            }
        }
    }
}
