package com.aidev.six.ui.components

import android.app.Activity
import android.widget.Toast
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing
import com.aidev.six.ui.theme.sessionColor
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabChip(
    title: String,
    aiSession: Boolean,
    active: Boolean,
    locked: Boolean,
    color: String = "",
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeClose: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val thresholdPx = with(LocalDensity.current) { 18.dp.toPx() }
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { pendingAction }.collect { action ->
            if (action != null) {
                action()
                pendingAction = null
            }
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.button))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .pointerInput(thresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    swipeOffset = 0f
                    var totalDy = 0f
                    var longPressFired = false
                    val longPressMs = viewConfiguration.longPressTimeoutMillis.toLong()

                    val firstEvent = withTimeoutOrNull(longPressMs) {
                        awaitPointerEvent()
                    }

                    if (firstEvent == null) {
                        longPressFired = true
                        activity.window?.decorView?.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS
                        )
                        pendingAction = { onLongClick() }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                        }
                    } else {
                        var event = firstEvent
                        while (true) {
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dy = change.positionChange().y
                            if (dy != 0f) {
                                change.consume()
                                totalDy += dy
                                swipeOffset = totalDy
                            }
                            event = awaitPointerEvent()
                        }
                    }

                    swipeOffset = 0f
                    when {
                        totalDy >= thresholdPx -> {
                            if (locked) {
                                Toast.makeText(activity, "\u5DF2\u9501\u5B9A\uFF0C\u4E0A\u6ED1\u89E3\u9501", Toast.LENGTH_SHORT).show()
                            } else {
                                pendingAction = { onSwipeClose() }
                            }
                        }
                        totalDy <= -thresholdPx -> {
                            pendingAction = { onToggleLock() }
                        }
                        !longPressFired -> pendingAction = { onClick() }
                    }
                }
            }
            .offset { IntOffset(0, (swipeOffset * 0.3f).roundToInt()) }
            .graphicsLayer { alpha = 1f - (kotlin.math.abs(swipeOffset) / thresholdPx).coerceIn(0f, 0.25f) }
            .then(
                if (active) {
                    val c = MaterialTheme.colorScheme.primary
                    Modifier.drawBehind {
                        drawLine(c, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
                    }
                } else Modifier
            )
            .padding(horizontal = Spacing.s8, vertical = Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (aiSession) "AI-$title" else title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (locked) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = "\uD83D\uDD12",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (color.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(sessionColor(color))
            )
        }
    }
}
