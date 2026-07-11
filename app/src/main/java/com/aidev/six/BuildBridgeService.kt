package com.aidev.six

import android.content.Context
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStepResult
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.agent.BuildProgress
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 自我进化闭环的构建桥。
 *
 * OpenCode（宇宙 A）通过其工具 `aidev-build-request` 写入结构化请求文件：
 *   home/.aidev-build-bridge/req-<id>.json
 *   { "project": "<workspace 下相对路径或 /workspace/...>", "flavor": "debug",
 *     "autoInstall": true, "autoLaunch": true, "launchPackage": "<可选>" }
 *
 * 本服务轮询该目录，在【宇宙 B（compiler_rootfs）】内执行 `./gradlew assembleDebug`，
 * 编译产物经共享 workspace 零延迟映射到物理硬盘；成功后静默安装并拉起，
 * 最后写入 result-<id>.json 并通知宿主。
 */
object BuildBridgeService : BridgeService("BuildBridge") {

    private const val BRIDGE_DIR = ".aidev-build-bridge"
    // 便携版 JDK17 (arm64 glibc, 自带完整 cacerts)，清华 Adoptium 镜像，免 apt/dpkg/debconf
    private const val JDK_TARBALL_URL =
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz"

    private var requestDir: File? = null

    // 按请求 id 跟踪：活跃协程 Job 与当前 PRoot 进程，供「取消」真正中止编译（否则只删标志不杀进程）。
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val activeProcesses = java.util.concurrent.ConcurrentHashMap<String, Process>()
    private val cancelledIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 取消指定 id 的构建请求：标记取消 → 强杀正在运行的 PRoot/Gradle 进程（解除阻塞读取）→
     * 取消协程 Job → 删除残留请求文件。runStreaming 检测到取消会抛 CancellationException，
     * handleRequest 捕获后写入「已取消」结果，UI 轮询即刷新为已取消。幂等。
     */
    fun cancel(id: String) {
        cancelledIds.add(id)
        activeProcesses.remove(id)?.let { runCatching { it.destroyForcibly() } }
        activeJobs.remove(id)?.cancel()
        val reqDir = requestDir
        if (reqDir != null) {
            reqDir.listFiles { _, n -> n.startsWith("req-$id.json") }?.forEach { runCatching { it.delete() } }
            // 若请求尚未被认领（无活跃 Job/进程），直接写结果让追踪器收敛
            if (!File(reqDir, "result-$id.json").isFile) {
                runCatching {
                    File(reqDir, "result-$id.json").writeText(
                        JSONObject().apply {
                            put("id", id); put("success", false); put("message", "已取消")
                            put("time", System.currentTimeMillis())
                        }.toString(2)
                    )
                }
            }
        }
    }

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also {
            it.mkdirs()
            File(it, "logs").mkdirs()
            // 清理上次被中断（应用被杀）残留的 .processing 认领文件，避免其永久滞留且不被重新调度。
            it.listFiles { f -> f.name.endsWith(".json.processing") }?.forEach { orphan ->
                runCatching { orphan.delete() }
            }
        }
    }

    override fun poll() {
        val reqDir = requestDir ?: return
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            scope?.launch { handleRequest(claimed) }
        }
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("BuildBridge", "read request failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("BuildBridge", "parse json failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val ctx = appCtx ?: run { processingFile.delete(); return }
        val id = json.optString("id", processingFile.nameWithoutExtension)
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { activeJobs[id] = it }
        val project = json.optString("project", "MyAndroidProject").ifBlank { "MyAndroidProject" }
        val autoInstall = json.optBoolean("autoInstall", true)
        val autoLaunch = json.optBoolean("autoLaunch", true)

        val log = StringBuilder()
        val logFile = File(requestDir, "logs/build-$id.log")

        // 单一真源：BuildBridge 无论请求来自手动按钮还是宇宙 A（OpenCode）终端，都把构建进度
        // 写入同一份 agent-tasks.json，AF 面板轮询即可看到一致的过程（准备→编译→安装→拉起）。
        val stateFile = File(PathConfig.tasksDir(ctx), "agent-tasks.json")
        val definition = AgentTaskDefinition(
            id = "build-$id",
            name = "构建 $project",
            description = "宇宙 B 编译 → 静默安装 → 自动拉起",
            command = "aidev-build-request --project $project",
            workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
            tags = listOf("build", "self-evolution")
        )
        val startedAt = System.currentTimeMillis()
        var lastPublish = 0L
        fun publishBuild(status: AgentTaskStatus, exitCode: Int, finishedAt: Long, steps: List<AgentTaskStepResult>) {
            val record = AgentTaskRecord(
                definition = definition,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitCode = exitCode,
                log = log.toString().takeLast(6000).ifBlank { "已提交构建请求，等待宇宙 B 调度…" },
                lastUpdatedAt = System.currentTimeMillis(),
                steps = steps
            )
            runCatching { AgentTaskStore.upsertTask(stateFile, record, limit = 12) }
        }

        val append: (String) -> Unit = { line ->
            log.appendLine(line)
            runCatching { logFile.appendText("$line\n") }
            // 节流发布（≥800ms），避免逐行重写状态文件
            val now = System.currentTimeMillis()
            if (now - lastPublish >= 800) {
                lastPublish = now
                publishBuild(AgentTaskStatus.RUNNING, -1, 0L, BuildProgress.derive(log.toString()))
            }
        }

        // 统一收尾：写 result-<id>.json + 导出日志（finish），并把最终状态发布到 AF 面板。
        fun finishAndPublish(success: Boolean, message: String, cancelled: Boolean = false) {
            finish(ctx, id, success, message, log, processingFile)
            val status = when {
                cancelled -> AgentTaskStatus.CANCELLED
                success -> AgentTaskStatus.SUCCEEDED
                else -> AgentTaskStatus.FAILED
            }
            publishBuild(
                status,
                if (success) 0 else 1,
                System.currentTimeMillis(),
                BuildProgress.finalize(BuildProgress.derive(log.toString()), success)
            )
        }

        append("=== BuildBridge 构建请求 $id (project=$project) ===")
        publishBuild(AgentTaskStatus.RUNNING, -1, 0L, emptyList())
        notify(ctx, "AIDev 构建", "开始编译 $project", priority = "default")

        try {
            // 1) 确保宇宙 B（编译器）就绪
            ensureCompilerRootfs(ctx, id, append)
            // 2) 解析项目路径（共享 workspace）
            val ws = PathConfig.workspaceDir(ctx)
            val projectDir = when {
                project.startsWith("/workspace/") -> File(ws, project.removePrefix("/workspace/").trimStart('/'))
                File(project).isAbsolute -> File(project)
                else -> File(ws, project.trimStart('/'))
            }
            val rel = projectDir.absolutePath.removePrefix(ws.absolutePath).trimStart('/')
            append("项目目录: ${projectDir.absolutePath}  (rel=/workspace/$rel)")

            if (!File(projectDir, "gradlew").isFile) {
                append("→ 未找到 gradlew，自动创建项目模板...")
                scaffoldProject(projectDir, ctx)
                if (!File(projectDir, "gradlew").isFile) {
                    append("✗ 自动创建项目失败")
                    finishAndPublish(false, "自动创建项目失败")
                    return
                }
                append("✓ 项目模板已创建")
            }

            // 2.5) 兜底：已有项目可能缺 gradle-wrapper.jar（宇宙 B 无 curl 无法自下载），从 assets 补齐
            if (!File(projectDir, "gradle/wrapper/gradle-wrapper.jar").let { it.isFile && it.length() > 0 }) {
                append("→ 缺少 gradle-wrapper.jar，从内置资源补齐...")
                if (ensureWrapperJar(projectDir, ctx)) append("✓ gradle-wrapper.jar 已补齐")
                else append("⚠ gradle-wrapper.jar 补齐失败")
            }

            // 2.6) 总是刷新构建基础设施（旧项目可能带 `java -jar` 的错误 gradlew 或缺兜底仓库的 settings）
            runCatching {
                val gradlewDest = File(projectDir, "gradlew")
                ctx.assets.open("scripts/gradlew").use { input ->
                    gradlewDest.outputStream().use { output -> input.copyTo(output) }
                }
                gradlewDest.setExecutable(true)
            }.onFailure { AIDevLogger.e("BuildBridge", "刷新 gradlew 失败", it) }
            runCatching { writeSettingsGradle(projectDir) }
                .onFailure { AIDevLogger.e("BuildBridge", "刷新 settings.gradle.kts 失败", it) }
            // 关键：把 aapt2(ARM64/QEMU) 包装等 init 脚本装进 GRADLE_USER_HOME/init.d，
            // 否则设了 GRADLE_USER_HOME 后 ~/.gradle/init.d 会被 Gradle 忽略，bundled x86_64 aapt2 起不来
            runCatching { TerminalShellAssets.installGradleUserHomeInit(File(PathConfig.aidevHome(ctx), "gradle-cache")) }
                .onFailure { AIDevLogger.e("BuildBridge", "安装 GRADLE_USER_HOME init.d 失败", it) }

            // 2.7) 预构建体检：扫描并修复 OpenCode 常见的、宇宙B 必失败的写法（vibe coding 护栏）
            runCatching { preflightCheck(projectDir, append) }
                .onFailure { AIDevLogger.e("BuildBridge", "预构建体检失败(非致命)", it) }

            // 3) 在宇宙 B 内编译
            val compilerRootfs = PathConfig.compilerRootfs(ctx).absolutePath
            val bind = ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")
            // aapt2 override 必须作为真·Gradle 属性(-P)传入；init.gradle 里 System.setProperty 对 AGP 无效。
            // 优先 34.0.0（36.1.0 的 aapt2 在 qemu 下损坏，见 docs/error-journal.md）。
            // 手机 universe B 无 binfmt 且无 x86_64 glibc，Google 的 aapt2 是动态 x86_64。
            // 自带一套匹配好的 x86_64 sysroot（aapt2.real + loader + glibc）+ qemu，包装脚本经
            // QEMU_LD_PREFIX + 显式 loader 运行（与宿主机成功方案一致），仅 aapt2 走 qemu。
            val aapt2Override = ensureX86Aapt2(ctx)
            val aapt2Arg = aapt2Override?.let { " -Pandroid.aapt2FromMavenOverride=$it" } ?: ""
            append(if (aapt2Override != null) "→ aapt2(x86_64/qemu) 就绪: $aapt2Override" else "⚠ aapt2 部署失败")
            // 探针：真机上直接验证 aapt2 能否运行（宿主机容器无法复现）
            if (aapt2Override != null) {
                append("→ 探测 aapt2 version ...")
                runStreaming(
                    ctx,
                    id,
                    "$aapt2Override version 2>&1; echo \"AAPT2_PROBE_EXIT=\$?\"",
                    ProotLauncher.Options(
                        rootfs = PathConfig.compilerRootfs(ctx).absolutePath,
                        cwd = "/root",
                        binds = listOf(ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")),
                        env = mapOf("ANDROID_SDK_ROOT" to "/host-home/android-sdk"),
                        timeoutSec = 120,
                        redirectErrorStream = true
                    ),
                    append,
                    heartbeat = "探测 aapt2"
                )
            }
            append("→ 进入宇宙 B 编译: cd /workspace/$rel && ./gradlew assembleDebug")
            val exit = runStreaming(
                ctx,
                id,
                "cd /workspace/$rel && chmod +x gradlew && ./gradlew assembleDebug --no-daemon$aapt2Arg",
                ProotLauncher.Options(
                    rootfs = compilerRootfs,
                    cwd = "/workspace/$rel",
                    binds = listOf(bind),
                    env = mapOf(
                        "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                        "GRADLE_USER_HOME" to "/host-home/gradle-cache",
                        "JAVA_HOME" to "/host-home/jdk17",
                        "PATH" to "/host-home/jdk17/bin:/host-home/dev-env/bin:/system/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                    ),
                    timeoutSec = 900,
                    redirectErrorStream = true
                ),
                append,
                heartbeat = "编译中（首次会下载 Gradle 分发与依赖）"
            )

            if (exit != 0) {
                append("✗ 构建失败 (exit=$exit)")
                finishAndPublish(false, "构建失败 (exit=$exit)")
                return
            }
            append("✓ 构建成功")

            // 4) 安装 + 拉起
            val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
            if (!apk.isFile) {
                append("✗ 未找到产物 APK: ${apk.absolutePath}")
                finishAndPublish(true, "构建成功但缺少 APK")
                return
            }
            if (autoInstall) {
                val pkg = installAndLaunch(ctx, apk, autoLaunch, append)
                val msg = when {
                    pkg == null -> "构建成功，但安装未完成（详见日志）"
                    autoLaunch -> "构建成功并安装 (已拉起 $pkg)"
                    else -> "构建成功并安装 ($pkg)"
                }
                finishAndPublish(true, msg)
                // 自动触发崩溃回传：给刚启动的 App 几秒运行时间后抓取 logcat
                if (autoLaunch && pkg != null) {
                    scope?.launch {
                        kotlinx.coroutines.delay(8000)
                        runCatching {
                            val crDir = File(PathConfig.aidevHome(ctx), ".aidev-crash-bridge").apply { mkdirs() }
                            val cr = JSONObject().apply { put("package", pkg); put("lines", 1000) }
                            File(crDir, "req-${System.currentTimeMillis()}.json").writeText(cr.toString())
                        }
                    }
                }
            } else {
                finishAndPublish(true, "构建成功")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            append("⏹ 已取消")
            finishAndPublish(false, "已取消", cancelled = true)
        } catch (e: Exception) {
            append("✗ ${e.message}")
            finishAndPublish(false, e.message ?: "异常")
        } finally {
            activeJobs.remove(id)
            activeProcesses.remove(id)
            cancelledIds.remove(id)
        }
    }

    // ─── 自动项目模板 ──────────────────────────────────────────────────────

    /**
     * 在 projectDir 下创建完整的 Android 项目模板，使 BuildBridgeService 可以
     * 在没有用户手动操作的情况下编译出可安装的 APK。
     */
    /** 从 assets 拷贝 gradle-wrapper.jar，缺失才写入。宇宙 B 无 curl/wget，禁止网络下载。 */
    private fun ensureWrapperJar(projectDir: File, ctx: Context): Boolean {
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        if (wrapperJar.isFile && wrapperJar.length() > 0) return true
        wrapperJar.parentFile?.mkdirs()
        return runCatching {
            ctx.assets.open("scripts/gradle-wrapper.jar").use { input ->
                wrapperJar.outputStream().use { output -> input.copyTo(output) }
            }
            wrapperJar.isFile && wrapperJar.length() > 0
        }.getOrElse {
            AIDevLogger.e("BuildBridge", "拷贝 gradle-wrapper.jar 失败", it)
            false
        }
    }

    /** 由项目目录名推导合法包名，避免脚手架硬编码 com.example.myandroidproject 与用户源码冲突。 */
    private fun derivePackage(projectName: String): String {
        val slug = projectName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "app" }
        val safe = if (slug.first().isDigit()) "a$slug" else slug
        return "com.aidev.app.$safe"
    }

    /** 由项目目录名推导合法主题名后缀（仅字母数字）。 */
    private fun deriveThemeSuffix(projectName: String): String =
        projectName.filter { it.isLetterOrDigit() }.ifBlank { "App" }

    private fun scaffoldProject(projectDir: File, ctx: Context) {
        projectDir.mkdirs()
        val projectName = projectDir.name.ifBlank { "MyAndroidProject" }
        val pkg = derivePackage(projectName)
        val pkgPath = pkg.replace('.', '/')
        val themeName = "Theme.${deriveThemeSuffix(projectName)}"

        // gradlew 脚本（从 assets 加载，避免 Kotlin 字符串模板转义问题）
        val gradlewDest = File(projectDir, "gradlew")
        runCatching {
            ctx.assets.open("scripts/gradlew").use { input ->
                gradlewDest.outputStream().use { output -> input.copyTo(output) }
            }
            gradlewDest.setExecutable(true)
        }.onFailure {
            AIDevLogger.e("BuildBridge", "Failed to copy gradlew from assets", it)
        }

        // gradle-wrapper.properties
        File(projectDir, "gradle/wrapper").mkdirs()
        File(projectDir, "gradle/wrapper/gradle-wrapper.properties").writeText(
            """distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists"""
        )

        // gradle-wrapper.jar: 直接从 assets 拷贝（宇宙 B 无 curl，禁止网络下载）
        ensureWrapperJar(projectDir, ctx)

        // build.gradle.kts (root)
        File(projectDir, "build.gradle.kts").writeText(
            """plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}"""
        )

        // settings.gradle.kts
        writeSettingsGradle(projectDir)

        // gradle.properties
        File(projectDir, "gradle.properties").writeText(
            """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true"""
        )

        // local.properties（SDK 路径指向宇宙 B 内挂载的 SDK）
        File(projectDir, "local.properties").writeText("sdk.dir=/Android")

        // .gitignore
        File(projectDir, ".gitignore").writeText(
            """.gradle/
build/
/local.properties
*.iml
.idea/
.navigation/
captures/
.externalNativeBuild/
.cxx/"""
        )

        // app/build.gradle.kts
        File(projectDir, "app").mkdirs()
        val appDir = File(projectDir, "app/src/main")
        File(projectDir, "app/build.gradle.kts").writeText(
            """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "$pkg"
    compileSdk = 36

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}"""
        )

        File(projectDir, "app/proguard-rules.pro").writeText("# Proguard rules\n")

        // AndroidManifest.xml
        appDir.mkdirs()
        File(appDir, "AndroidManifest.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/$themeName">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/$themeName">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>"""
        )

        // MainActivity.kt
        File(appDir, "java/$pkgPath").mkdirs()
        File(appDir, "java/$pkgPath/MainActivity.kt").writeText(
            """package $pkg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}"""
        )

        // Resources
        val resDir = File(appDir, "res")
        File(resDir, "layout").mkdirs()
        File(resDir, "values").mkdirs()
        File(resDir, "drawable").mkdirs()
        File(resDir, "mipmap-anydpi-v26").mkdirs()

        File(resDir, "layout/activity_main.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hello $projectName!"
        android:textSize="24sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>"""
        )

        File(resDir, "values/strings.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$projectName</string>
</resources>"""
        )

        File(resDir, "values/themes.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="$themeName" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
        <item name="colorPrimary">#6200EE</item>
        <item name="colorPrimaryVariant">#3700B3</item>
        <item name="colorOnPrimary">#FFFFFF</item>
        <item name="colorSecondary">#03DAC5</item>
        <item name="colorSecondaryVariant">#018786</item>
        <item name="colorOnSecondary">#000000</item>
    </style>
</resources>"""
        )

        File(resDir, "drawable/ic_launcher_foreground.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0F2E22"
        android:fillAlpha="0.65"
        android:pathData="M54,2a52,52 0 1,0 0.001,0z" />
    <path
        android:fillColor="#EAF7EE"
        android:strokeColor="#00E676"
        android:strokeWidth="3"
        android:strokeLineJoin="round"
        android:pathData="M30,40 a8,8 0 0 1 8,-8 h32 a8,8 0 0 1 8,8 v32 a8,8 0 0 1 -8,8 h-32 a8,8 0 0 1 -8,-8 z" />
    <path
        android:fillColor="@android:color/transparent"
        android:strokeColor="#00C853"
        android:strokeWidth="5"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="M44,50 L54,58 L44,66" />
    <path
        android:fillColor="#00C853"
        android:pathData="M60,50 h4 v16 h-4 z" />
    <path
        android:fillColor="#69F0AE"
        android:pathData="M82,22 C82,27.6 87.6,30 90,30 C87.6,30 82,32.4 82,38 C82,32.4 76.4,30 74,30 C76.4,30 82,27.6 82,22 Z" />
</vector>"""
        )

        File(resDir, "drawable/ic_launcher_background.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#07131E"
        android:pathData="M0,0h108v108H0z" />
</vector>"""
        )

        File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>"""
        )

        File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>"""
        )
    }

    // ─── 流式执行（逐行输出 + 心跳，避免长任务 UI 静默）───────────────────

    /**
     * 流式执行 PRoot 命令：stdout 逐行写入 [append]（UI 通过 tail 日志实时看到进度）；
     * 若输出静默超过 15s，打印一条"仍在进行"心跳，防止用户误判卡死。
     * @return 退出码；超时或异常返回 -1。
     */
    private fun runStreaming(
        ctx: Context,
        id: String,
        command: String,
        opts: ProotLauncher.Options,
        append: (String) -> Unit,
        heartbeat: String
    ): Int {
        val process = ProotLauncher.start(ctx, command, opts.copy(redirectErrorStream = true))
        activeProcesses[id] = process
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val lastOutput = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()
        val hb = Thread {
            while (running.get()) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                if (!running.get()) break
                if (System.currentTimeMillis() - lastOutput.get() >= 15000) {
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    append("… $heartbeat（已用时 ${sec}s，仍在进行）")
                    lastOutput.set(System.currentTimeMillis())
                }
            }
        }.apply { isDaemon = true; start() }
        val exit = try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    lastOutput.set(System.currentTimeMillis())
                    line = reader.readLine()
                }
            }
            val exited = runCatching { process.waitFor(opts.timeoutSec, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if (exited) process.exitValue() else { process.destroyForcibly(); -1 }
        } catch (e: Exception) {
            append("✗ 执行异常: ${e.message}")
            process.destroyForcibly(); -1
        } finally {
            running.set(false)
            hb.interrupt()
            activeProcesses.remove(id, process)
        }
        // 被取消：进程已被强杀（上面读取到 EOF/异常返回），抛出以让 handleRequest 收敛为「已取消」。
        if (id in cancelledIds) throw kotlinx.coroutines.CancellationException("已取消")
        return exit
    }

    // ─── 编译器 rootfs 初始化 ─────────────────────────────────────────────

    private fun ensureCompilerRootfs(ctx: Context, id: String, append: (String) -> Unit) {
        val compilerRootfs = PathConfig.compilerRootfs(ctx)
        if (File(compilerRootfs, ".aidev-rootfs-ready").isFile &&
            File(compilerRootfs, "usr/bin/bash").isFile
        ) {
            append("宇宙 B 已就绪: ${compilerRootfs.absolutePath}")
            // 即使 base 已就绪，也要确保 JDK 存在（首次可能 apt 失败导致缺 java）
            ensureJdk(ctx, id, append)
            return
        }
        append("→ 准备宇宙 B（编译器 rootfs）...")

        // Step 1: 在 agent rootfs 内调用 aidev-ubuntu-core install-ubuntu，
        // 通过环境变量覆盖 AIDEV_HOME / AIDEV_ROOTFS / AIDEV_PROOT，
        // 使 Ubuntu base 安装到 compiler_rootfs 而非默认的 ubuntu-rootfs。
        val agentRootfs = PathConfig.agentRootfs(ctx).absolutePath
        val installExit = runStreaming(
            ctx,
            id,
            "AIDEV_HOME=/host-home " +
                "AIDEV_ROOTFS=/host-home/compiler_rootfs " +
                "AIDEV_PROOT=/bin/true " +
                "sh /host-home/dev-env/bin/aidev-ubuntu-core install-ubuntu",
            ProotLauncher.Options(
                rootfs = agentRootfs,
                timeoutSec = 600,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "准备宇宙 B 基础系统（首次下载解压 Ubuntu base）"
        )
        if (installExit != 0) {
            append("⚠ 宇宙 B Ubuntu 基础环境安装失败(exit=$installExit)，编译可能失败")
        }

        // Step 2: 在 compiler_rootfs 内安装 JDK 17
        ensureJdk(ctx, id, append)
    }

    /** 从 assets 拷贝单个文件到目标（不存在或空才拷），可执行。 */
    private fun copyAsset(ctx: Context, assetPath: String, dst: File, exec: Boolean) {
        if (dst.isFile && dst.length() > 0L) { if (exec) dst.setExecutable(true, false); return }
        dst.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        if (exec) dst.setExecutable(true, false)
    }

    /**
     * 部署自带的 x86_64 aapt2 运行环境（qemu + aapt2.real + loader + glibc），生成包装脚本，
     * 返回其在 universe B 内的路径 (/host-home/x86_64/aapt2)。失败返回 null。幂等。
     * 原理同宿主机成功方案：AGP exec 原生 sh 包装 → QEMU_LD_PREFIX + 显式 x86_64 loader 运行 aapt2.real。
     */
    private fun ensureX86Aapt2(ctx: Context): String? {
        return runCatching {
            val home = PathConfig.aidevHome(ctx)
            copyAsset(ctx, "tools/qemu-x86_64-static", File(home, "qemu-x86_64-static"), exec = true)
            val x86 = File(home, "x86_64")
            copyAsset(ctx, "tools/x86_64/aapt2.real", File(x86, "aapt2.real"), exec = true)
            val libs = listOf(
                "ld-linux-x86-64.so.2", "libc.so.6", "libm.so.6", "libdl.so.2",
                "libpthread.so.0", "librt.so.1", "libgcc_s.so.1", "libstdc++.so.6", "libz.so.1"
            )
            for (l in libs) copyAsset(ctx, "tools/x86_64/lib/$l", File(x86, "lib/$l"), exec = true)
            val wrapper = File(x86, "aapt2")
            wrapper.writeText(
                "#!/bin/sh\n" +
                "DIR=/host-home/x86_64\n" +
                "export QEMU_LD_PREFIX=\$DIR\n" +
                "exec /host-home/qemu-x86_64-static \$DIR/lib/ld-linux-x86-64.so.2 --library-path \$DIR/lib \$DIR/aapt2.real \"\$@\"\n"
            )
            wrapper.setExecutable(true, false)
            "/host-home/x86_64/aapt2"
        }.getOrNull()
    }

    /**
     * 写入健壮的 settings.gradle.kts：阿里云 public(聚合) + google 镜像优先，
     * 官方 google()/mavenCentral() 兜底——任一镜像 502/被禁用时仍可从其它仓库解析。
     */
    private fun writeSettingsGradle(projectDir: File) {
        // 保留已有 settings 里的 rootProject.name（用户/OpenCode 命名），否则用目录名——不再硬编码 MyAndroidProject
        val settingsFile = File(projectDir, "settings.gradle.kts")
        val existingName = runCatching {
            if (settingsFile.isFile) {
                Regex("""rootProject\.name\s*=\s*"([^"]+)"""").find(settingsFile.readText())?.groupValues?.get(1)
            } else null
        }.getOrNull()
        val projectName = existingName?.takeIf { it.isNotBlank() }
            ?: projectDir.name.ifBlank { "MyAndroidProject" }
        settingsFile.writeText(
            """pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}
rootProject.name = "$projectName"
include(":app")"""
        )
    }

    /**
     * 预构建体检：针对 vibe coding 小白，OpenCode 生成的代码常犯几类宇宙B 必失败的错误。
     * 编译前扫描 app/build.gradle.kts：
     *  1) 模块级 repositories{} → 自动移除（settings 已开 FAIL_ON_PROJECT_REPOS，统一阿里云镜像）
     *  2) compileSdk ≠ 36 → 告警（宇宙B 只装了 android-36）
     *  3) 使用 Compose 但配置不完整 → 告警
     * 全部尽力而为，失败不阻断构建；提示以中文写入构建日志，小白可读。
     */
    private fun preflightCheck(projectDir: File, append: (String) -> Unit) {
        val gradleFile = File(projectDir, "app/build.gradle.kts")
        if (!gradleFile.isFile) return
        val original = runCatching { gradleFile.readText() }.getOrNull() ?: return
        val rootGradle = runCatching { File(projectDir, "build.gradle.kts").readText() }.getOrDefault("")
        val result = BuildPreflight.inspect(original, rootGradle)
        result.messages.forEach(append)
        if (result.fixedText != original) {
            runCatching { gradleFile.writeText(result.fixedText) }
                .onFailure { append("⚠ 体检：自动修复写回失败: ${it.message}") }
        }
    }

    /**
     * 确保宇宙 B 内 java 可用。采用便携版 JDK tarball（自带完整 cacerts），
     * 免 apt/dpkg/debconf——本机 rootfs 的 dpkg/debconf 环境损坏，apt 装 openjdk 的 postinst 必崩。
     * JDK 解压到共享持久目录 /host-home/jdk17，跨重建复用。幂等。
     */
    private fun ensureJdk(ctx: Context, id: String, append: (String) -> Unit) {
        val compilerRootfs = PathConfig.compilerRootfs(ctx)
        val wsBind = ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")
        append("→ 检查/安装宇宙 B JDK17（便携版，免 apt）...")

        // 便携 JDK 下载需要 curl + CA；rootfs 无 curl，从内置资源部署静态 curl 与 CA 包
        runCatching {
            val curlDst = File(compilerRootfs, "usr/local/bin/curl")
            if (!curlDst.isFile || curlDst.length() == 0L) {
                curlDst.parentFile?.mkdirs()
                ctx.assets.open("tools/curl").use { i -> curlDst.outputStream().use { o -> i.copyTo(o) } }
                curlDst.setExecutable(true)
            }
            val caDst = File(compilerRootfs, "etc/ssl/certs/ca-certificates.crt")
            if (!caDst.isFile) {
                caDst.parentFile?.mkdirs()
                ctx.assets.open("tools/ca-certificates.crt").use { i -> caDst.outputStream().use { o -> i.copyTo(o) } }
            }
        }.onFailure { AIDevLogger.e("BuildBridge", "部署 curl/ca 到宇宙 B 失败", it) }

        val script = """
            set -u
            JDK_DIR=/host-home/jdk17
            if [ -x "${'$'}JDK_DIR/bin/java" ]; then ln -sf "${'$'}JDK_DIR/bin/java" /usr/bin/java 2>/dev/null || true; "${'$'}JDK_DIR/bin/java" -version 2>&1 | head -1; exit 0; fi
            if command -v java >/dev/null 2>&1; then java -version 2>&1 | head -1; exit 0; fi
            export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
            CURL=/usr/local/bin/curl
            [ -x "${'$'}CURL" ] || CURL=curl
            echo "下载便携 JDK17（约 192MB，首次较慢）..."
            "${'$'}CURL" -fL --retry 3 -o /host-home/jdk17.tar.gz "$JDK_TARBALL_URL" || "${'$'}CURL" -fkL --retry 3 -o /host-home/jdk17.tar.gz "$JDK_TARBALL_URL"
            echo "解压 JDK..."
            rm -rf "${'$'}JDK_DIR"; mkdir -p "${'$'}JDK_DIR"
            tar -xzf /host-home/jdk17.tar.gz -C "${'$'}JDK_DIR" --strip-components=1
            rm -f /host-home/jdk17.tar.gz
            mkdir -p /usr/bin
            ln -sf "${'$'}JDK_DIR/bin/java" /usr/bin/java
            ln -sf "${'$'}JDK_DIR/bin/javac" /usr/bin/javac
            "${'$'}JDK_DIR/bin/java" -version 2>&1 | head -1
        """.trimIndent()
        val javaExit = runStreaming(
            ctx,
            id,
            script,
            ProotLauncher.Options(
                rootfs = compilerRootfs.absolutePath,
                cwd = "/root",
                binds = listOf(wsBind),
                env = mapOf(
                    "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache"
                ),
                timeoutSec = 1800,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "下载/解压便携 JDK17"
        )
        append("→ 宇宙 B JDK17: ${if (javaExit == 0) "OK" else "返回非零(可重试)"}")
    }

    private suspend fun installAndLaunch(ctx: Context, apk: File, autoLaunch: Boolean, append: (String) -> Unit): String? {
        val sd = File("/sdcard/AIDev").apply { mkdirs() }
        val dst = File(sd, apk.name)
        runCatching { apk.copyTo(dst, overwrite = true) }.onFailure {
            append("✗ 复制 APK 到 /sdcard 失败: ${it.message}")
            return null
        }
        val state = ShizukuLogcat.checkState(ctx)
        if (state !is ShizukuState.Ready) {
            append("⚠ Shizuku 未就绪（${state.javaClass.simpleName}），跳过安装。APK 已位于 ${dst.absolutePath}")
            return null
        }
        val pkg = runCatching { ctx.packageManager.getPackageArchiveInfo(dst.absolutePath, 0)?.packageName }.getOrNull()
        // 安装：等待真实结果，而非 fire-and-forget
        val tmp = "/data/local/tmp/aidev-install-${Math.abs(dst.name.hashCode())}.apk"
        val ir = ShizukuLogcat.executeCommand(
            "cp '${dst.absolutePath}' '$tmp' && pm install -r -d '$tmp'; RC=\$?; rm -f '$tmp'; exit \$RC"
        )
        val out = (ir.stdout + "\n" + ir.stderr).trim()
        if (ir.exitCode != 0 || out.contains("Failure", ignoreCase = true)) {
            append("✗ 安装失败 (exit=${ir.exitCode}): ${ShizukuLogcat.pmInstallErrorHint(ir)}")
            if (out.isNotBlank()) append("  详情: ${out.take(500)}")
            return null
        }
        append("→ 已安装 ${dst.name}${if (pkg != null) " ($pkg)" else ""}")
        if (autoLaunch && pkg != null) {
            // 新装 App 处于 stopped 状态，`am start -p` 默认排除 stopped 包会解析失败；
            // 先解析出 launcher 组件再 `am start -n`（含 FLAG_INCLUDE_STOPPED_PACKAGES），失败回退 monkey。
            val comp = ShizukuLogcat.executeCommand(
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg | tail -1"
            ).stdout.trim().lines().lastOrNull()?.trim().orEmpty()
            val lr = if (comp.contains("/")) {
                ShizukuLogcat.executeCommand("am start -n $comp")
            } else {
                ShizukuLogcat.executeCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            }
            val lout = (lr.stdout + "\n" + lr.stderr).trim()
            if (lr.exitCode != 0 || lout.contains("Error:", ignoreCase = true) || lout.contains("No activities", ignoreCase = true)) {
                append("✗ 拉起失败 (exit=${lr.exitCode}): ${lout.take(300)}")
            } else {
                append("→ 已拉起 $pkg${if (comp.contains("/")) " ($comp)" else ""}")
            }
        }
        return pkg
    }

    private fun finish(ctx: Context, id: String, success: Boolean, message: String, log: StringBuilder, reqFile: File) {
        val result = JSONObject().apply {
            put("id", id)
            put("success", success)
            put("message", message)
            put("time", System.currentTimeMillis())
        }
        runCatching {
            File(requestDir, "result-$id.json").writeText(result.toString(2))
        }
        // 始终把完整构建日志导出到 /sdcard，方便宿主/用户直接取到根因（app 私有日志无法外部访问）
        runCatching {
            val outDir = File("/sdcard/AIDev").apply { mkdirs() }
            val header = "# build id=$id success=$success msg=$message time=${System.currentTimeMillis()}\n\n"
            File(outDir, "last-build.log").writeText(header + log.toString())
            File(outDir, "last-build-$id.log").writeText(header + log.toString())
        }
        reqFile.delete()
        notify(ctx, if (success) "AIDev 构建完成" else "AIDev 构建失败", message, priority = "high")
        AIDevLogger.i("BuildBridge", "request $id done success=$success msg=$message")
    }

    private fun notify(ctx: Context, title: String, msg: String, priority: String) {
        runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
    }
}
