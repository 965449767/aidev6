package com.aidev.six.ui.pages

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.navigation.LocalImeBottomPx
import com.aidev.six.terminal.TerminalCompletion
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

/**
 * 终端补全栏：显示路径补全建议 + IME 切换按钮。
 * 从 TerminalPanel.kt 中拆分出来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalCompletionBar(page: EmbeddedTerminalPage, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as Activity
    val suggestions by page.completionSnapshot

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(MaterialTheme.colorScheme.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s8, vertical = Spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (suggestions.isEmpty()) {
                HintChip(onClick = { page.completionEngine.focusTerminalInput() })
            } else {
                suggestions.take(8).forEach { item ->
                    CompletionChip(item = item, page = page, activity = activity)
                }
            }
        }
        ImeToggleButton(page = page, activity = activity)
    }
}

@Composable
private fun ImeToggleButton(page: EmbeddedTerminalPage, activity: Activity) {
    val imeVisible = LocalImeBottomPx.current > 0
    val color = if (imeVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clickable {
                val proxy = page.inputProxy
                if (proxy != null) {
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    if (imeVisible) {
                        imm.hideSoftInputFromWindow(proxy.windowToken, 0)
                    } else {
                        proxy.requestFocus()
                        imm.showSoftInput(proxy, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 0.dp)
            .height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\u2328",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun HintChip(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.button))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Radius.button))
            .height(24.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "点击输入框获取焦点",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompletionChip(item: TerminalCompletion, page: EmbeddedTerminalPage, activity: Activity, modifier: Modifier = Modifier) {
    val borderColor = when (item.kind) {
        "DIR" -> MaterialTheme.colorScheme.tertiary
        "HIDDEN_DIR", "HIDDEN_FILE" -> MaterialTheme.colorScheme.primary
        "FILE" -> MaterialTheme.colorScheme.outline
        "PIN" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.button))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(Radius.button))
            .height(24.dp)
            .combinedClickable(
                onClick = { page.completionEngine.applyCompletion(item) },
                onLongClick = { page.showCompletionMenu(activity, item) },
            )
            .padding(horizontal = 10.dp, vertical = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
