package com.aidev.six.ui.pages

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aidev.six.BuildConfig
import com.aidev.six.EmbeddedTerminalPage
import com.aidev.six.PathConfig
import com.aidev.six.ProjectDetector
import com.aidev.six.shizuku.ShizukuLogcat
import com.aidev.six.SyncCoordinator
import com.aidev.six.ui.components.AppChip
import com.aidev.six.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File

/**
 * 终端顶部栏：编译/拉起按钮 + 版本号 + 新建会话/粘贴/性能/更多操作按钮。
 * 从 TerminalPanel.kt 中拆分出来，保持单一职责。
 */
@Composable
internal fun TerminalTopBar(
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

    val isAndroid by remember(projectDir) {
        derivedStateOf { projectDir != null && ProjectDetector.isAndroidProject(projectDir!!) }
    }
    val canBuild = isAndroid
    val canLaunch = pkgName != null

    val disabledBg by remember {
        derivedStateOf { MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) }
    }
    val disabledFg by remember {
        derivedStateOf { MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) }
    }

    // 缓存 lambda，避免每次重组重新创建
    val buildOnClick = remember(canBuild, projectDir, page, activity) {
        {
            if (!canBuild) return@remember
            val dir = projectDir ?: return@remember
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
        }
    }
    val launchOnClick = remember(canLaunch, pkgName, scope, activity) {
        {
            if (!canLaunch) return@remember
            val pkg = pkgName ?: return@remember
            scope.launch {
                ShizukuLogcat.executeCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                Toast.makeText(activity, "已拉起：$pkg", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            onClick = buildOnClick,
            bgColor = if (canBuild) MaterialTheme.colorScheme.primaryContainer else disabledBg,
            textColor = if (canBuild) MaterialTheme.colorScheme.onPrimaryContainer else disabledFg,
        )
        Spacer(modifier = Modifier.width(Spacing.s4))
        AppChip(
            text = "拉起",
            modifier = Modifier.widthIn(max = 120.dp),
            onClick = launchOnClick,
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

/**
 * 解析项目包名：优先用已构建 APK 的真实包名（才是真相），
 * 避免 Gradle namespace 与最终 applicationId 不一致。
 */
private fun resolveProjectPackage(ctx: Context, projectDir: File): String? {
    val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
    if (apk.isFile) {
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        if (!info?.packageName.isNullOrBlank()) return info?.packageName
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
