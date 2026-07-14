package com.aidev.six.agent

import android.os.Handler
import android.os.Looper
import com.aidev.six.AIDevLogger
import com.aidev.six.SafeCommandGuard
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentTaskRunner {
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
                    val result = execProcess(plan.id, step.command, workingDirectory, cancellationFlag, onLine)
                    AgentPlanEngine.StepOutput(result.exitCode, result.output, result.failure)
                }
            )

            activeCancellationFlags.remove(plan.id)
            publish(outcome.status, outcome.exitCode, System.currentTimeMillis(), outcome.log, outcome.steps)
        }
    }

    private fun execProcess(
        taskId: String,
        command: String,
        workingDirectory: String,
        cancellationFlag: AtomicBoolean,
        onLine: (String) -> Unit
    ): ExecResult {
        return try {
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
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            activeProcesses[taskId] = process

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    output.append(line).append('\n')
                    onLine(line)
                    if (cancellationFlag.get()) {
                        process.destroy()
                        break
                    }
                }
            }
            val exitCode = process.waitFor()
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
        activeProcesses[taskId]?.destroy()
        activeProcesses.remove(taskId)
        activeCancellationFlags.remove(taskId)
    }
}
