package com.aidev.six.ui.pages

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aidev.six.ClipboardHelper
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.terminal.PerfSample
import com.aidev.six.ui.theme.themePresetList
import kotlin.math.roundToInt

/**
 * 终端主面板：整合顶部栏、标签栏、状态栏、终端视图、补全栏、虚拟键盘。
 * 子组件已拆分到独立文件以保持单一职责：
 * - TerminalTopBar — 顶部操作栏
 * - TerminalTabs — 会话标签栏
 * - TerminalStatusBar — 底部状态栏
 * - TerminalCompletionBar — 补全建议栏
 * - TerminalKeyboard — 虚拟键盘
 * - TerminalPerfHud — 性能监控浮层
 * - TerminalMoreSheet — 更多操作底部面板
 * - TerminalDialogs — 字体/主题覆盖层等对话框
 */
@Composable
fun TerminalPanel(
    page: EmbeddedTerminalPage,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val coreView = page.coreContainerView
    if (coreView == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("初始化中...", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
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
    var showCommandHelp by remember { mutableStateOf(false) }
    var showThemeOverlay by remember { mutableStateOf(false) }
    var showPerfHud by remember { mutableStateOf(false) }
    var perfSample by remember { mutableStateOf<PerfSample?>(null) }
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
        page.perfMonitor?.onSample = { perfSample = it }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
                TerminalTopBar(
                    activity = activity,
                    page = page,
                    onNewSession = { page.sessionManager.addSession() },
                onCopy = {},
                    onPaste = {
                        try {
                            val text = ClipboardHelper.paste(activity)
                            if (text != null) {
                                try {
                                    page.flushInput()
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
                    onTogglePerf = { showPerfHud = !showPerfHud },
                )
                TerminalTabs(page)
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
                    onHelp = { showCommandHelp = true },
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
        if (showPerfHud && perfSample != null) {
            TerminalPerfHud(perfSample, Modifier.align(Alignment.TopStart).padding(8.dp))
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
            onDismiss = { showMoreSheet = false },
        )
    }

    if (showCommandHelp) {
        AlertDialog(
            onDismissRequest = { showCommandHelp = false },
            modifier = Modifier.fillMaxSize(0.95f),
            title = null,
            text = {
                CommandHelpContent(activity = activity)
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCommandHelp = false }) {
                    Text("关闭")
                }
            },
        )
    }
}
