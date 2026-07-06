package com.aidev.four.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
data class SwipeAction(
    val icon: ImageVector? = null,
    val label: String,
    val color: Color,
    val onClick: () -> Unit,
)

@Composable
fun SwipeableRow(
    maxSwipePx: Float,
    actionThresholdPx: Float,
    peekThresholdPx: Float,
    rightActions: List<SwipeAction>,
    leftPeekContent: @Composable () -> Unit,
    onLeftPeekTrigger: () -> Unit,
    onLeftSwipeAuto: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val swipeAnim = remember { Animatable(0f) }
    var showRightActions by remember { mutableStateOf(false) }
    var showLeftPeek by remember { mutableStateOf(false) }

    fun snapTo(target: Float) {
        scope.launch {
            swipeAnim.animateTo(target, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showRightActions && rightActions.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rightActions.forEach { action ->
                    Box(
                        modifier = Modifier
                            .size(42.dp).padding(2.dp)
                            .clip(CircleShape).background(action.color)
                            .clickable { action.onClick(); snapTo(0f); showRightActions = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = action.label,
                                tint = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Text(action.label, style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (showLeftPeek) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart).padding(start = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                leftPeekContent()
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(swipeAnim.value.roundToInt(), 0) }
                .then(
                    if (swipeAnim.value < -peekThresholdPx) {
                        Modifier.graphicsLayer { alpha = 1f + swipeAnim.value / maxSwipePx * 0.3f }
                    } else if (swipeAnim.value > peekThresholdPx) {
                        Modifier.graphicsLayer { alpha = 1f - swipeAnim.value / maxSwipePx * 0.2f }
                    } else Modifier
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = swipeAnim.value
                            when {
                                v < -actionThresholdPx -> {
                                    onLeftSwipeAuto()
                                    showRightActions = false; showLeftPeek = false
                                    snapTo(0f)
                                }
                                v < -peekThresholdPx -> {
                                    showRightActions = true; showLeftPeek = false
                                    snapTo(-maxSwipePx)
                                }
                                v > peekThresholdPx -> {
                                    onLeftPeekTrigger()
                                    showLeftPeek = false; showRightActions = false
                                    snapTo(0f)
                                }
                                else -> { showRightActions = false; showLeftPeek = false; snapTo(0f) }
                            }
                        },
                        onDragCancel = { showRightActions = false; showLeftPeek = false; snapTo(0f) },
                    ) { _, dragAmount ->
                        scope.launch {
                            val newVal = (swipeAnim.value + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                            swipeAnim.snapTo(newVal)
                        }
                    }
                },
        ) {
            content()
        }
    }
}
