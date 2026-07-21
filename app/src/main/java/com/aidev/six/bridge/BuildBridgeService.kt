package com.aidev.six.bridge

import android.content.Context
import com.aidev.six.build.BuildContext
import com.aidev.six.build.BuildExecutor
import com.aidev.six.build.BuildEnvironmentSetup
import com.aidev.six.build.CompileResult
import com.aidev.six.task.BuildProgress
import com.aidev.six.task.BuildProgress.Phase
import com.aidev.six.task.ProjectTaskLock
import com.aidev.six.task.TaskStatus
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 构建桥（人类驱动的构建请求入口）。
 *
 * 人类在终端执行 `aidev-build-request` 写入结构化请求文件：
 *   home/.aidev-build-bridge/req-<id>.json
 *   { "project": "<workspace 下相对路径或 /workspace/...>", "flavor": "debug" }
 *
 * 构建黑盒只负责出产物（apk_path），部署（安装+启动）交由独立的部署黑盒 aidev-deploy。
 *
 * 本服务轮询该目录，在终端环境（ubuntu-rootfs）内执行 `./gradlew assembleDebug`，
 * 编译产物经共享 workspace 零延迟映射到物理硬盘；成功后静默安装并拉起，
 * 最后写入 result-<id>.json 并通知宿主。
 */
object BuildBridgeService : BridgeService("BuildBridge") {

    private const val BRIDGE_DIR = ".aidev-build-bridge"

    @Volatile private var requestDir: File? = null

    // 按请求 id 跟踪：活跃协程 Job 与当前 PRoot 进程，供「取消」真正中止编译（否则只删标志不杀进程）。
    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val cancelledIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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

    override val bridgeName: String get() = "build"

