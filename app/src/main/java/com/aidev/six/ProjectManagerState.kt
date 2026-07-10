package com.aidev.six

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aidev.six.files.ProjectManager
import com.aidev.six.terminal.ProotLauncher
import com.aidev.six.files.ShizukuInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile

internal class ProjectManagerState(activity: Activity) {

    var projectList by mutableStateOf<List<ProjectMeta>>(emptyList())
    var selectedProject by mutableStateOf<ProjectMeta?>(null)
    var projectFilter by mutableStateOf("android")

    private var _projectViewTab by mutableIntStateOf(0)
    var projectViewTab: Int
        get() = _projectViewTab
        set(value) {
            _projectViewTab = value
            pm.projectViewTab = value
        }
    var buildLog by mutableStateOf("")
    var runtimeLog by mutableStateOf("")
    var isBuilding by mutableStateOf(false)
    var isRunning by mutableStateOf(false)
    var showAppPicker by mutableStateOf(false)
    @Volatile var buildJob: kotlinx.coroutines.Job? = null
    @Volatile var logcatJob: kotlinx.coroutines.Job? = null
    @Volatile var logcatProcess: java.lang.Process? = null
    var needsJdkInstall by mutableStateOf(false)
    var isInstalling by mutableStateOf(false)
    var installOutput by mutableStateOf("")
    var installError by mutableStateOf<String?>(null)
    @Volatile private var installJob: kotlinx.coroutines.Job? = null
    @Volatile private var installProcess: Process? = null

    val pm = PreferencesManager(activity)
    val projectManager = ProjectManager(activity)
    val shizukuInstaller = ShizukuInstaller(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var _activity: Activity = activity

    init {
        _projectViewTab = pm.projectViewTab
    }

    fun updateActivity(activity: Activity) {
        _activity = activity
    }

    fun onDestroy() {
        scope.cancel()
        shizukuInstaller.destroy()
    }

    fun startBuild(projectDir: File) {
        if (isBuilding || isInstalling) return
        isBuilding = true
        buildLog = ""
        projectViewTab = 0
        buildJob = scope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val gradlew = File(projectDir, "gradlew")
                if (!gradlew.isFile) {
                    withContext(Dispatchers.Main) { buildLog += "✗ 未找到 gradlew\n" }
                    return@launch
                }

                val readyFile = File(PathConfig.agentRootfs(_activity), ".aidev-rootfs-ready")
                if (!readyFile.isFile) {
                    withContext(Dispatchers.Main) {
                        buildLog += "⚠ Ubuntu 环境未就绪，请先在终端中进入 Ubuntu 完成初始化\n"
                        isBuilding = false
                        toast("PRoot 未就绪")
                    }
                    return@launch
                }

                process = createProotProcess("java -version 2>&1")
                val javaOut = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitForTimed(30) != 0) {
                    process?.destroy()
                    withContext(Dispatchers.Main) {
                        isBuilding = false
                        buildLog += "⚠ 未检测到 Java，需要安装 JDK 17\n"
                        needsJdkInstall = true
                    }
                    return@launch
                }

                process = createProotProcess("test -d /host-home/android-sdk/platforms/android-36")
                process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitForTimed(30) != 0) {
                    withContext(Dispatchers.Main) {
                        isBuilding = false
                        buildLog += "⚠ 未检测到 Android SDK，需要安装\n"
                        needsJdkInstall = true
                    }
                    return@launch
                }

