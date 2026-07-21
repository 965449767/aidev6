package com.aidev.six.build

import android.content.Context
import com.aidev.six.AIDevCommandDispatcher
import com.aidev.six.AIDevLogger
import com.aidev.six.LogHub
import com.aidev.six.PathConfig
import com.aidev.six.task.BuildProgress
import com.aidev.six.task.BuildProgress.Phase
import com.aidev.six.task.ProjectTaskLock
import com.aidev.six.task.TaskDefinition
import com.aidev.six.task.TaskRecord
import com.aidev.six.task.TaskStepResult
import com.aidev.six.task.TaskStatus
import com.aidev.six.task.TaskStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 构建过程上下文：日志、进度发布、最终状态汇总。
 * 由 [BuildBridgeService] 在请求开始时创建，编译全程持有。
 */
internal class BuildContext(
    val log: StringBuffer,
    val logWriter: LogHub.LogWriter,
    val timer: LogHub.StepTimer,
    val gradleFilter: LogHub.GradleFilter,
    val stateFile: File,
    val definition: TaskDefinition,
    val startedAt: Long,
    var currentPhase: Phase,
    val lastPublish: AtomicLong,
    val lockKey: String,
    val projectDir: File,
    val ws: File,
    val rel: String,
    val project: String,
    private val ctx: Context,
    private val id: String,
    private val processingFile: File,
    private val requestDir: File?
) {
    /** 由 append 按行增量提取，避免构建结束时全量正则扫描 bc.log.toString()（P-H6 优化）。 */
    @Volatile var buildApkPath: String? = null
    fun append(line: String) {
        log.appendLine(line)
        if (log.length > 24000) log.delete(0, log.length - 16000)

        // 按行增量提取 APK 路径（P-H6 优化），避免末尾全量正则扫描 bc.log.toString()。
        val apkMatch = APK_PATH_REGEX.find(line)
        if (apkMatch != null) buildApkPath = apkMatch.groupValues.getOrNull(1)

        if (gradleFilter.shouldKeep(line)) {
            logWriter.append(line)
        }
        val now = System.currentTimeMillis()
        val prev = lastPublish.getAndSet(now)
        if (now - prev >= 800) {
            publishBuild(TaskStatus.RUNNING, -1, 0L, BuildProgress.deriveUpTo(currentPhase))
        }
    }

    companion object {
        private val APK_PATH_REGEX = Regex("AIDev:\\s*APK\\s*->\\s*(\\S+)")
    }

    fun publishBuild(status: TaskStatus, exitCode: Int, finishedAt: Long, steps: List<TaskStepResult>) {
        val record = TaskRecord(
            definition = definition,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exitCode = exitCode,
            log = log.toString().takeLast(6000).ifBlank { "已提交构建请求，等待编译调度…" },
            lastUpdatedAt = System.currentTimeMillis(),
            steps = steps
        )
        runCatching { TaskStore.upsertTask(stateFile, record, limit = 12) }
    }

    fun finishAndPublish(
        success: Boolean,
        message: String,
        cancelled: Boolean = false,
        apkPath: String? = null,
        logPath: String? = null,
        pkg: String? = null
    ) {
        val warningSummary = gradleFilter.flushWarnings()
        if (warningSummary != null) {
            log.appendLine(warningSummary)
            logWriter.append(warningSummary)
        }
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
                buildWriteLoopBuildFailure(ctx, id, project, log.toString())
            }
            timer.endStep(if (success) "成功" else "失败")
            logWriter.finish()
            runCatching { LogHub.saveProfile(PathConfig.logsDir(ctx), timer.profileJson(), "build", project) }
            buildFinish(ctx, id, success, message, log, processingFile, requestDir, apkPath, logPath, pkg, project)
        val status = when {
            cancelled -> TaskStatus.CANCELLED
            success -> TaskStatus.SUCCEEDED
            else -> TaskStatus.FAILED
        }
        publishBuild(
            status,
            if (success) 0 else 1,
            System.currentTimeMillis(),
            BuildProgress.finalize(BuildProgress.deriveUpTo(currentPhase), success)
        )
    }
}

// ─── 构建结果落盘（package-level，供 BuildContext 和 BuildBridgeService 共用）─────

internal fun buildFinish(
    ctx: Context, id: String, success: Boolean, message: String, log: CharSequence,
    reqFile: File, requestDir: File?,
    apkPath: String? = null, logPath: String? = null, pkg: String? = null, project: String
) {
    val result = org.json.JSONObject().apply {
        put("id", id)
        put("success", success)
        put("message", message)
        put("time", System.currentTimeMillis())
        put("apk_path", apkPath ?: org.json.JSONObject.NULL)
        put("log_path", logPath ?: org.json.JSONObject.NULL)
        put("pkg", pkg ?: org.json.JSONObject.NULL)
        put("project", project)
    }
    runCatching {
        java.io.File(requestDir, "result-$id.json").writeText(result.toString(2))
    }
    reqFile.delete()
    buildNotify(ctx, if (success) "AIDev 构建完成" else "AIDev 构建失败", message, priority = "high")
    AIDevLogger.i("BuildBridge", "request $id done success=$success msg=$message")
}

internal fun buildNotify(ctx: Context, title: String, msg: String, priority: String) {
    runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
}

internal fun buildWriteLoopBuildFailure(ctx: Context, buildId: String, project: String, logText: String) {
    val stableLog = java.io.File(java.io.File(PathConfig.logsDir(ctx), project), "last-build-failure.log")
    runCatching { stableLog.parentFile?.mkdirs(); stableLog.writeText(logText) }
        .onFailure { e -> AIDevLogger.e("BuildBridge", "写稳定失败日志失败: ${e.message}") }
}