    override fun dispatch(frame: BridgeFrame): BridgeFrame? {
        val dir = requestDir
        if (dir == null) {
            AIDevLogger.w("BuildBridge", "dispatch: 桥未启动")
            return BridgeFrame("build", frame.id, "ERROR: 桥未就绪")
        }
        runCatching { File(dir, "req-${frame.id}.json").writeText(frame.payload) }
            .onFailure { AIDevLogger.w("BuildBridge", "dispatch 入队失败", it) }
        return BridgeFrame("build", frame.id, "accepted")
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

        val bc = prepareBuild(ctx, id, project, processingFile) ?: return
        // 失败结果也要回带正确的日志路径（否则 aidev-build-request 拿不到 build.log，见 Issue #2）。
        val buildLogPath = File(File(PathConfig.logsDir(ctx), bc.project), "build.log").absolutePath

        try {
            val result = BuildExecutor.compileProject(ctx, id, bc, activeProcesses, cancelledIds)
            if (result.success) {
                installAndLaunch(ctx, id, bc, result)
            } else {
                postProcess(ctx, id, bc, false, result.message, logPath = buildLogPath)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            bc.append("⏹ 已取消")
            postProcess(ctx, id, bc, false, "已取消", cancelled = true)
        } catch (e: Exception) {
            bc.append("✗ ${e.message}")
            postProcess(ctx, id, bc, false, e.message ?: "异常")
        } finally {
            ProjectTaskLock.release(bc.lockKey)
            activeJobs.remove(id)
            activeProcesses.remove(id)
            cancelledIds.remove(id)
        }
    }

    private suspend fun prepareBuild(ctx: Context, id: String, project: String, processingFile: File): BuildContext? {
        val log = StringBuffer()

        val ws0 = PathConfig.workspaceDir(ctx)
        val projectDir0 = resolveProjectDir(ws0, project)
        // 日志/结果统一用项目目录名（basename）做 key，避免把 /workspace/xxx 这种路径
        // 拼进 logs/<key>/build.log 产生非法/转义路径（见调试报告 Issue #1/#2）。
        // 注意：必须在 openBuildLog 之前算出 logKey，否则用绝对路径 project 会让
        // File(logsDir, "/workspace/xxx") 忽略 logsDir，日志写到别处，与 result 回带的
        // basename 路径不一致 → aidev-build-request "日志未找到"。
        val logKey = projectDir0.name

        val logWriter = LogHub.openBuildLog(PathConfig.logsDir(ctx), logKey)
        val timer = LogHub.StepTimer(logWriter)
        val gradleFilter = LogHub.GradleFilter()

        val stateFile = File(PathConfig.tasksDir(ctx), "task-records.json")
        val definition = com.aidev.six.task.TaskDefinition(
            id = "build-$id",
            name = "构建 $project",
            description = "终端环境编译 → 静默安装 → 自动拉起",
            command = "aidev-build-request --project $project",
            workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
            tags = listOf("build", "self-evolution")
        )
        val startedAt = System.currentTimeMillis()
        val lastPublish = java.util.concurrent.atomic.AtomicLong(0L)

        val ws = ws0
        val projectDir = projectDir0
        val lockKey = projectDir.absolutePath
        val rel = projectDir.absolutePath.removePrefix(ws.absolutePath).trimStart('/')

        val bc = BuildContext(
            log = log, logWriter = logWriter, timer = timer, gradleFilter = gradleFilter,
            stateFile = stateFile, definition = definition, startedAt = startedAt,
            currentPhase = Phase.PREPARE, lastPublish = lastPublish, lockKey = lockKey,
            projectDir = projectDir, ws = ws, rel = rel, project = logKey,
            ctx = ctx, id = id, processingFile = processingFile,
            requestDir = requestDir
        )

        if (!ProjectTaskLock.tryAcquire(lockKey, "build:$id")) {
            val holder = ProjectTaskLock.holder(lockKey)
            bc.append("⏸ 该项目已有任务进行中（${holder?.source ?: "?"}），本次请求跳过")
            bc.publishBuild(TaskStatus.RUNNING, -1, 0L, emptyList())
            bc.finishAndPublish(false, "该项目已有任务进行中，已跳过")
            return null
        }

        bc.append("=== BuildBridge 构建请求 $id (project=$project) ===")
        bc.publishBuild(TaskStatus.RUNNING, -1, 0L, emptyList())
        buildNotify(ctx, "AIDev 构建", "开始编译 $project", priority = "default")
        return bc
    }

    private suspend fun installAndLaunch(ctx: Context, id: String, bc: BuildContext, result: CompileResult) {
        val projectDir = bc.projectDir
        val append = bc::append

        val stdApk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
        val customApk = bc.buildApkPath?.let { File(it) }
        val apk = when {
            stdApk.isFile -> stdApk
            customApk != null && customApk.isFile -> customApk
            else -> null
        }
        if (apk == null) {
            append("✗ 构建成功但找不到有效产物 APK（Gradle 产物缺失）: ${stdApk.absolutePath}")
            bc.finishAndPublish(false, "构建成功但缺少 APK")
            return
        }

        val buildLogPath = File(File(PathConfig.logsDir(ctx), bc.project), "build.log").absolutePath
        runCatching {
            File(File(PathConfig.logsDir(ctx), bc.project), "last-build-failure.log").delete()
            listOf(
                File(PathConfig.aidevHome(ctx), ".aidev-loop"),
                File(PathConfig.logsDir(ctx), bc.project)
            ).forEach { d ->
                d.listFiles { f -> f.name.matches(Regex("build-failure-.*\\.json")) }?.forEach { it.delete() }
            }
        }
        val pkg = BuildEnvironmentSetup.extractPackage(ctx, apk.absolutePath, append)
        if (pkg.isNotBlank()) append("→ 产物包名: $pkg")
        bc.finishAndPublish(true, "构建成功: ${apk.absolutePath}", apkPath = apk.absolutePath, logPath = buildLogPath, pkg = pkg.ifBlank { null })
    }

    private suspend fun postProcess(ctx: Context, id: String, bc: BuildContext, success: Boolean, message: String, cancelled: Boolean = false, logPath: String? = null) {
        bc.append(if (cancelled) "⏹ 已取消" else "✗ ${message}")
        bc.finishAndPublish(success, message, cancelled = cancelled, logPath = logPath)
    }

    /** 解析请求里的 project 字段为共享 workspace 下的实际目录（兼容相对/绝对/`/workspace/` 前缀）。 */
    internal fun resolveProjectDir(ws: File, project: String): File = when {
        project.startsWith("/workspace/") -> File(ws, project.removePrefix("/workspace/").trimStart('/'))
        File(project).isAbsolute -> File(project)
        else -> File(ws, project.trimStart('/'))
    }
}
