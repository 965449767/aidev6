package com.aidev.six

import android.content.Context
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStepResult
import com.aidev.six.agent.AgentTaskStore
import com.aidev.six.agent.BuildProgress
import com.aidev.six.agent.BuildProgress.Phase
import com.aidev.six.agent.ProjectTaskLock
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 自我进化闭环的构建桥。
 *
 * OpenCode（宇宙 A）通过其工具 `aidev-build-request` 写入结构化请求文件：
 *   home/.aidev-build-bridge/req-<id>.json
 *   { "project": "<workspace 下相对路径或 /workspace/...>", "flavor": "debug" }
 *
 * 构建黑盒只负责出产物（apk_path），部署（安装+启动）交由独立的部署黑盒 aidev-deploy。
 *
 * 本服务轮询该目录，在【宇宙 B（compiler_rootfs）】内执行 `./gradlew assembleDebug`，
 * 编译产物经共享 workspace 零延迟映射到物理硬盘；成功后静默安装并拉起，
 * 最后写入 result-<id>.json 并通知宿主。
 */
object BuildBridgeService : BridgeService("BuildBridge") {

    private const val BRIDGE_DIR = ".aidev-build-bridge"
    // 便携版 JDK17 (arm64 glibc, 自带完整 cacerts)，多镜像 fallback，免 apt/dpkg/debconf
    private const val JDK_SHA256 = "83a52172678ec8975164648654869cb2e71d7c748b47aca94b29bbfa10c18e81"

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

    override fun poll(): Boolean {
        val reqDir = requestDir ?: return false
        var hadWork = false
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            hadWork = true
            scope?.launch { handleRequest(claimed) }
        }
        return hadWork
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
        val project = (json.optString("project", "MyAndroidProject") ?: "MyAndroidProject").let { if (it.isBlank()) "MyAndroidProject" else it }

