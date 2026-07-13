package com.aidev.six.ui.pages

import android.app.Activity
import android.content.Context
import com.aidev.six.BuildConfig
import android.os.Handler
import android.view.View
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aidev.six.ClipboardHelper
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.PreferencesManager
import com.aidev.six.TerminalCommandBus
import com.aidev.six.navigation.LocalImeBottomPx
import com.aidev.six.terminal.EmbeddedVirtualKey
import com.aidev.six.terminal.TerminalCompletion
import com.aidev.six.ui.components.AppChip
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TerminalPanel(
    page: EmbeddedTerminalPage,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val coreView = page.coreContainerView
    if (coreView == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        page.onSelected(activity)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) page.onSelected(activity)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            page.onDestroy(activity)
        }
    }

    var showFontSlider by remember { mutableStateOf(false) }
    var sliderFontSp by remember { mutableFloatStateOf(page.currentFontSp(activity)) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showThemeOverlay by remember { mutableStateOf(false) }
    var currentThemeKey by remember {
        mutableStateOf(
            activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
                .getString("terminal_theme", "classic-dark") ?: "classic-dark"
        )
    }
    var themeDragAccumulator by remember { mutableFloatStateOf(0f) }
    val decorView = activity.window?.decorView

    LaunchedEffect(Unit) {
        page.sessionManager.ensureSession()
        page.completionEngine.focusTerminalInput()
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            TerminalTopBar(
                activity = activity,
                onNewSession = { page.sessionManager.addSession() },
                onCopy = {},
                    onPaste = {
                        try {
                            val text = ClipboardHelper.paste(activity)
                            if (text != null) {
                                try {
                                    page.sessionManager.currentTerminalSession?.write(text)
                                } catch (_: Exception) {
                                    Toast.makeText(activity, "粘贴写入失败", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(activity, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {
                            Toast.makeText(activity, "读取剪贴板失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onMore = { showMoreSheet = true },
                )
                TerminalTabBar(page)
                TerminalStatusBar(
                    page = page,
                    fontSpClipped = sliderFontSp,
                    onFontSizeDragStart = { dragAccumulator = 0f; showFontSlider = true },
                    onFontSizeDrag = { deltaPx ->
                        dragAccumulator += deltaPx * 0.025f
                        val steps = dragAccumulator.roundToInt()
                        if (steps != 0) {
                            dragAccumulator -= steps
                            val prev = sliderFontSp.roundToInt()
                            sliderFontSp = (sliderFontSp + steps).coerceIn(10f, 24f).roundToInt().toFloat()
                            if (sliderFontSp.roundToInt() != prev) {
                                decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            page.applyFontSp(sliderFontSp)
                        }
                    },
                    onFontSizeDragEnd = {
                        page.applyFontSp(sliderFontSp)
                        showFontSlider = false
                    },
                    currentThemeKey = currentThemeKey,
                    onThemeDragStart = { themeDragAccumulator = 0f; showThemeOverlay = true },
                    onThemeDrag = { deltaPx ->
                        themeDragAccumulator += deltaPx * 0.03f
                        val steps = themeDragAccumulator.roundToInt()
                        if (steps != 0) {
                            themeDragAccumulator -= steps
                            val currentIdx = themePresetList.indexOfFirst { it.first == currentThemeKey }
                            if (currentIdx >= 0) {
                                val newIdx = ((currentIdx + steps) % themePresetList.size + themePresetList.size) % themePresetList.size
                                val (newKey, _) = themePresetList[newIdx]
                                currentThemeKey = newKey
                                page.sessionManager.applyThemeToAll(newKey)
                                activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
                                    .edit().putString("terminal_theme", newKey).apply()
                                decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                    },
                    onThemeDragEnd = { showThemeOverlay = false },
                )

            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { coreView },
                    update = { it.visibility = View.VISIBLE },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                TerminalCompletionBar(page)
                TerminalKeyboard(page)
            }
        }
    }

    if (showFontSlider) {
        FontSizeOverlay(fontSp = sliderFontSp)
    }

    if (showThemeOverlay) {
        val displayName = themePresetList.find { it.first == currentThemeKey }?.second ?: currentThemeKey
        ThemeOverlay(themeDisplayName = displayName)
    }

    if (showMoreSheet) {
        TerminalMoreSheet(
            activity = activity,
            page = page,
            onDismiss = { showMoreSheet = false },
        )
    }
}

@Composable
private fun TerminalTopBar(
    activity: Activity,
    onNewSession: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onMore: () -> Unit,
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
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
        TopBarButton("+", onClick = onNewSession)
        Spacer(modifier = Modifier.width(4.dp))
        TopBarButton("粘贴", onClick = onPaste)
        Spacer(modifier = Modifier.width(4.dp))
        TopBarButton("更多", onClick = onMore)
    }
}

@Composable
private fun TopBarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppChip(text = label, onClick = onClick, modifier = modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTabBar(page: EmbeddedTerminalPage, modifier: Modifier = Modifier) {
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
                title = { Text("\u4F1A\u8BDD ${session.title}") },
                text = {
                    Column {
                        listOf(
                            Triple("\u91CD\u547D\u540D", "\u4FEE\u6539\u4F1A\u8BDD\u6807\u7B7E\u6587\u672C") { menuSessionId = null; sm.renameCurrentSession() },
                            Triple("\u66F4\u6539\u989C\u8272", "\u8BBE\u7F6E\u6807\u7B7E\u989C\u8272\u6807\u8BC6") { menuSessionId = null; colorPickerId = id },
                            Triple("\u4ECE\u5F53\u524D\u76EE\u5F55\u6253\u5F00\u65B0\u4F1A\u8BDD", "\u514B\u9686\u5E76 cd \u5230\u5F53\u524D\u76EE\u5F55") { menuSessionId = null; sm.cloneSession() },
                        ).forEach { (title, desc, onClick) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onClick)
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
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
                dismissButton = { TextButton(onClick = { menuSessionId = null }) { Text("\u53D6\u6D88") } },
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

@Composable
private fun TabChip(
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
            .clip(RoundedCornerShape(6.dp))
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
                        var event = firstEvent!!
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
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

@Composable
private fun TerminalStatusBar(
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
) {
    val activity = LocalContext.current as Activity
    val btnWidth = 42.dp
    val themeColor = themeColor(currentThemeKey)
    val themeAbbr = themeAbbreviation(currentThemeKey)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${fontSpClipped.toInt()}sp",
            style = MaterialTheme.typography.labelSmall,
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
            fontWeight = FontWeight.Bold,
            color = if (page.tuiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(btnWidth).clickable { page.toggleTuiMode(activity) },
        )
    }
}

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
                .padding(horizontal = 6.dp, vertical = 3.dp),
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
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

private const val KEYBOARD_HEIGHT = 88
private const val KEYS_PER_ROW = 8

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

    val swapAlpha = if (isSwapping) {
        val t = swapProgress
        val blink = if (t < 0.5f) 1f - t * 2f else (t - 0.5f) * 2f
        1f - blink * 0.4f
    } else 1f

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
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (isRearranging) Modifier.border(borderWidth, borderColor, RoundedCornerShape(8.dp))
                else Modifier
            )
            .scale(keyScale)
            .then(
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
                        if (!longPressed && (System.nanoTime() - startTime) / 1_000_000 >= timeoutMs) {
                            longPressed = true
                            onLongPress()
                        }

                        val event = if (!longPressed) {
                            val remaining = (timeoutMs - (System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
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

@Composable
private fun FontSizeOverlay(fontSp: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${fontSp.roundToInt()}sp",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 48.dp, vertical = 32.dp),
        )
    }
}

private val themePresetList = listOf(
    "classic-dark" to "One Dark",
    "classic-light" to "One Light",
    "solarized-dark" to "Solarized Dark",
    "dracula" to "Dracula",
    "nord" to "Nord",
    "tokyo-night" to "Tokyo Night",
    "catppuccin" to "Catppuccin Mocha",
    "gruvbox" to "Gruvbox Dark",
    "monokai" to "Monokai",
    "everforest" to "Everforest Dark",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalMoreSheet(
    activity: Activity,
    page: EmbeddedTerminalPage,
    onDismiss: () -> Unit,
) {
    val prefs = remember { PreferencesManager(activity) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSwipeDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showBgDialog by remember { mutableStateOf(false) }
    var hapticChecked by remember { mutableStateOf(prefs.hapticTap) }

    val currentBgOverride = prefs.bgOverride
    val bgPreviewColor = if (currentBgOverride != -1) {
        String.format("#%06X", currentBgOverride and 0xFFFFFF)
    } else {
        "默认"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SectionHeader("输入")
            SectionToggleItem("触觉反馈", "按键时振动反馈", hapticChecked) {
                hapticChecked = it; prefs.hapticTap = it
            }
            SectionItem("滑动灵敏度") { showSwipeDialog = true }
            SectionItem("重置按键布局") { showResetDialog = true }
            SectionItem("别名管理") { page.keyboardManager.showAliasManager() }

            HorizontalDivider()

            SectionHeader("显示")
            SectionItem("背景色 · $bgPreviewColor") { showBgDialog = true }

        }
    }

    if (showSwipeDialog) {
        SwipeSensitivityDialog { showSwipeDialog = false }
    }
    if (showResetDialog) {
        ResetKeyboardDialog(activity) { showResetDialog = false }
    }
    if (showBgDialog) {
        BackgroundColorDialog(
            initialOverride = prefs.bgOverride,
            onApply = { color ->
                prefs.bgOverride = color
                page.sessionManager.applyThemeToAll(prefs.terminalTheme)
                showBgDialog = false
            },
            onReset = {
                prefs.bgOverride = -1
                page.sessionManager.applyThemeToAll(prefs.terminalTheme)
                showBgDialog = false
            },
            onDismiss = { showBgDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
    )
}

@Composable
private fun SectionItem(text: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionToggleItem(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun ClickableSectionHeader(text: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ThemeOverlay(themeDisplayName: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = themeDisplayName,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    RoundedCornerShape(20.dp),
                )
                .padding(horizontal = 48.dp, vertical = 32.dp),
        )
    }
}

private fun themeColor(key: String): Color = when (key) {
    "classic-dark" -> Color(0xFF61AFEF)
    "classic-light" -> Color(0xFF407FF4)
    "solarized-dark" -> Color(0xFFCB4B16)
    "dracula" -> Color(0xFFBD93F9)
    "nord" -> Color(0xFF5E81AC)
    "tokyo-night" -> Color(0xFF7AA2F7)
    "catppuccin" -> Color(0xFFF5C2E7)
    "gruvbox" -> Color(0xFFD79921)
    "monokai" -> Color(0xFFA6E22E)
    "everforest" -> Color(0xFFA7C080)
    else -> Color.Gray
}

private fun themeAbbreviation(key: String): String = when (key) {
    "classic-dark" -> "One"
    "classic-light" -> "Lt"
    "solarized-dark" -> "Sol"
    "dracula" -> "Dra"
    "nord" -> "Nrd"
    "tokyo-night" -> "Tok"
    "catppuccin" -> "Cat"
    "gruvbox" -> "Grb"
    "monokai" -> "Mon"
    "everforest" -> "Evr"
    else -> "??"
}

private fun sessionColor(key: String): Color = when (key) {
    "red" -> Color(0xFFEF4444)
    "orange" -> Color(0xFFF97316)
    "yellow" -> Color(0xFFEAB308)
    "green" -> Color(0xFF22C55E)
    "cyan" -> Color(0xFF06B6D4)
    "blue" -> Color(0xFF3B82F6)
    "purple" -> Color(0xFFA855F7)
    "pink" -> Color(0xFFEC4899)
    "gray" -> Color(0xFF6B7280)
    else -> Color.Transparent
}

private val sessionColorOptions = listOf(
    "" to "\u65E0",
    "red" to "\u7EA2",
    "orange" to "\u6A59",
    "yellow" to "\u9EC4",
    "green" to "\u7EFF",
    "cyan" to "\u9752",
    "blue" to "\u84DD",
    "purple" to "\u7D2B",
    "pink" to "\u7C89",
    "gray" to "\u7070",
)

@Composable
private fun TabColorPickerDialog(
    currentColor: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\u9009\u62E9\u4F1A\u8BDD\u989C\u8272") },
        text = {
            Column {
                sessionColorOptions.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(key) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(sessionColor(key)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        if (key == currentColor) {
                            Spacer(Modifier.weight(1f))
                            Text("\u2713", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("\u53D6\u6D88") } },
    )
}

@Composable
private fun SwipeSensitivityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiPrefs = context.getSharedPreferences("aidev_ui", android.content.Context.MODE_PRIVATE)
    val current = uiPrefs.getFloat("swipe_sensitivity", 1.0f)
    val items = listOf("low" to "低敏感", "medium" to "中敏感", "high" to "高敏感")
    val floatMap = mapOf("low" to 1.5f, "medium" to 1.0f, "high" to 0.5f)
    val initialKey = floatMap.entries.firstOrNull { it.value == current }?.key ?: "medium"
    var selectedValue by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("滑动灵敏度") },
        text = {
            com.aidev.six.ui.components.AppRadioDialogContent(
                items = items,
                selectedValue = selectedValue,
                onSelect = { selectedValue = it },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                uiPrefs.edit().putFloat("swipe_sensitivity", floatMap[selectedValue] ?: 1.0f).apply()
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ResetKeyboardDialog(activity: Activity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认重置") },
        text = { Text("将恢复键盘按键顺序到默认布局，自定义按键覆盖不会丢失。", color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            TextButton(onClick = {
                activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
                    .edit().remove("terminal_key_order").apply()
                onDismiss()
            }) { Text("重置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private data class HuePreset(val label: String, val r: Int, val g: Int, val b: Int)

private val huePresets = listOf(
    HuePreset("灰", 0x88, 0x88, 0x88),
    HuePreset("蓝", 0x1A, 0x1B, 0x3E),
    HuePreset("紫", 0x3A, 0x1B, 0x4E),
    HuePreset("绿", 0x1A, 0x3E, 0x1B),
    HuePreset("暖", 0x3E, 0x27, 0x1A),
)

@Composable
private fun BackgroundColorDialog(
    initialOverride: Int,
    onApply: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasOverride = initialOverride != -1
    val initR = if (hasOverride) (initialOverride shr 16) and 0xFF else 0x88
    val initG = if (hasOverride) (initialOverride shr 8) and 0xFF else 0x88
    val initB = if (hasOverride) initialOverride and 0xFF else 0x88
    val initHue = huePresets.indices.minByOrNull { i ->
        val p = huePresets[i]; val dr = initR - p.r; val dg = initG - p.g; val db = initB - p.b
        dr * dr + dg * dg + db * db
    } ?: 0
    val maxComponent = maxOf(initR, initG, initB, 1)
    val initBrightness = ((initR.toFloat() / maxComponent * 255f).roundToInt()).coerceIn(30, 255)

    var selectedHue by remember { mutableIntStateOf(initHue) }
    var brightness by remember { mutableIntStateOf(initBrightness) }

    val preset = huePresets[selectedHue]
    val r = (preset.r * brightness / 255).coerceIn(0, 255)
    val g = (preset.g * brightness / 255).coerceIn(0, 255)
    val b = (preset.b * brightness / 255).coerceIn(0, 255)
    val previewColor = Color(r / 255f, g / 255f, b / 255f)
    val resultInt = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整背景色") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(previewColor, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    huePresets.forEachIndexed { i, hue ->
                        val chipColor = if (i == selectedHue) {
                            Color(hue.r / 255f, hue.g / 255f, hue.b / 255f)
                        } else {
                            Color(hue.r / 255f * 0.5f, hue.g / 255f * 0.5f, hue.b / 255f * 0.5f)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(chipColor)
                                .border(
                                    if (i == selectedHue) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { selectedHue = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(hue.label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("亮度", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { brightness = it.roundToInt().coerceIn(30, 255) },
                    valueRange = 30f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = previewColor,
                        activeTrackColor = previewColor,
                    ),
                )
                Text("${brightness * 100 / 255}%", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(resultInt) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("重置", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
