package com.aidev.six.agent

import android.content.Context
import com.aidev.six.BuildBridgeService
import com.aidev.six.CrashReportBridgeService
import com.aidev.six.PathConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 自我进化闭环「提交构建请求」的可见状态追踪器（F02）。
 *
 * 提交后：
 *  1) 确保 BuildBridge / CrashReportBridge 已在轮询（解决 B3：不进终端也能跑）。
 *  2) 写入 req-<id>.json（与 aidev-build-request.sh 同格式）。
 *  3) 立即在任务流中插入一条 RUNNING 记录，并轮询 BuildBridge 的
 *     /sdcard/AIDev/logs/build-<project>-<id>-*.log（实时日志）与 result-<id>.json（最终结果），
 *     持续回调刷新 UI（解决 B1/B2/B4）。
 */
internal class BuildRequestTracker(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {

    private var appContext: Context? = null
    // 记录已落盘（AgentTaskStore.upsertTask），这里直接回调即可，无需切主线程
    private fun postToMain(block: () -> Unit) = block()

    // 暴露给 UI：最近一次回流的崩溃摘要（F04）。
    var latestCrash: String = ""
        private set

    fun submit(
        context: Context,
        project: String,
        stateFile: File,
        autoInstall: Boolean = true,
        autoLaunch: Boolean = true,
        autonomous: Boolean = false,
        onUpdate: (AgentTaskRecord) -> Unit,
    ) {
        val appCtx = context.applicationContext
        val home = PathConfig.aidevHome(appCtx)
        appContext = appCtx
        runCatching {
            BuildBridgeService.start(appCtx, home)
            CrashReportBridgeService.start(appCtx, home)
        }

        val bridgeDir = File(home, ".aidev-build-bridge").apply { mkdirs() }
        val id = System.currentTimeMillis().toString()
        val reqFile = File(bridgeDir, "req-$id.json")
        val payload = JSONObject().apply {
            put("id", id)
            put("project", project)
            put("flavor", "debug")
            put("autoInstall", autoInstall)
            put("autoLaunch", autoLaunch)
        }

        // 注意：构建进度记录由 BuildBridgeService 统一发布到 agent-tasks.json（单一真源），
        // 保证「手动提交」与「宇宙 A 终端自动提交」在 AF 面板呈现完全一致。这里只负责：
        // 写请求文件 → 等结果 → 成功拉起后驱动崩溃回流/自治重建。
        val startedAt = System.currentTimeMillis()
        val runningDefinition = AgentTaskDefinition(
            id = "build-$id", name = "构建 $project",
            description = "宇宙 B 编译 → 产出 APK（安装/拉起由 aidev-deploy 独立黑盒接力）",
            command = "aidev-build-request --project $project",
            workingDirectory = PathConfig.workspaceDir(appCtx).absolutePath,
            tags = listOf("build", "self-evolution")
        )
        val writeOk = runCatching { reqFile.writeText(payload.toString()) }.isSuccess
        if (!writeOk) {
            val record = AgentTaskRecord(
                definition = runningDefinition,
                status = AgentTaskStatus.FAILED,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                exitCode = -1,
                log = "✖ 写入构建请求失败：${reqFile.absolutePath}"
            )
            AgentTaskStore.upsertTask(stateFile, record, limit = 12)
            postToMain { onUpdate(record) }
            return
        }

        // 立即插入一条 RUNNING 记录并回调，任务流即时呈现「已提交构建」（与类文档承诺一致）
        val runningRecord = AgentTaskRecord(
            definition = runningDefinition,
            status = AgentTaskStatus.RUNNING,
            startedAt = startedAt,
            finishedAt = 0L,
            exitCode = -1,
            log = "⏳ 已提交构建请求，等待宇宙 B 编译…",
            lastUpdatedAt = System.currentTimeMillis()
        )
        AgentTaskStore.upsertTask(stateFile, runningRecord, limit = 12)
        postToMain { onUpdate(runningRecord) }

        scope.launch {
            val resultFile = File(bridgeDir, "result-$id.json")
            val logsDir = PathConfig.logsDir(appCtx)
            val timeoutMs = 20 * 60 * 1000L
            val deadline = System.currentTimeMillis() + timeoutMs

            while (isActive && System.currentTimeMillis() < deadline) {
                if (resultFile.isFile) {
                    val result = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
                    val success = result?.optBoolean("success", false) ?: false
                    val logFile = logsDir.listFiles()?.mapNotNull { sub ->
                        File(sub, "build.log").takeIf { it.isFile }
                    }?.maxByOrNull { it.lastModified() }
                    val logText = runCatching { logFile?.readText() ?: "" }.getOrDefault("")
                    if (success && logText.contains("已拉起")) {
                        watchCrashReport(home, startedAt, stateFile, autonomous, onUpdate)
                    }
                    return@launch
                }
                delay(800)
            }
        }
    }

    /**
     * 构建拉起后，等待 CrashReportBridge 生成的崩溃回流报告（.aidev-mcp/crash-*.json），
     * 并作为独立任务记录呈现在同一任务流中，闭合「运行 → 崩溃 → 回流」这一环（F04）。
     *
     * 自治模式（[autonomous]=true，即「自我进化自治开关」开启）：若崩溃未被宇宙 A 修复
     * （`fix_applied=false`），自动触发下一轮构建，形成「崩溃 → 改码 → 重建」自动循环，
     * 直到 fix_applied=true（已修）或达到 [MAX_AUTO_ITERATIONS] 上限（防失控）。
     */
    internal suspend fun watchCrashReport(
        home: File,
        buildStartedAt: Long,
        stateFile: File,
        autonomous: Boolean,
        onUpdate: (AgentTaskRecord) -> Unit,
    ) {
        val mcpDir = File(home, ".aidev-mcp")
        var rebuildAt = buildStartedAt
        var iterations = 0
        while (coroutineContext.isActive && System.currentTimeMillis() - buildStartedAt < 60_000L) {
            val fresh = mcpDir.listFiles { f ->
                f.name.startsWith("crash-") && f.name.endsWith(".json") && f.lastModified() >= rebuildAt
            }?.maxByOrNull { it.lastModified() }
            if (fresh == null) {
                delay(1000)
                continue
            }
            publishCrashRecord(fresh, stateFile, onUpdate)
            val fixed = runCatching { JSONObject(fresh.readText()).optBoolean("fix_applied", false) }.getOrDefault(false)
            if (!autonomous || fixed || iterations >= MAX_AUTO_ITERATIONS) return
            iterations++
            requestRebuild(appContext ?: return, "MyAndroidProject", stateFile, autonomous, onUpdate)
            rebuildAt = System.currentTimeMillis()
        }
    }

    private companion object {
        const val MAX_AUTO_ITERATIONS = 10
    }

    internal fun publishCrashRecord(reportFile: File, stateFile: File, onUpdate: (AgentTaskRecord) -> Unit) {
        val json = runCatching { JSONObject(reportFile.readText()) }.getOrNull() ?: return
        val pkg = json.optString("package", "?")
        val fatal = json.optString("fatal", "")
        val stackArr = json.optJSONArray("stack")
        val stackLines = (0 until (stackArr?.length() ?: 0)).map { i -> stackArr?.optString(i) ?: "" }
        val crashed = stackLines.isNotEmpty()

        val log = buildString {
            if (crashed) {
                append("✖ 捕获到崩溃（${stackLines.size} 行）\n")
                if (fatal.isNotBlank()) append("$fatal\n")
                append("\n")
                append(stackLines.take(40).joinToString("\n"))
            } else {
                append("✔ 未捕获到崩溃，应用运行正常\n")
                if (fatal.isNotBlank()) append(fatal)
            }
        }

        val record = AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = "crash-${reportFile.nameWithoutExtension}",
                name = "崩溃回流 $pkg",
                description = if (crashed) "已回流崩溃堆栈，可交 OpenCode 自我修正" else "运行自检：未发现崩溃",
                command = "aidev-crash-report --package $pkg",
                workingDirectory = "",
                tags = listOf("crash", "self-evolution")
            ),
            status = if (crashed) AgentTaskStatus.FAILED else AgentTaskStatus.SUCCEEDED,
            startedAt = json.optLong("time", System.currentTimeMillis()),
            finishedAt = System.currentTimeMillis(),
            exitCode = if (crashed) 1 else 0,
            log = log,
            lastUpdatedAt = System.currentTimeMillis()
        )
        AgentTaskStore.upsertTask(stateFile, record, limit = 12)
        latestCrash = if (crashed) "✖ $pkg 崩溃（${stackLines.size} 行）" else "✔ $pkg 运行正常"
        // G01：把崩溃回流同时写进共享工作区，供宇宙 A（OpenCode）读取并自我修正
        writeLoopCrash(reportFile, json, crashed, pkg, stackLines)
        postToMain { onUpdate(record) }
    }

    /**
     * 把崩溃回流写到 `home/.aidev-loop/crash-<id>.json`（G01）。
     * 这是「宇宙 A 反向驱动」的入口文件：OpenCode 读它 → 改码 → 调 [requestRebuild] 触发下一轮构建。
     */
    private fun writeLoopCrash(reportFile: File, source: JSONObject, crashed: Boolean, pkg: String, stack: List<String>) {
        val ctx = appContext ?: return
        runCatching {
            val loopDir = File(PathConfig.aidevHome(ctx), ".aidev-loop").apply { mkdirs() }
            val out = File(loopDir, "crash-${reportFile.nameWithoutExtension}.json")
            val payload = JSONObject().apply {
                put("type", "self-evolution/crash")
                put("id", reportFile.nameWithoutExtension)
                put("package", pkg)
                put("time", source.optLong("time", System.currentTimeMillis()))
                put("crashed", crashed)
                put("fatal", source.optString("fatal", ""))
                put("stack", org.json.JSONArray().apply { stack.forEach { put(it) } })
                put("project", "MyAndroidProject")
                put("fix_applied", false)
            }
            out.writeText(payload.toString(2))
        }
    }

