package com.aidev.four.ui.pages

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.aidev.four.navigation.DialogType
import com.aidev.four.navigation.LocalDialogManager
import com.aidev.four.ui.components.AppActionRow
import com.aidev.four.ui.components.AppSectionHeader
import com.aidev.four.ui.components.AppSectionTitle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun ServerPanel(
    onExecuteCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dialogManager = LocalDialogManager.current

    val batteryIgnored = remember { batteryIgnored(context) }
    val taskCount = remember { taskCount(context) }
    val ubuntuInstalled = remember { ubuntuInstalled(context) }
    val opencodeInstalled = remember { opencodeInstalled(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AppSectionHeader("服务器中心", "移动 Linux 服务器状态与 AI 服务入口")

        Spacer(Modifier.height(8.dp))

        AppSectionTitle("运行状态")
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusRow("电池优化", if (batteryIgnored) "已忽略" else "受限制", batteryIgnored, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusRow("任务记录", "$taskCount 个", taskCount > 0, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusRow("Ubuntu", if (ubuntuInstalled) "可用" else "未安装", ubuntuInstalled, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusRow("OpenCode", if (opencodeInstalled) "可能已安装" else "未检测到", opencodeInstalled, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))

        AppSectionTitle("服务操作")
        AppActionRow("监听端口", "查看当前本地服务端口", onClick = { onExecuteCommand("list-listen-ports") })
        HorizontalDivider()
        AppActionRow("访问诊断", "检查 127.0.0.1 服务", onClick = { onExecuteCommand("check-local-server 3000") })
        HorizontalDivider()
        AppActionRow("任务中心", "查看服务日志和任务", onClick = { onExecuteCommand("aidev-agent-log") })
        HorizontalDivider()
        AppActionRow("SFTP 传输", "远程文件传输管理", onClick = { dialogManager.show(DialogType.SFtpTransfer) })

        Spacer(Modifier.height(8.dp))

        AppSectionTitle("AI 助手")
        Row(modifier = Modifier.fillMaxWidth()) {
            AppActionRow("安装 OpenCode", "调用官方安装入口", onClick = { onExecuteCommand("install-aitool") }, modifier = Modifier.weight(1f), compact = true)
            Spacer(Modifier.width(8.dp))
            AppActionRow("检测环境", "AI/Web 通信与工具链", onClick = { onExecuteCommand("check-dev-env\naidev-net-explain") }, modifier = Modifier.weight(1f), compact = true)
        }
        HorizontalDivider()
        AppActionRow("OpenCode 日志", "查看 OpenCode 任务输出", onClick = { onExecuteCommand("aidev-agent-log") })

        Spacer(Modifier.height(16.dp))

        AppSectionTitle("设计原则")
        InfoCard("服务类任务不依赖前台终端页面")
        InfoNote("用 task-run 启动下载、AI 后端、Web 前端和构建任务，从任务中心查看日志。")
        InfoNote("同机浏览器访问 127.0.0.1；局域网访问需服务监听 0.0.0.0，HyperOS 不能限制后台网络。")
        InfoNote("AI 能力逐步集中至此页面，避免继续散落在设置和命令菜单中。")

        Spacer(Modifier.height(24.dp))
    }
}

private fun batteryIgnored(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 23) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ubuntuInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/.aidev-rootfs-ready").exists()

private fun taskCount(context: Context): Int =
    File(context.filesDir, "home/tasks").listFiles { f -> f.name.endsWith(".meta") }?.size ?: 0

private fun opencodeInstalled(context: Context): Boolean =
    File(context.filesDir, "home/ubuntu-rootfs/root/.opencode/bin/opencode").exists() ||
        File(context.filesDir, "home/.opencode/bin/opencode").exists()

@Composable
private fun StatusRow(label: String, value: String, positive: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

@Composable
private fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}
