package com.aidev.six.agent

import android.os.Handler
import android.os.Looper
import com.aidev.six.AIDevLogger
import com.aidev.six.SafeCommandGuard
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单例：避免随 Composable 重组反复 `newSingleThreadExecutor()` 造成的 executor 泄漏与 cancel 失效。
 * 整 App 生命周期内复用同一线程，任务顺序执行。
 */
internal object AgentTaskRunner {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val activeCancellationFlags = ConcurrentHashMap<String, AtomicBoolean>()

    private data class ExecResult(val exitCode: Int, val output: String, val failure: Throwable? = null)

    fun runTask(definition: AgentTaskDefinition, stateFile: File, onUpdate: (AgentTaskRecord) -> Unit) {
        executor.execute {
            val startedAt = System.currentTimeMillis()
            val cancellationFlag = AtomicBoolean(false)
            activeCancellationFlags[definition.id] = cancellationFlag

            val logBuilder = StringBuilder()
            fun publish(status: AgentTaskStatus, exitCode: Int, finishedAt: Long) {
                val record = AgentTaskRecord(
                    definition = definition,
                    status = status,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    exitCode = exitCode,
                    log = logBuilder.toString().ifBlank {
                        if (status == AgentTaskStatus.RUNNING) "任务执行中…" else "任务已完成"
                    },
                    lastUpdatedAt = System.currentTimeMillis()
                )
                AgentTaskStore.upsertTask(stateFile, record, limit = 12)
                mainHandler.post { onUpdate(record) }
            }

            publish(AgentTaskStatus.RUNNING, -1, 0L)

            val result = execProcess(definition.id, definition.command, definition.workingDirectory, cancellationFlag) { line ->
                logBuilder.append(line).append('\n')
                publish(AgentTaskStatus.RUNNING, -1, 0L)
            }

            activeCancellationFlags.remove(definition.id)

            val status = resolveStatus(cancellationFlag, result)
            appendOutcome(logBuilder, cancellationFlag, result)
            publish(status, result.exitCode, System.currentTimeMillis())
        }
    }

    fun runPlan(plan: AgentTaskPlan, workingDirectory: String, stateFile: File, tags: List<String>, onUpdate: (AgentTaskRecord) -> Unit) {
        executor.execute {
            val startedAt = System.currentTimeMillis()
            val cancellationFlag = AtomicBoolean(false)
            activeCancellationFlags[plan.id] = cancellationFlag

            val definition = AgentTaskDefinition(
                id = plan.id,
                name = plan.name,
                description = plan.description,
                command = plan.steps.joinToString("\n") { it.command },
                workingDirectory = workingDirectory,
                tags = tags
            )

            fun publish(status: AgentTaskStatus, exitCode: Int, finishedAt: Long, log: String, steps: List<AgentTaskStepResult>) {
                val record = AgentTaskRecord(
                    definition = definition,
                    status = status,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    exitCode = exitCode,
                    log = log.ifBlank { "任务执行中…" },
                    lastUpdatedAt = System.currentTimeMillis(),
                    steps = steps
                )
                AgentTaskStore.upsertTask(stateFile, record, limit = 12)
                mainHandler.post { onUpdate(record) }
            }

            val outcome = AgentPlanEngine.execute(
                plan = plan,
                isCancelled = { cancellationFlag.get() },
                onProgress = { steps, log -> publish(AgentTaskStatus.RUNNING, -1, 0L, log, steps) },
                exec = { step, onLine ->
                    val result = execProcess(plan.id, step.command, workingDirectory, cancellationFlag, onLine = onLine)
                    AgentPlanEngine.StepOutput(result.exitCode, result.output, result.failure)
                }
            )

            activeCancellationFlags.remove(plan.id)
            publish(outcome.status, outcome.exitCode, System.currentTimeMillis(), outcome.log, outcome.steps)
        }
    }

    /** 单次命令执行超时（毫秒）。超时或取消时强制 kill，防止单线程 executor 被挂死进程永久占用。 */
    private val DEFAULT_EXEC_TIMEOUT_MS = 10 * 60_000L

    private fun execProcess(
        taskId: String,
        command: String,
        workingDirectory: String,
        cancellationFlag: AtomicBoolean,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS,
        onLine: (String) -> Unit
    ): ExecResult {
        val workDir = File(workingDirectory)
        if (!workDir.isDirectory) {
            return ExecResult(-1, "", IllegalStateException("工作目录不存在: $workingDirectory"))
        }
        // 安全护栏：拦截危险命令 / 对受保护路径的破坏性写（非交互上下文无人工确认，直接失败）
        val guard = SafeCommandGuard.check(command)
        if (guard.verdict != SafeCommandGuard.Verdict.ALLOW) {
            AIDevLogger.w("AgentTaskRunner", "SafeCommandGuard 拦截命令: ${guard.reason}")
            return ExecResult(-1, "", IllegalStateException("SafeCommandGuard 拦截：${guard.reason}"))
        }
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            activeProcesses[taskId] = process

            // 独立 daemon 线程消费输出流，主线程只负责「轮询退出 / 取消 / 超时」，避免读行阻塞导致无法 cancel
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            output.append(line).append('\n')
                            onLine(line!!)
                        }
                    }
                } catch (_: Throwable) {
                    // 流在 destroyForcibly 后被关闭，读线程自然退出
                }
            }.apply { isDaemon = true; start() }

            val deadline = System.currentTimeMillis() + timeoutMs
            var exited = false
            try {
                while (true) {
                    if (cancellationFlag.get()) { process.destroyForcibly(); break }
                    if (System.currentTimeMillis() >= deadline) { process.destroyForcibly(); break }
                    if (process.waitFor(250, TimeUnit.MILLISECONDS)) { exited = true; break }
                }
            } finally {
                reader.join(5000)
            }
            val exitCode = if (exited) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            activeProcesses.remove(taskId)
            ExecResult(exitCode, output.toString())
        } catch (t: Throwable) {
            activeProcesses.remove(taskId)
            ExecResult(-1, "", t)
        }
    }

    private fun resolveStatus(cancellationFlag: AtomicBoolean, result: ExecResult): AgentTaskStatus = when {
        cancellationFlag.get() -> AgentTaskStatus.CANCELLED
        result.failure != null -> AgentTaskStatus.FAILED
        result.exitCode == 0 -> AgentTaskStatus.SUCCEEDED
        else -> AgentTaskStatus.FAILED
    }

    private fun appendOutcome(logBuilder: StringBuilder, cancellationFlag: AtomicBoolean, result: ExecResult) {
        when {
            cancellationFlag.get() -> logBuilder.append("\n■ 任务已被取消")
            result.failure != null -> logBuilder.append("\n✖ 任务启动失败：${result.failure.message ?: result.failure}")
            result.exitCode != 0 -> logBuilder.append("\n✖ 任务失败 (exit=${result.exitCode})")
        }
    }

    fun cancelTask(taskId: String) {
        activeCancellationFlags[taskId]?.set(true)
        activeProcesses[taskId]?.destroyForcibly()
        activeProcesses.remove(taskId)
        activeCancellationFlags.remove(taskId)
    }
}
