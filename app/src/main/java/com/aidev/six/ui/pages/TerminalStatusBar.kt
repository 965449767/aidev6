package com.aidev.six.ui.pages

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.ui.theme.Spacing
import com.aidev.six.ui.theme.themeAbbreviation
import com.aidev.six.ui.theme.themeColor

@Composable
internal fun TerminalStatusBar(
    page: EmbeddedTerminalPage,
    fontSpClipped: Float,
    currentThemeKey: String = "classic-dark",
    modifier: Modifier = Modifier,
    onFontSizeDragStart: () -> Unit = {},
    onFontSizeDrag: (Float) -> Unit = {},
    onFontSizeDragEnd: () -> Unit = {},
    onThemeDragStart: () -> Unit = {},
    onThemeDrag: (Float) -> Unit = {},
    onThemeDragEnd: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val activity = LocalContext.current as Activity
    val btnWidth = 48.dp
    val themeColor = remember(currentThemeKey) { themeColor(currentThemeKey) }
    val themeAbbr = remember(currentThemeKey) { themeAbbreviation(currentThemeKey) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = Spacing.s16, end = Spacing.s16, top = Spacing.s8, bottom = Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${fontSpClipped.toInt()}sp",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .width(btnWidth)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onFontSizeDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onFontSizeDrag(dragAmount.x)
                        },
                        onDragEnd = { onFontSizeDragEnd() },
                        onDragCancel = { onFontSizeDragEnd() },
                    )
                },
        )
        Text(
            text = themeAbbr,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = themeColor,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .width(btnWidth)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onThemeDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onThemeDrag(dragAmount.x)
                        },
                        onDragEnd = { onThemeDragEnd() },
                        onDragCancel = { onThemeDragEnd() },
                    )
                },
        )
        Text(
            text = "T",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (page.tuiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(btnWidth).clickable { page.toggleTuiMode(activity) },
        )
        Text(
            text = "?",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(btnWidth).clickable { onHelp() },
        )
    }
}