                val rootfs = PathConfig.agentRootfs(_activity).absolutePath
                val prootCwd = projectDir.absolutePath.removePrefix(rootfs).trimStart('/')
                process = createProotProcess("cd /$prootCwd && chmod +x gradlew && ./gradlew assembleDebug --no-daemon")
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        withContext(Dispatchers.Main) { buildLog += "$line\n" }
                        line = reader.readLine()
                    }
                }
                val exitCode = process.waitForTimed(600)
                withContext(Dispatchers.Main) {
                    if (exitCode == 0) {
                        buildLog += "\n✓ 构建成功\n"
                        toast("构建成功")
                    } else {
                        buildLog += "\n✗ 构建失败 (exit=$exitCode)\n"
                        toast("构建失败")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                process?.destroy()
                withContext(Dispatchers.Main) { buildLog += "\n⏹ 构建已取消\n" }
            } catch (e: Exception) {
                process?.destroy()
                withContext(Dispatchers.Main) { buildLog += "\n✗ ${e.message}\n"; toast("构建出错") }
            } finally {
                withContext(Dispatchers.Main) { isBuilding = false }
            }
        }
    }

    fun cancelBuild() {
        buildJob?.cancel()
        buildJob = null
    }

    fun confirmInstall(projectDir: File) {
        if (isInstalling) return
        installJob = scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isInstalling = true
                installOutput = ""
                installError = null
            }
            var process: Process? = null
            try {
                appendInstallOutput("\$ apt-get update -qq\n")
                process = createProotProcess("apt-get update -qq 2>&1")
                installProcess = process
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendInstallOutput("$line\n")
                        line = reader.readLine()
                    }
                }
                    process.waitForTimed(300)
                installProcess = null

                appendInstallOutput("\$ apt-get install -y openjdk-17-jdk-headless unzip\n")
                process = createProotProcess("DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk-headless unzip 2>&1")
                installProcess = process
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendInstallOutput("$line\n")
                        line = reader.readLine()
                    }
                }
                val exitCode = process.waitForTimed(300)
                installProcess = null

                if (exitCode != 0) {
                    val output = installOutput
                    withContext(Dispatchers.Main) {
                        installError = matchErrorPattern(output) ?: "安装命令返回非零退出码 (exit=$exitCode)"
                    }
                    return@launch
                }

                appendInstallOutput("\n\$ java -version\n")
                process = createProotProcess("java -version 2>&1")
                val verifyOut = process.inputStream.bufferedReader().use { it.readText() }
                val verifyCode = process.waitForTimed(30)
                appendInstallOutput(verifyOut)
                installProcess = null

                if (verifyCode != 0) {
                    withContext(Dispatchers.Main) {
                        installError = "安装完成但 java 命令不可用:\n$verifyOut"
                    }
                    return@launch
                }

                appendInstallOutput("\n✓ JDK 安装成功\n")

                appendInstallOutput("\n\$ test -d /host-home/android-sdk/platforms/android-36\n")

                process = createProotProcess("test -d /host-home/android-sdk/platforms/android-36")
                if (process.waitForTimed(30) != 0) {
                    appendInstallOutput("→ Android SDK 缺失\n")

                    val backupSdkDir = File("/storage/emulated/0/dev-backup/android-sdk")
                    if (backupSdkDir.isDirectory()) {
                        appendInstallOutput("  检测到本地备份 (/storage/emulated/0/dev-backup)，尝试从备份恢复...\n")
                        val restoreScript = buildString {
                            appendLine("BACKUP=/storage/emulated/0/dev-backup/android-sdk")
                            appendLine("SDK=/host-home/android-sdk")
                            appendLine("echo '→ 恢复 platforms...'")
                            appendLine("mkdir -p \$SDK/platforms && cp -a \$BACKUP/platforms/. \$SDK/platforms/")
                            appendLine("echo '→ 恢复 build-tools...'")
                            appendLine("mkdir -p \$SDK/build-tools && cp -a \$BACKUP/build-tools/. \$SDK/build-tools/")
                            appendLine("echo '→ 恢复 cmdline-tools...'")
                            appendLine("mkdir -p \$SDK/cmdline-tools && cp -a \$BACKUP/cmdline-tools/. \$SDK/cmdline-tools/")
                            appendLine("echo '→ 恢复 platform-tools...'")
                            appendLine("mkdir -p \$SDK/platform-tools && cp -a \$BACKUP/platform-tools/. \$SDK/platform-tools/")
                            appendLine("echo '→ 恢复 licenses...'")
                            appendLine("mkdir -p \$SDK/licenses && cp -a \$BACKUP/licenses/. \$SDK/licenses/")
                            appendLine("echo '✓ SDK 从备份恢复完成'")
                        }
                        process = createProotProcess(restoreScript)
                        installProcess = process
                        process.inputStream.bufferedReader().use { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                appendInstallOutput("$line\n")
                                line = reader.readLine()
                            }
                        }
                        val backupCode = process.waitForTimed(120)
                        installProcess = null
                        if (backupCode == 0) {
                            appendInstallOutput("✓ Android SDK 从备份恢复完成\n")
                        } else {
                            appendInstallOutput("⚠ 备份恢复失败 (exit=$backupCode)，回退到网络下载\n")
                            doDownloadSdk()
                        }
                    } else {
                        doDownloadSdk()
                    }
                } else {
                    appendInstallOutput("✓ Android SDK 已就绪\n")
                }

                withContext(Dispatchers.Main) {
                    needsJdkInstall = false
                    isInstalling = false
                }
                startBuild(projectDir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                process?.destroy()
                installProcess = null
                withContext(Dispatchers.Main) {
                    installOutput += "\n⏹ 安装已取消\n"
                    installError = "用户取消"
                }
            } catch (e: Exception) {
                process?.destroy()
                installProcess = null
                withContext(Dispatchers.Main) {
                    installOutput += "\n✗ ${e.message}\n"
                    installError = "安装异常: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { isInstalling = false }
            }
        }
    }

    fun cancelInstall() {
        installProcess?.destroy()
        installProcess = null
        installJob?.cancel()
        installJob = null
    }

    fun dismissInstall() {
        cancelInstall()
        needsJdkInstall = false
        isInstalling = false
        installOutput = ""
        installError = null
    }

    fun startApp(apkPath: String) {
        if (isRunning || !File(apkPath).isFile) return
        shizukuInstaller.installApk(File(apkPath))
        val pkgInfo = _activity.packageManager.getPackageArchiveInfo(apkPath, 0) ?: return
        val packageName = pkgInfo.packageName ?: return
        isRunning = true
        runtimeLog = ""
        projectViewTab = 1
        logcatJob = scope.launch {
            try {
                val launchResult = withContext(Dispatchers.IO) {
                    ShizukuLogcat.executeCommand("am start -n $packageName/.MainActivity")
                }
                if (launchResult.exitCode != 0) {
                    runtimeLog += "⚠ 启动可能失败: ${launchResult.stderr}\n"
                } else {
                    runtimeLog += "✓ 已启动 $packageName\n"
                }

                val process = ShizukuLogcat.startLogStream(
                    packageName = packageName,
                    onLine = { line ->
                        scope.launch(Dispatchers.Main) {
                            runtimeLog += "$line\n"
                        }
                    },
                    onError = { err ->
                        scope.launch(Dispatchers.Main) {
                            runtimeLog += "✗ $err\n"
                        }
                    }
                )
                logcatProcess = process
                if (process == null) {
                    runtimeLog += "✗ 日志流启动失败: Shizuku 可能未就绪\n"
                    return@launch
                }

                while (isActive) {
                    delay(1000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                logcatProcess?.destroy()
                logcatProcess = null
                runtimeLog += "\n⏹ 日志已停止\n"
            } catch (e: Exception) {
                logcatProcess?.destroy()
                logcatProcess = null
                runtimeLog += "\n✗ ${e.message}\n"
                toast("运行出错")
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
                isRunning = false
            }
        }
    }

    fun startLogcatForPackage(packageName: String) {
        if (isRunning) {
            stopLogcat()
        }
        isRunning = true
        runtimeLog = ""
        projectViewTab = 1
        logcatJob = scope.launch {
            try {
                val process = ShizukuLogcat.startLogStream(
                    packageName = packageName,
                    onLine = { line ->
                        scope.launch(Dispatchers.Main) {
                            runtimeLog += "$line\n"
                        }
                    },
                    onError = { err ->
                        scope.launch(Dispatchers.Main) {
                            runtimeLog += "✗ $err\n"
                        }
                    }
                )
                logcatProcess = process
                if (process == null) {
                    runtimeLog += "✗ 日志流启动失败: Shizuku 可能未就绪\n"
                    return@launch
                }
                while (isActive) {
                    delay(1000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                logcatProcess?.destroy()
                logcatProcess = null
                runtimeLog += "\n⏹ 日志已停止\n"
            } catch (e: Exception) {
                logcatProcess?.destroy()
                logcatProcess = null
                runtimeLog += "\n✗ ${e.message}\n"
                toast("运行出错")
            } finally {
                logcatProcess?.destroy()
                logcatProcess = null
                isRunning = false
            }
        }
    }

    fun stopLogcat() {
        logcatProcess?.destroy()
        logcatProcess = null
        logcatJob?.cancel()
        logcatJob = null
    }

    fun refreshProjectList() {
        val ws = workspaceDir()
        scope.launch {
            val projects = withContext(Dispatchers.IO) {
                if (projectFilter == "android") {
                    ProjectDetector.findAndroidProjects(ws)
                } else {
                    ProjectDetector.findProjectsInWorkspace(ws)
                }
            }
            projectList = projects.map { ProjectDetector.getProjectMeta(it) }
        }
    }

    fun workspaceDir(activity: Activity): File = PathConfig.workspaceDir(activity)

    private fun workspaceDir(): File = PathConfig.workspaceDir(_activity)

    private fun toast(text: String) {
        Toast.makeText(_activity, text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun appendInstallOutput(text: String) {
        withContext(Dispatchers.Main) { installOutput += text }
    }

    private fun createProotProcess(innerCmd: String): Process {
        val rootfs = PathConfig.agentRootfs(_activity).absolutePath
        return ProotLauncher.start(
            _activity,
            innerCmd,
            ProotLauncher.Options(
                rootfs = rootfs,
                env = mapOf(
                    "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache"
                ),
                redirectErrorStream = true
            )
        )
    }

    private suspend fun doDownloadSdk() {
        appendInstallOutput("→ 准备从 USTC 镜像下载 Android SDK...\n")
        var process: Process? = null
        try {
            process = createProotProcess("command -v curl 2>&1 || apt-get install -y --no-install-recommends curl 2>&1")
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    appendInstallOutput("$line\n")
                    line = reader.readLine()
                }
            }
            process.waitForTimed(30)

            val sdkScript = buildString {
                appendLine("SDK=/host-home/android-sdk")
                appendLine("MIR=https://mirrors.ustc.edu.cn/android/repository")
                appendLine("if [ ! -f \$SDK/cmdline-tools/latest/bin/sdkmanager ]; then")
                appendLine("  echo '-> downloading cmdline-tools...'")
                appendLine("  mkdir -p \$SDK")
                appendLine("  curl -L \$MIR/commandlinetools-linux-11076708_latest.zip -o /tmp/ct.zip 2>&1")
                appendLine("  unzip -q /tmp/ct.zip -d /tmp/ct")
                appendLine("  mkdir -p \$SDK/cmdline-tools")
                appendLine("  mv /tmp/ct/cmdline-tools \$SDK/cmdline-tools/latest")
                appendLine("  rm -rf /tmp/ct /tmp/ct.zip")
                appendLine("  echo 'done'")
                appendLine("fi")
                appendLine("")
                appendLine("echo '-> downloading platform android-36...'")
                appendLine("mkdir -p /tmp/p36-extract")
                appendLine("curl -L \$MIR/platform-36_r03.zip -o /tmp/p36.zip 2>&1 && unzip -q /tmp/p36.zip -d /tmp/p36-extract")
                appendLine("mkdir -p \$SDK/platforms")
                appendLine("rm -rf \$SDK/platforms/android-36")
                appendLine("p36_dir=\$(ls /tmp/p36-extract)")
                appendLine("mv \"/tmp/p36-extract/\$p36_dir\" \$SDK/platforms/android-36")
                appendLine("rm -rf /tmp/p36-extract /tmp/p36.zip")
                appendLine("echo 'done'")
                appendLine("")
                appendLine("echo '-> downloading build-tools 36.0.0...'")
                appendLine("mkdir -p /tmp/bt-extract")
                appendLine("curl -L \$MIR/build-tools_r36.0.0-linux.zip -o /tmp/bt.zip 2>&1 && unzip -q /tmp/bt.zip -d /tmp/bt-extract")
                appendLine("mkdir -p \$SDK/build-tools/36.0.0")
                appendLine("bt_dir=\$(ls /tmp/bt-extract)")
                appendLine("mv \"/tmp/bt-extract/\$bt_dir\"/* \$SDK/build-tools/36.0.0/")
                appendLine("rm -rf /tmp/bt-extract /tmp/bt.zip")
                appendLine("echo 'done'")
            }
            process = createProotProcess(sdkScript)
            installProcess = process
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    appendInstallOutput("$line\n")
                    line = reader.readLine()
                }
            }
            val sdkCode = process.waitForTimed(300)
            installProcess = null

            if (sdkCode != 0) {
                val output = installOutput
                withContext(Dispatchers.Main) {
                    installError = matchErrorPattern(output) ?: "SDK 安装失败 (exit=$sdkCode)。可尝试手动运行: setup-dev-env"
                }
            } else {
                appendInstallOutput("✓ Android SDK 安装完成\n")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            process?.destroy()
            withContext(Dispatchers.Main) {
                installError = "SDK 下载安装异常: ${e.message}"
            }
        }
    }

    private fun matchErrorPattern(output: String): String? {
        val o = output.lowercase()
        return when {
            o.contains("could not get lock") || o.contains("unable to lock") ->
                "有另一个安装进程在运行。请稍后重试，或重启应用。"
            o.contains("waiting for cache lock") || o.contains("could not lock") ->
                "等待缓存锁超时。请稍后重试。"
            o.contains("no space left on device") || o.contains("disk full") ->
                "存储空间不足。请释放存储空间后重试。"
            o.contains("network is unreachable") || o.contains("could not resolve") || o.contains("temporary failure in name resolution") ->
                "网络连接失败。请检查网络后重试。"
            o.contains("package has no installation candidate") ->
                "软件包不可用。请尝试执行 apt-get update 后重试。"
            o.contains("connection timed out") || o.contains("timeout") ->
                "连接超时。请检查网络后重试。"
            o.contains("internal error") || o.contains("sub-process") ->
                "dpkg 内部错误。可尝试执行: dpkg --configure -a"
            else -> null
        }
    }

    private fun Process.waitForTimed(timeoutSec: Long): Int {
        if (waitFor(timeoutSec, TimeUnit.SECONDS)) return exitValue()
        destroyForcibly()
        throw RuntimeException("Process timed out after ${timeoutSec}s")
    }
}