/**
 * 宇宙 A（OpenCode）改完码后调用，触发下一轮构建（G03）。
 * 等价于在「服务器中心」点一次「提交构建请求」，但由闭环自动发起；
 * 安装/拉起/抓崩溃由 OpenCode 经 aidev-deploy + aidev-verify-run 独立黑盒接力。
 */
    fun requestRebuild(
        context: Context,
        project: String = "MyAndroidProject",
        stateFile: File,
        autonomous: Boolean = false,
        onUpdate: (AgentTaskRecord) -> Unit,
    ) {
        submit(context, project, stateFile, autoInstall = true, autoLaunch = true, autonomous = autonomous, onUpdate = onUpdate)
    }

    /**
     * 启动后消费宿主上次遗留的崩溃报告（P0-1）。
     * 扫描 home/.aidev-mcp/crash-*.json 中尚无对应 home/.aidev-loop/crash-<id>.json 的，
     * 发布到 agent-tasks 闭环，让 OpenCode 自我修正；避免宿主崩溃被安静丢弃。
     */
    fun consumeLegacyCrashes(context: Context, onUpdate: (AgentTaskRecord) -> Unit = {}) {
        val ctx = context.applicationContext
        appContext = ctx
        val home = PathConfig.aidevHome(ctx)
        val mcpDir = File(home, ".aidev-mcp")
        val loopDir = File(home, ".aidev-loop").apply { mkdirs() }
        val stateFile = File(PathConfig.tasksDir(ctx), "agent-tasks.json")
        mcpDir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".json") }
            ?.forEach { f ->
                val loopFile = File(loopDir, "crash-${f.nameWithoutExtension}.json")
                if (!loopFile.exists()) {
                    publishCrashRecord(f, stateFile, onUpdate)
                }
            }
    }
}

/**
 * 跨包入口：消费宿主遗留崩溃报告（P0-1）。[AIDevApp] 在 onCreate 调用，
 * [CrashReportBridgeService] 在生成宿主自身崩溃报告后也会调用。
 */
internal fun consumeLegacyCrashes(context: Context, onUpdate: (AgentTaskRecord) -> Unit = {}) {
    BuildRequestTracker().consumeLegacyCrashes(context, onUpdate)
}
