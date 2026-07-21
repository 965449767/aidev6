package com.aidev.six.ui.pages

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.BuildConfig
import com.aidev.six.ui.components.AppChip
import com.aidev.six.ui.theme.Spacing

/**
 * 终端顶部栏：版本号 + 新建会话/粘贴/性能/更多操作按钮。
 * 从 TerminalPanel.kt 中拆分出来，保持单一职责。
 */
@Composable
internal fun TerminalTopBar(
    activity: Activity,
    onNewSession: () -> Unit,
    onPaste: () -> Unit,
    onMore: () -> Unit,
    onTogglePerf: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        TopBarButton("+", onClick = onNewSession)
        Spacer(modifier = Modifier.width(Spacing.s4))
        TopBarButton("粘贴", onClick = onPaste)
        Spacer(modifier = Modifier.width(Spacing.s4))
        TopBarButton("性能", onClick = onTogglePerf)
        Spacer(modifier = Modifier.width(Spacing.s4))
        TopBarButton("更多", onClick = onMore)
    }
}

@Composable
private fun TopBarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppChip(text = label, onClick = onClick, modifier = modifier)
}