        val log = StringBuilder()
        val logWriter = LogHub.openBuildLog(PathConfig.logsDir(ctx), project)
        val timer = LogHub.StepTimer(logWriter)
        val gradleFilter = LogHub.GradleFilter()

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
        var currentPhase = Phase.PREPARE
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
            // Gradle 输出过滤：减少噪音，保留关键信息
            if (gradleFilter.shouldKeep(line)) {
                logWriter.append(line)
            }
            // 节流发布（≥800ms），避免逐行重写状态文件
            val now = System.currentTimeMillis()
            if (now - lastPublish >= 800) {
                lastPublish = now
                publishBuild(AgentTaskStatus.RUNNING, -1, 0L, BuildProgress.deriveUpTo(currentPhase))
            }
        }

        // 统一收尾：写 result-<id>.json + 导出日志（finish），并把最终状态发布到 AF 面板。
        fun finishAndPublish(success: Boolean, message: String, cancelled: Boolean = false, apkPath: String? = null, logPath: String? = null, pkg: String? = null) {
            // 输出折叠的警告摘要
            val warningSummary = gradleFilter.flushWarnings()
            if (warningSummary != null) {
                log.appendLine(warningSummary)
                logWriter.append(warningSummary)
            }
            // 构建失败时追加错误摘要块
            if (!success && !cancelled) {
                val errorLines = log.toString().lines().filter { line ->
                    line.contains("error:", ignoreCase = true) ||
                    line.contains("FAILED") ||
                    line.contains("Exception") ||
                    line.contains("What went wrong")
                }.take(10)
                if (errorLines.isNotEmpty()) {
                    val summary = buildString {
                        appendLine()
                        appendLine("━━━ 错误摘要 ━━━")
                        errorLines.forEach { appendLine("  $it") }
                        appendLine("━━━━━━━━━━━━━━")
                    }
                    log.appendLine(summary)
                    logWriter.append(summary)
                }
                // 构建失败回流：写入 .aidev-loop/ 供宇宙 A 自动修复
                writeLoopBuildFailure(ctx, id, project, log.toString())
            }
            timer.endStep(if (success) "成功" else "失败")
            logWriter.finish()
            // 重命名日志：build-<project>-<id>-<SUCCESS|FAILED>.log
            val suffix = when {
                cancelled -> "CANCELLED"
                success -> "SUCCESS"
                else -> "FAILED"
            }
            runCatching { LogHub.saveProfile(PathConfig.logsDir(ctx), timer.profileJson(), "build", project) }
            finish(ctx, id, success, message, log, processingFile, apkPath, logPath, pkg, project)
            val status = when {
                cancelled -> AgentTaskStatus.CANCELLED
                success -> AgentTaskStatus.SUCCEEDED
                else -> AgentTaskStatus.FAILED
            }
            publishBuild(
                status,
                if (success) 0 else 1,
                System.currentTimeMillis(),
                BuildProgress.finalize(BuildProgress.deriveUpTo(currentPhase), success)
            )
        }

        // 按项目单写锁：同一项目同时只允许一个构建/写任务（防两个前端并发改文件/构建冲突）。
        val ws = PathConfig.workspaceDir(ctx)
        val projectDir = resolveProjectDir(ws, project)
        val lockKey = projectDir.absolutePath
        if (!ProjectTaskLock.tryAcquire(lockKey, "build:$id")) {
            val holder = ProjectTaskLock.holder(lockKey)
            append("⏸ 该项目已有任务进行中（${holder?.source ?: "?"}），本次请求跳过")
            publishBuild(AgentTaskStatus.RUNNING, -1, 0L, emptyList())
            finishAndPublish(false, "该项目已有任务进行中，已跳过")
            return
        }

        append("=== BuildBridge 构建请求 $id (project=$project) ===")
        publishBuild(AgentTaskStatus.RUNNING, -1, 0L, emptyList())
        notify(ctx, "AIDev 构建", "开始编译 $project", priority = "default")

        try {
            // 1) 确保宇宙 B（编译器）就绪
            timer.beginStep("准备宇宙 B")
            ensureCompilerRootfs(ctx, id, append)
            timer.endStep("rootfs + JDK + aapt2")
            // 2) 解析项目路径（共享 workspace）
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

            // 2.7b) 构建前硬护栏：命中 HARD_BLOCKER 或离线缺基线 → 明确报错，不浪费编译时间
            val pre = BuildPreflight.checkPreconditions(projectDir)
            pre.warnings.forEach(append)
            if (pre.hardErrors.isNotEmpty()) {
                pre.hardErrors.forEach(append)
                finishAndPublish(false, "构建前护栏拦截：${pre.hardErrors.first().removePrefix("✖ ")}")
                return
            }

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

            // 2.8) 自动预热宇宙 B 依赖缓存（首次在线构建时）：确保断网也能离线构建
            // 宇宙 B 构建用 GRADLE_USER_HOME=/host-home/gradle-cache，对应宿主 filesDir/home/gradle-cache。
            // 若其缺基线标记且当前联网，先解析依赖把缓存落进去，后续断网 assembleDebug 即可离线。
            val ubMarker = File(PathConfig.aidevHome(ctx), "gradle-cache/caches/modules-2/files-2.1/androidx.compose.material/material-icons-extended")
            if (!ubMarker.isDirectory) {
                if (isOnline()) {
                    append("→ 预热宇宙 B 依赖缓存（首次在线构建）...")
                    runStreaming(
                        ctx, id,
                        "cd /workspace/$rel && ./gradlew dependencies --no-daemon$aapt2Arg",
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
                            timeoutSec = 600,
                            redirectErrorStream = true
                        ),
                        append,
                        heartbeat = "预热宇宙 B 缓存"
                    )
                    append("→ 宇宙 B 依赖缓存预热完成（断网可离线构建）")
                } else {
                    append("⚠ 宇宙 B 依赖缓存缺失且当前离线；若构建报缺包，请联网后运行 aidev-precache 再构建。")
                }
            }
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
            currentPhase = Phase.COMPILE
            val buildDir = File(projectDir, "app/build")
            val buildType = if (buildDir.isDirectory && buildDir.listFiles()?.isNotEmpty() == true) "增量编译" else "全量编译"
            timer.beginStep("编译 ($buildType)")
            append("→ 进入宇宙 B 编译（$buildType）: cd /workspace/$rel && ./gradlew assembleDebug")
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

            // 退出码不可靠（PRoot 双层 sh 在部分失败场景会吞掉真实退出码），
            // 必须同时扫描日志里的 BUILD FAILED，否则会误判成功并安装旧 APK。
            val buildFailed = exit != 0 || log.contains("BUILD FAILED")
            if (buildFailed) {
                timer.endStep("编译失败 (exit=$exit)")
                append("✗ 构建失败 (exit=$exit)")
                val hints = BuildDiagnostics.diagnoseBuildErrors(log.toString())
                hints.forEach { append("💡 $it") }
                finishAndPublish(false, "构建失败 (exit=$exit)")
                return
            }
            timer.endStep("编译成功")

            // 4) 产物解析（构建黑盒只负责出产物；部署交由独立的部署黑盒 aidev-deploy）
            // 宇宙 B 标准出口：产物路径优先取 Gradle 标准输出（UP-TO-DATE 时即为有效产物，
            // 不应因 mtime 被怀疑）；其次宇宙 B 复制到 /sdcard 的副本（copy-apk.gradle 的
            // "AIDev: APK ->"）。两边都要求文件真实存在，绝不信任一个并不存在的路径。
            val stdApk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
            val apkFromBuild = Regex("AIDev:\\s*APK\\s*->\\s*(\\S+)").find(log.toString())?.groupValues?.getOrNull(1)
            val apk = when {
                stdApk.isFile -> stdApk
                apkFromBuild != null && File(apkFromBuild).isFile -> File(apkFromBuild)
                else -> null
            }
            if (apk == null) {
                append("✗ 构建成功但找不到有效产物 APK（Gradle 产物与宇宙 B 副本均缺失）: ${stdApk.absolutePath}")
                finishAndPublish(false, "构建成功但缺少 APK")
                return
            }
            // 标准出口：完整日志路径（LogHub 写的 build.log）+ 产物路径，写入 result-<id>.json。
            val buildLogPath = File(File(PathConfig.logsDir(ctx), project), "build.log").absolutePath
            // 构建成功：此前的失败回流产物已失效，清理掉避免手动按钮误读旧失败。
            runCatching {
                File(File(PathConfig.logsDir(ctx), project), "last-build-failure.log").delete()
                listOf(
                    File(PathConfig.aidevHome(ctx), ".aidev-loop"),
                    File(PathConfig.logsDir(ctx), project)
                ).forEach { d ->
                    d.listFiles { f -> f.name.matches(Regex("build-failure-.*\\.json")) }?.forEach { it.delete() }
                }
            }
            // 部署（安装+启动）是独立的部署黑盒，构建黑盒不越界，避免误报"已拉起"。
            // 解析产物包名，供面板「安装/拉起」按钮与部署黑盒直接使用，避免再次猜测。
            val pkg = extractPackage(ctx, apk.absolutePath, append)
            if (pkg.isNotBlank()) append("→ 产物包名: $pkg")
            finishAndPublish(true, "构建成功: ${apk.absolutePath}", apkPath = apk.absolutePath, logPath = buildLogPath, pkg = pkg.ifBlank { null })
        } catch (e: kotlinx.coroutines.CancellationException) {
            append("⏹ 已取消")
            finishAndPublish(false, "已取消", cancelled = true)
        } catch (e: Exception) {
            append("✗ ${e.message}")
            finishAndPublish(false, e.message ?: "异常")
        } finally {
            ProjectTaskLock.release(lockKey)
            activeJobs.remove(id)
            activeProcesses.remove(id)
            cancelledIds.remove(id)
        }
    }

    /** 解析请求里的 project 字段为共享 workspace 下的实际目录（兼容相对/绝对/`/workspace/` 前缀）。 */
    private fun resolveProjectDir(ws: File, project: String): File = when {
        project.startsWith("/workspace/") -> File(ws, project.removePrefix("/workspace/").trimStart('/'))
        File(project).isAbsolute -> File(project)
        else -> File(ws, project.trimStart('/'))
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
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
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
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "$pkg"
    compileSdk = 35

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 35
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
    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose")
    debugImplementation("androidx.compose.ui:ui-tooling")
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
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Hello $projectName!")
        }
    }
}"""
        )

        // Resources
        val resDir = File(appDir, "res")
        File(resDir, "values").mkdirs()
        File(resDir, "drawable").mkdirs()
        File(resDir, "mipmap-anydpi-v26").mkdirs()

        File(resDir, "values/strings.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$projectName</string>
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
     * 从编译产物 APK 解析包名（applicationId），供部署黑盒与面板「安装/拉起」按钮直接使用。
     * 用 aidev-deploy 同源的 aapt2（x86_64/qemu）dump badging；失败返回空串（部署按钮将提示先成功构建）。
     */
    private fun extractPackage(ctx: Context, apk: String, append: (String) -> Unit): String {
        val aapt2 = ensureX86Aapt2(ctx) ?: return ""
        val ws = PathConfig.workspaceDir(ctx)
        val prootApk = if (apk.startsWith(ws.absolutePath)) {
            "/workspace" + apk.removePrefix(ws.absolutePath).replace('\\', '/')
        } else apk
        val cmd = "$aapt2 d badging '$prootApk' 2>/dev/null"
        val out = runCatching {
            ProotLauncher.run(
                ctx, cmd,
                ProotLauncher.Options(
                    rootfs = PathConfig.compilerRootfs(ctx).absolutePath,
                    cwd = "/workspace",
                    binds = listOf(ProotLauncher.ProotBind(ws.absolutePath, "/workspace")),
                    env = mapOf("ANDROID_SDK_ROOT" to "/host-home/android-sdk"),
                    timeoutSec = 120,
                    redirectErrorStream = true
                )
            )
        }.getOrNull() ?: return ""
        return Regex("package: name='([^']+)'").find(out.stdout)?.groupValues?.getOrNull(1) ?: ""
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
    private fun isOnline(): Boolean = try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName("maven.aliyun.com"), 443), 1500)
        socket.close()
        true
    } catch (_: Exception) {
        false
    }

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
        // 源码预检：import 引用 + Manifest 组件声明
        val sourceMessages = runCatching { BuildPreflight.inspectSourceCode(projectDir) }.getOrDefault(emptyList())
        sourceMessages.forEach(append)
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
            EXPECTED_SHA="${JDK_SHA256}"
            JDK_FILE=/host-home/jdk17.tar.gz
            REPO_JDK_DEC=$(aidev-repo decide android-jdk17 2>/dev/null || true)
            case "${'$'}REPO_JDK_DEC" in
              repo:*)
                REPO_JDK=${'$'}{REPO_JDK_DEC#repo:}
                echo "→ 使用离线仓库 JDK: ${'$'}REPO_JDK"
                cp "${'$'}REPO_JDK" "${'$'}JDK_FILE"
                ;;
              network)
                echo "ℹ️ 离线仓库无 JDK，回退网络下载（可在 AIDevRepo 缓存 android-jdk17 以离线）"
                ;;
              deny)
                echo "❌ 离线优先模式：仓库无 JDK，已禁止网络下载。请先在 AIDevRepo 缓存该资源，或把「离线优先」开关关闭。"
                exit 1
                ;;
            esac
            if [ ! -f "${'$'}JDK_FILE" ]; then
            MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz https://mirrors.ustc.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz"
            DOWNLOADED=0
            for url in ${'$'}MIRRORS; do
                echo "尝试镜像: ${'$'}url"
                if "${'$'}CURL" -fL --retry 3 --connect-timeout 15 --max-time 900 -o "${'$'}JDK_FILE" "${'$'}url" 2>/dev/null; then
                    ACTUAL_SHA=""
                    if command -v sha256sum >/dev/null 2>&1; then
                        ACTUAL_SHA=$(sha256sum "${'$'}JDK_FILE" | cut -d' ' -f1)
                    elif command -v openssl >/dev/null 2>&1; then
                        ACTUAL_SHA=$(openssl dgst -sha256 "${'$'}JDK_FILE" | awk '{print ${'$'}NF}')
                    fi
                    if [ -n "${'$'}EXPECTED_SHA" ] && [ -n "${'$'}ACTUAL_SHA" ] && [ "${'$'}ACTUAL_SHA" != "${'$'}EXPECTED_SHA" ]; then
                        echo "⚠️ SHA256 校验失败: 期望 ${'$'}EXPECTED_SHA, 实际 ${'$'}ACTUAL_SHA，尝试下一个镜像"
                        rm -f "${'$'}JDK_FILE"
                        continue
                    fi
                    if [ -n "${'$'}ACTUAL_SHA" ]; then echo "✅ SHA256 校验通过"; fi
                    DOWNLOADED=1
                    break
                else
                    echo "✗ 镜像不可用: ${'$'}url"
                fi
            done
            if [ "${'$'}DOWNLOADED" -ne 1 ]; then
                echo "✗ 所有镜像均下载失败，请检查网络后重试"
                exit 1
            fi
            else
                echo "✅ 已获得 JDK 包（仓库提供），跳过下载"
            fi
            echo "解压 JDK..."
            rm -rf "${'$'}JDK_DIR"; mkdir -p "${'$'}JDK_DIR"
            tar -xzf "${'$'}JDK_FILE" -C "${'$'}JDK_DIR" --strip-components=1
            rm -f "${'$'}JDK_FILE"
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
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache",
                    "AIDEV_REPO_MODE" to com.aidev.six.PreferencesManager(ctx).repoMode
                ),
                timeoutSec = 1800,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "下载/解压便携 JDK17"
        )
        append("→ 宇宙 B JDK17: ${if (javaExit == 0) "OK" else "返回非零(可重试)"}")
    }

    private fun finish(ctx: Context, id: String, success: Boolean, message: String, log: StringBuilder, reqFile: File, apkPath: String? = null, logPath: String? = null, pkg: String? = null, project: String) {
        val result = JSONObject().apply {
            put("id", id)
            put("success", success)
            put("message", message)
            put("time", System.currentTimeMillis())
            // 宇宙 B 标准出口：构建产物路径与完整日志路径，供消费方（OpenCode/AF 面板）直接读取，不再自行猜测。
            put("apk_path", apkPath ?: JSONObject.NULL)
            put("log_path", logPath ?: JSONObject.NULL)
            put("pkg", pkg ?: JSONObject.NULL)
            put("project", project)
        }
        runCatching {
            File(requestDir, "result-$id.json").writeText(result.toString(2))
        }
        // 构建日志已由 LogHub 统一写入 /sdcard/AIDev/logs/，无需额外复制
        reqFile.delete()
        notify(ctx, if (success) "AIDev 构建完成" else "AIDev 构建失败", message, priority = "high")
        AIDevLogger.i("BuildBridge", "request $id done success=$success msg=$message")
    }

    private fun notify(ctx: Context, title: String, msg: String, priority: String) {
        runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
    }

    /**
     * 构建失败回流：把编译错误写到 `home/.aidev-loop/build-failure-<id>.json`。
     * 无论请求来自 App UI 还是宇宙 A 终端，只要构建失败就写，确保健壮性。
     * 写文件是第一优先级，诊断/JSON 构建各自防御，避免任何一步抛异常导致整条回流丢失。
     */
    private fun writeLoopBuildFailure(ctx: Context, buildId: String, project: String, logText: String) {
        val errorLines = runCatching {
            logText.lines().filter { line ->
                line.contains("error:", ignoreCase = true) ||
                line.contains("FAILED") ||
                line.contains("Exception") ||
                line.contains("What went wrong")
            }.take(20)
        }.getOrDefault(emptyList())
        val hints = runCatching { BuildDiagnostics.diagnoseBuildErrors(logText) }.getOrDefault(emptyList())
        val tailLog = runCatching { logText.lines().takeLast(80).joinToString("\n") }.getOrDefault("")

        // 把【完整失败日志】落到稳定、不可变路径，避免被后续构建覆盖 build.log 而读到旧日志。
        // - logs/<project>/last-build-failure.log：按钮回流用（OpenCode 直接读此文件）
        // - .aidev-loop/build-failure-<id>.log：守护进程自动闭环用
        val stableLog = File(File(PathConfig.logsDir(ctx), project), "last-build-failure.log")
        val loopLog = File(File(PathConfig.aidevHome(ctx), ".aidev-loop"), "build-failure-$buildId.log")
        runCatching { stableLog.parentFile?.mkdirs(); stableLog.writeText(logText) }
            .onFailure { e -> LoopTrace.log("AutoLoop", "写稳定失败日志失败: $e") }
        runCatching { loopLog.parentFile?.mkdirs(); loopLog.writeText(logText) }
            .onFailure { e -> LoopTrace.log("AutoLoop", "写回流日志失败: $e") }

        val payload = runCatching {
            JSONObject().apply {
                put("type", "self-evolution/build-failure")
                put("id", buildId)
                put("project", project)
                put("time", System.currentTimeMillis())
                put("failed", true)
                put("errors", org.json.JSONArray().apply { errorLines.forEach { put(it) } })
                put("hints", org.json.JSONArray().apply { hints.forEach { put(it) } })
                put("log_tail", tailLog)
                put("log_file", "/host-home/.aidev-loop/build-failure-$buildId.log")
                put("fix_applied", false)
            }.toString(2)
        }.getOrNull() ?: return

        // 主路径：home/.aidev-loop/（宇宙 A 守护进程与手动按钮共享）
        val targets = listOf(
            File(PathConfig.aidevHome(ctx), ".aidev-loop"),
            File(PathConfig.logsDir(ctx), project),
        )
        var written: File? = null
        for (dir in targets) {
            runCatching {
                dir.mkdirs()
                val out = File(dir, "build-failure-$buildId.json")
                out.writeText(payload)
                written = out
            }.onFailure { e ->
                AIDevLogger.e("BuildBridge", "写构建失败回流失败: ${dir.absolutePath} -> $e")
                LoopTrace.log("AutoLoop", "写回流失败: ${dir.absolutePath} -> $e")
            }
            if (written != null) break
        }
        if (written != null) {
            AIDevLogger.i("BuildBridge", "写入构建失败回流: ${written.absolutePath}")
            LoopTrace.log("AutoLoop", "写入构建失败回流: ${written.absolutePath}")
        } else {
            AIDevLogger.e("BuildBridge", "构建失败回流全部写入失败（project=$project）")
            LoopTrace.log("AutoLoop", "构建失败回流全部写入失败（project=$project）")
        }
    }
}
