package com.aidev.six.ui.pages

import com.aidev.six.ui.components.AppActionRow
import com.aidev.six.ui.components.AppSectionHeader
import com.aidev.six.ui.components.AppToggleRow
import com.aidev.six.ui.theme.Radius
import com.aidev.six.ui.theme.Spacing

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
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
import com.aidev.six.terminal.PerfSample
import com.aidev.six.PathConfig
import com.aidev.six.ProjectExporter

import com.aidev.six.ProjectDetector
import com.aidev.six.SyncCoordinator
import com.aidev.six.ShizukuLogcat
import java.io.File
import org.json.JSONObject
import com.aidev.six.ui.components.AppChip
import com.aidev.six.ui.components.TabChip
import com.aidev.six.ui.pages.CommandHelpContent
import com.aidev.six.ui.theme.TabColorPickerDialog
import com.aidev.six.ui.theme.themePresetList
import kotlin.math.abs
import kotlin.math.roundToInt

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
            TerminalPerfHud(perfSample!!, Modifier.align(Alignment.TopStart).padding(8.dp))
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
                    Text("\u5173\u95ED")
                }
            },
        )
    }
}

@Composable
private fun TerminalTopBar(
    activity: Activity,
    page: EmbeddedTerminalPage,
    onNewSession: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onMore: () -> Unit,
    onTogglePerf: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pwd by remember { mutableStateOf(page.completionEngine.cachedCompletionPwd) }
    var projectDir by remember { mutableStateOf<File?>(null) }
    var pkgName by remember { mutableStateOf<String?>(null) }

    // 终端 cwd 变化由 SessionManager 的 pwdState（FileObserver 驱动）推送，避免每秒轮询唤醒。
    LaunchedEffect(Unit) {
        page.sessionManager.pwdState.collect { newPwd ->
            if (newPwd.isNotBlank()) pwd = newPwd
        }
    }

    LaunchedEffect(pwd) {
        val home = PathConfig.aidevHome(activity)
        val androidDir = SyncCoordinator.toAndroidDir(pwd, home)
        val base = androidDir ?: page.completionEngine.currentProjectDir()
        val dir = base?.let { ProjectDetector.findProjectRoot(it) }
        projectDir = dir
        if (dir != null && ProjectDetector.isAndroidProject(dir)) {
            pkgName = resolveProjectPackage(activity, dir)
        } else {
            pkgName = null
        }
    }

    val isAndroid = projectDir != null && ProjectDetector.isAndroidProject(projectDir!!)
    val canBuild = isAndroid
    val canLaunch = pkgName != null

    val disabledBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val disabledFg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppChip(
            text = "编译",
            modifier = Modifier.widthIn(max = 120.dp),
            onClick = {
                if (!canBuild) return@AppChip
                val dir = projectDir ?: return@AppChip
                // 在终端会话里真正执行构建命令，使构建过程在终端可见（与双宇宙文档一致）
                val cmd = "aidev-build-request --project ${dir.name} --no-install --no-launch"
                val ts = page.sessionManager.currentTerminalSession
                if (ts != null) {
                    page.completionEngine?.inputBuffer = ""
                    ts.write("$cmd\r")
                    page.completionEngine?.focusTerminalInput()
                    Toast.makeText(activity, "已在终端提交构建：${dir.name}（不自动安装）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "当前无可用终端会话", Toast.LENGTH_SHORT).show()
                }
            },
            bgColor = if (canBuild) MaterialTheme.colorScheme.primaryContainer else disabledBg,
            textColor = if (canBuild) MaterialTheme.colorScheme.onPrimaryContainer else disabledFg,
        )
        Spacer(modifier = Modifier.width(Spacing.s4))
        AppChip(
            text = "拉起",
            modifier = Modifier.widthIn(max = 120.dp),
            onClick = {
                if (!canLaunch) return@AppChip
                val pkg = pkgName ?: return@AppChip
                scope.launch {
                    ShizukuLogcat.executeCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    Toast.makeText(activity, "已拉起：$pkg", Toast.LENGTH_SHORT).show()
                }
            },
            bgColor = if (canLaunch) MaterialTheme.colorScheme.primaryContainer else disabledBg,
            textColor = if (canLaunch) MaterialTheme.colorScheme.onPrimaryContainer else disabledFg,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 8.dp),
        )
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

private fun resolveProjectPackage(ctx: Context, projectDir: File): String? {
    // 优先用已构建 APK 的真实包名（pm install 注册的 applicationId 才是真相），
    // 避免 Gradle namespace 与最终 applicationId 不一致导致装到错误包名。
    val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
    if (apk.isFile) {
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        if (!info?.packageName.isNullOrBlank()) return info!!.packageName
    }
    for (rel in listOf("app/build.gradle.kts", "app/build.gradle")) {
        val file = File(projectDir, rel)
        if (file.isFile) {
            val ns = Regex("""namespace\s*=\s*"([^"]+)"""").find(file.readText())?.groupValues?.getOrNull(1)
            if (ns != null) return ns
        }
    }
    return null
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
            .clip(RoundedCornerShape(Radius.button))
            .background(bgColor)
            .then(
                if (isRearranging) Modifier.border(borderWidth, borderColor, RoundedCornerShape(Radius.button))
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
