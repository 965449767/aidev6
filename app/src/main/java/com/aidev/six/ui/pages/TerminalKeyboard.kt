package com.aidev.six.ui.pages

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.terminal.EmbeddedVirtualKey
import com.aidev.six.ui.theme.Radius
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val KEYBOARD_HEIGHT = 88
private const val KEYS_PER_ROW = 8

/**
 * 终端虚拟键盘：显示可自定义的虚拟按键行，支持点击、长按、滑动手势。
 * 从 TerminalPanel.kt 中拆分出来。
 */
@Composable
internal fun TerminalKeyboard(page: EmbeddedTerminalPage, modifier: Modifier = Modifier) {
    val km = page.keyboardManager
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var swapPair by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(swapPair) {
        if (swapPair.isNotEmpty()) {
            delay(410L)
            swapPair = emptySet()
        }
    }

    val kv = km.keyVersion
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(KEYBOARD_HEIGHT.dp)
            .background(if (km.isRearranging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        km.keyVersion
        val keys = km.getOrderedKeys()
        keys.chunked(KEYS_PER_ROW).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowKeys.forEach { key ->
                    KeyboardKey(
                        key = key,
                        isCtrlLatched = km.ctrlLatched && key.id == "ctrl",
                        isRearranging = km.isRearranging,
                        isSelected = selectedKeyId == key.id,
                        isSwapping = key.id in swapPair,
                        onTap = { tapId ->
                            if (km.isRearranging) {
                                val sel = selectedKeyId
                                when {
                                    sel == null -> selectedKeyId = tapId
                                    sel == tapId -> selectedKeyId = null
                                    else -> {
                                        km.swapKeyOrder(sel, tapId)
                                        swapPair = setOf(sel, tapId)
                                        selectedKeyId = null
                                    }
                                }
                            } else {
                                selectedKeyId = null
                                km.handleVirtualKeyTap(key)
                            }
                        },
                        onLongPress = {
                            if (km.isRearranging) {
                                km.editVirtualKey(key.id)
                            } else if (key.id == "bspace") {
                                km.writeToSession("\u0015")
                            }
                        },
                        onSwipeLeft = {
                            if (km.isRearranging) {
                                km.exitRearrangeMode(true)
                                selectedKeyId = null
                            } else {
                                km.enterRearrangeMode()
                            }
                        },
                        onSwipeRight = { if (!km.isRearranging) km.writeToSession("\r") },
                        onSwipeDown = {
                            if (!km.isRearranging) {
                                if (key.id == "bspace") {
                                    km.sendSwipeAction("\u0015")
                                } else if (key.swipeCommand.isNotBlank()) {
                                    km.sendSwipeAction(key.swipeCommand)
                                }
                            }
                        },
                        onSwipeUp = {
                            if (!km.isRearranging && key.swipeUpCommand.isNotBlank()) {
                                km.sendSwipeAction(key.swipeUpCommand)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .height(36.dp),
                    )
                }
                repeat(KEYS_PER_ROW - rowKeys.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 单个虚拟按键：支持点击、长按、四向滑动手势，带重排动画。
 */
@Composable
private fun KeyboardKey(
    key: EmbeddedVirtualKey,
    isCtrlLatched: Boolean,
    isRearranging: Boolean,
    isSelected: Boolean,
    isSwapping: Boolean = false,
    onTap: (String) -> Unit,
    onLongPress: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val density = LocalDensity.current

    val swapProgress by animateFloatAsState(
        targetValue = if (isSwapping) 1f else 0f,
        animationSpec = tween(410),
        label = "swapProgress",
    )

    // 交换时的闪烁透明度效果
    val swapAlpha = if (isSwapping) {
        val t = swapProgress
        val blink = if (t < 0.5f) 1f - t * 2f else (t - 0.5f) * 2f
        1f - blink * 0.4f
    } else 1f

    // 重排模式下的摇摆动画
    val infiniteTransition = rememberInfiniteTransition(label = "wiggle")
    val wiggleRotation by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wiggleRotation",
    )

    val bgColor = when {
        isRearranging -> MaterialTheme.colorScheme.surfaceVariant
        isCtrlLatched -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        isRearranging && isSelected -> MaterialTheme.colorScheme.primary
        isRearranging -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Color.Transparent
    }
    val borderWidth = when {
        isRearranging && isSelected -> 2.dp
        isRearranging -> 1.dp
        else -> 0.dp
    }

    val keyScale = if (isSelected) 1.06f else 1f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.button))
            .background(bgColor)
            .then(
                if (isRearranging) Modifier.border(borderWidth, borderColor, RoundedCornerShape(Radius.button))
                else Modifier
            )
            .scale(keyScale)
            .then(
                // 重排模式下未选中的按键左右摇摆提示可拖动
                if (isRearranging && !isSelected) Modifier.rotate(wiggleRotation)
                else Modifier
            )
            .then(
                if (isSwapping) Modifier.alpha(swapAlpha) else Modifier
            )
            .pointerInput(key.id) {
                val vc = viewConfiguration
                val slop = vc.touchSlop
                val hThreshold = with(density) { 72.dp.toPx() }
                val vThreshold = 24.dp.toPx()
                val timeoutMs = vc.longPressTimeoutMillis

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragTotal = Offset.Zero
                    var longPressed = false
                    var released = false
                    val startTime = System.nanoTime()

                    while (true) {
                        // 长按检测
                        if (!longPressed && (System.nanoTime() - startTime) / 1_000_000 >= timeoutMs) {
                            longPressed = true
                            onLongPress()
                        }

                        // 等待下一个指针事件（长按前带超时，长按后无限等）
                        val event = if (!longPressed) {
                            val remaining = (timeoutMs - (System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // 超时触发长按
                            if (!longPressed) {
                                longPressed = true
                                onLongPress()
                            }
                            continue
                        }

                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            change.consume()
                            released = true
                            break
                        }
                        dragTotal += change.positionChange()
                        change.consume()

                        if (longPressed) continue

                        // 超过触摸斜率后开始识别滑动方向
                        if (dragTotal.getDistance() > slop) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                dragTotal += ch.positionChange()
                                ch.consume()
                            }
                            when {
                                abs(dragTotal.x) > abs(dragTotal.y) -> {
                                    if (dragTotal.x < -hThreshold) onSwipeLeft()
                                    else if (dragTotal.x > hThreshold) onSwipeRight()
                                }
                                dragTotal.y > vThreshold -> onSwipeDown()
                                dragTotal.y < -vThreshold -> onSwipeUp()
                            }
                            return@awaitEachGesture
                        }
                    }

                    // 短按触发点击
                    if (released && !longPressed) {
                        onTap(key.id)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
