package com.aidev.six.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

enum class PanelType { SETTINGS }

private const val SWIPE_THRESHOLD_FRACTION = 0.15f

@Composable
fun EdgeSwipePanel(
    enabled: Boolean,
    currentPanel: PanelType?,
    onPanelOpen: (PanelType) -> Unit,
    onPanelClose: () -> Unit,
    imeActive: Boolean = false,
    excludeTop: Dp = 0.dp,
    settingsContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomPad = 120.dp

    Box(modifier = modifier.fillMaxSize()) {
        content()

        if (currentPanel != null) {
            BackHandler(enabled = true) { onPanelClose() }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { onPanelClose() },
                )

                AnimatedContent(
                    targetState = currentPanel,
                    transitionSpec = {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { -it }
                    },
                    label = "panel_content",
                ) { panel ->
                    when (panel) {
                        PanelType.SETTINGS -> {
                            Surface(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .widthIn(min = 280.dp)
                                    .fillMaxWidth(0.85f)
                                    .align(Alignment.CenterStart),
                                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                                tonalElevation = 8.dp,
                                color = MaterialTheme.colorScheme.surface,
                            ) { settingsContent() }
                        }
                    }
                }
            }
        }

        if (enabled && !imeActive && currentPanel == null) {
            EdgeGestureZone(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(48.dp)
                    .fillMaxHeight()
                    .padding(top = excludeTop, bottom = bottomPad),
                targetPanel = PanelType.SETTINGS,
                currentPanel = currentPanel,
                onPanelOpen = onPanelOpen,
                onPanelClose = onPanelClose,
            )
        }
    }
}

@Composable
private fun EdgeGestureZone(
    modifier: Modifier = Modifier,
    targetPanel: PanelType,
    currentPanel: PanelType?,
    onPanelOpen: (PanelType) -> Unit,
    onPanelClose: () -> Unit,
) {
    var totalDrag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier.pointerInput(currentPanel) {
            val displayWidth = size.width.toFloat()
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (abs(totalDrag) > displayWidth * SWIPE_THRESHOLD_FRACTION) {
                        if (currentPanel != null) onPanelClose()
                        else onPanelOpen(targetPanel)
                    }
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f },
            ) { _, dragAmount ->
                totalDrag += dragAmount
            }
        },
    )
}

@Composable
fun PanelIndicatorDots(
    currentPanel: PanelType?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IndicatorDot(active = currentPanel == PanelType.SETTINGS)
    }
}

@Composable
private fun IndicatorDot(active: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            ),
    )
}
