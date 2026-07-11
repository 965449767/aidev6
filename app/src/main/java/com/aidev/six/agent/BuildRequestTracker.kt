package com.aidev.six.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aidev.six.BuildBridgeService
import com.aidev.six.CrashReportBridgeService
import com.aidev.six.PathConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 自我进化闭环「提交构建请求」的可见状态追踪器（F02）。
 *
 * 提交后：
 *  1) 确保 BuildBridge / CrashReportBridge 已在轮询（解决 B3：不进终端也能跑）。
 *  2) 写入 req-<id>.json（与 aidev-build-request.sh 同格式）。
 *  3) 立即在任务流中插入一条 RUNNING 记录，并轮询 BuildBridge 的
 *     logs/build-<id>.log（实时日志）与 result-<id>.json（最终结果），
 *     持续回调刷新 UI（解决 B1/B2/B4）。
 */
internal class BuildRequestTracker {

    private val executor = Executors.newCachedThreadPool()
    private var mainHandler: Handler? = null
    private fun postToMain(block: () -> Unit) { mainHandler?.post(block) ?: block() }

    // 暴露给 UI：最近一次回流的崩溃摘要（F04）。
    var latestCrash: String = ""
        private set

    fun submit(
        context: Context,
        project: String,
        stateFile: File,
        autoInstall: Boolean = true,
        autoLaunch: Boolean = true,
        onUpdate: (AgentTaskRecord) -> Unit,
    ) {
        val appCtx = context.applicationContext
        val home = PathConfig.aidevHome(appCtx)
        mainHandler = Handler(Looper.getMainLooper())
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

        val definition = AgentTaskDefinition(
            id = "build-$id",
            name = "构建 $project",
            description = "宇宙 B 编译 → 静默安装 → 自动拉起",
            command = "aidev-build-request --project $project",
            workingDirectory = PathConfig.workspaceDir(appCtx).absolutePath,
            tags = listOf("build", "self-evolution")
        )

        val startedAt = System.currentTimeMillis()
        fun publish(status: AgentTaskStatus, exitCode: Int, finishedAt: Long, log: String, steps: List<AgentTaskStepResult>) {
            val record = AgentTaskRecord(
                definition = definition,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitCode = exitCode,
                log = log.ifBlank { "已提交构建请求，等待宇宙 B 调度…" },
                lastUpdatedAt = System.currentTimeMillis(),
                steps = steps
            )
            AgentTaskStore.upsertTask(stateFile, record, limit = 12)
            postToMain { onUpdate(record) }
        }

        val writeOk = runCatching { reqFile.writeText(payload.toString()) }.isSuccess
        if (!writeOk) {
            publish(AgentTaskStatus.FAILED, -1, System.currentTimeMillis(), "✖ 写入构建请求失败：${reqFile.absolutePath}", emptyList())
            return
        }

        publish(AgentTaskStatus.RUNNING, -1, 0L, "▶ 已提交构建请求 (id=$id, project=$project)\n等待 BuildBridge 调度…", emptyList())

        executor.execute {
            val logFile = File(bridgeDir, "logs/build-$id.log")
            val resultFile = File(bridgeDir, "result-$id.json")
            val timeoutMs = 20 * 60 * 1000L
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val logText = runCatching { if (logFile.isFile) logFile.readText() else "" }.getOrDefault("")
                val steps = derivePhases(logText)

                if (resultFile.isFile) {
                    val result = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
                    val success = result?.optBoolean("success", false) ?: false
                    val message = result?.optString("message", "") ?: ""
                    val finalLog = buildString {
                        append(logText.takeLast(6000))
                        append("\n\n")
                        append(if (success) "✔ $message" else "✖ $message")
                    }
                    val finalSteps = finalizePhases(steps, success)
                    publish(
                        if (success) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED,
                        if (success) 0 else 1,
                        System.currentTimeMillis(),
                        finalLog,
                        finalSteps
                    )
                    // 闭环最后一环：若成功安装并拉起，等待崩溃回流报告并作为独立任务呈现（F04）
                    if (success && logText.contains("已拉起")) {
                        watchCrashReport(home, startedAt, stateFile, onUpdate)
                    }
                    return@execute
                }

                publish(AgentTaskStatus.RUNNING, -1, 0L, logText.takeLast(6000), steps)
                Thread.sleep(800)
            }

            publish(
                AgentTaskStatus.FAILED,
                -1,
                System.currentTimeMillis(),
                "✖ 构建超时（超过 20 分钟未返回结果）。可能宇宙 B 首次初始化较慢，稍后在服务器中心刷新查看。",
                emptyList()
            )
        }
    }

    /**
     * 构建拉起后，等待 CrashReportBridge 生成的崩溃回流报告（.aidev-mcp/crash-*.json），
     * 并作为独立任务记录呈现在同一任务流中，闭合「运行 → 崩溃 → 回流」这一环（F04）。
     */
    private fun watchCrashReport(
        home: File,
        buildStartedAt: Long,
        stateFile: File,
        onUpdate: (AgentTaskRecord) -> Unit,
    ) {
        val mcpDir = File(home, ".aidev-mcp")
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val fresh = mcpDir.listFiles { f ->
                f.name.startsWith("crash-") && f.name.endsWith(".json") && f.lastModified() >= buildStartedAt
            }?.maxByOrNull { it.lastModified() }
            if (fresh != null) {
                publishCrashRecord(fresh, stateFile, onUpdate)
                return
            }
            Thread.sleep(1000)
        }
    }

    private fun publishCrashRecord(reportFile: File, stateFile: File, onUpdate: (AgentTaskRecord) -> Unit) {
        val json = runCatching { JSONObject(reportFile.readText()) }.getOrNull() ?: return
        val pkg = json.optString("package", "?")
        val fatal = json.optString("fatal", "")
        val stackArr = json.optJSONArray("stack")
        val stackLines = (0 until (stackArr?.length() ?: 0)).map { stackArr!!.optString(it) }
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
        postToMain { onUpdate(record) }
    }

    private data class Phase(val name: String, val markers: List<String>)

    private val phaseOrder = listOf(
        Phase("准备宇宙 B", listOf("准备宇宙 B", "宇宙 B 已就绪", "install-compiler", "JDK17")),
        Phase("编译", listOf("进入宇宙 B 编译", "gradlew assembleDebug", "构建成功", "构建失败")),
        Phase("安装", listOf("通过 Shizuku 安装", "复制 APK", "未找到产物")),
        Phase("拉起", listOf("已拉起")),
    )

    private fun derivePhases(log: String): List<AgentTaskStepResult> {
        if (log.isBlank()) return emptyList()
        var activeReached = -1
        phaseOrder.forEachIndexed { idx, phase ->
            if (phase.markers.any { log.contains(it) }) activeReached = idx
        }
        if (activeReached < 0) return emptyList()
        return phaseOrder.mapIndexed { idx, phase ->
            val status = when {
                idx < activeReached -> AgentTaskStatus.SUCCEEDED
                idx == activeReached -> AgentTaskStatus.RUNNING
                else -> AgentTaskStatus.PENDING
            }
            AgentTaskStepResult(name = phase.name, status = status)
        }
    }

    private fun finalizePhases(steps: List<AgentTaskStepResult>, success: Boolean): List<AgentTaskStepResult> {
        if (steps.isEmpty()) return emptyList()
        return steps.map { step ->
            when (step.status) {
                AgentTaskStatus.RUNNING -> step.copy(status = if (success) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED)
                AgentTaskStatus.PENDING -> if (success) step.copy(status = AgentTaskStatus.SUCCEEDED) else step
                else -> step
            }
        }
    }
}
