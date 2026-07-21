package com.aidev.six.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.ui.components.TabChip
import com.aidev.six.ui.theme.Spacing
import com.aidev.six.ui.theme.TabColorPickerDialog

/**
 * 终端会话标签栏：显示所有会话标签，支持切换、关闭、重命名、改色、克隆。
 * 从 TerminalPanel.kt 中拆分出来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalTabs(page: EmbeddedTerminalPage, modifier: Modifier = Modifier) {
    val sm = page.sessionManager
    val sessions = sm.sessions
    val currentId = sm.currentSession?.id
    var menuSessionId by remember { mutableStateOf<Int?>(null) }
    var colorPickerId by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colorScheme.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sessions.forEach { session ->
            key(session.id) {
                TabChip(
                    title = session.title,
                    aiSession = session.aiSession,
                    active = session.id == currentId,
                    locked = session.locked,
                    color = sm.getSessionColor(session.id),
                    onClick = { sm.switchSession(session.id) },
                    onLongClick = {
                        sm.switchSession(session.id)
                        menuSessionId = session.id
                    },
                    onSwipeClose = { sm.closeSession(session.id) },
                    onToggleLock = { sm.toggleLock(session.id) },
                )
            }
        }
    }

    menuSessionId?.let { id ->
        val session = sessions.find { it.id == id }
        if (session != null) {
            AlertDialog(
                onDismissRequest = { menuSessionId = null },
                title = { Text("会话 ${session.title}") },
                text = {
                    Column {
                        listOf(
                            Triple("重命名", "修改会话标签文本") { menuSessionId = null; sm.renameCurrentSession() },
                            Triple("更改颜色", "设置标签颜色标识") { menuSessionId = null; colorPickerId = id },
                            Triple("从当前目录打开新会话", "克隆并 cd 到当前目录") { menuSessionId = null; sm.cloneSession() },
                        ).forEach { (title, desc, onClick) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onClick)
                                    .padding(vertical = Spacing.s12, horizontal = Spacing.s4),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(title, style = MaterialTheme.typography.bodyMedium)
                                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { menuSessionId = null }) { Text("取消") } },
            )
        }
    }

    colorPickerId?.let { id ->
        TabColorPickerDialog(
            currentColor = sm.getSessionColor(id),
            onSelect = { color ->
                sm.setSessionColor(id, color)
                colorPickerId = null
            },
            onDismiss = { colorPickerId = null },
        )
    }
}
