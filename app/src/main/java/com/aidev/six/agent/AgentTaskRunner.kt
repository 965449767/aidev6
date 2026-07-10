package com.aidev.six.agent

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class AgentTaskRunner {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val activeCancellationFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun runTask(definition: AgentTaskDefinition, stateFile: File, onUpdate: (AgentTaskRecord) -> Unit) {
        executor.execute {
            val task = AgentTaskRecord(
                definition = definition,
                status = AgentTaskStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis()
            )
            val cancellationFlag = AtomicBoolean(false)
            activeCancellationFlags[definition.id] = cancellationFlag
            AgentTaskStore.upsertTask(stateFile, task, limit = 12)
            mainHandler.post { onUpdate(task) }

            val process = ProcessBuilder("/system/bin/sh", "-c", definition.command)
                .directory(File(definition.workingDirectory))
                .redirectErrorStream(true)
                .start()
            activeProcesses[definition.id] = process

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            activeProcesses.remove(definition.id)
            activeCancellationFlags.remove(definition.id)

            val status = when {
                cancellationFlag.get() -> AgentTaskStatus.CANCELLED
                exitCode == 0 -> AgentTaskStatus.SUCCEEDED
                else -> AgentTaskStatus.FAILED
            }
            val finished = AgentTaskRecord(
                definition = definition,
                status = status,
                startedAt = task.startedAt,
                finishedAt = System.currentTimeMillis(),
                exitCode = exitCode,
                log = if (status == AgentTaskStatus.CANCELLED) {
                    "任务已被取消"
                } else {
                    output.takeIf { it.isNotBlank() } ?: "任务已完成"
                },
                lastUpdatedAt = System.currentTimeMillis()
            )
            AgentTaskStore.upsertTask(stateFile, finished, limit = 12)
            mainHandler.post { onUpdate(finished) }
        }
    }

    fun cancelTask(taskId: String) {
        activeCancellationFlags[taskId]?.set(true)
        activeProcesses[taskId]?.destroy()
        activeProcesses.remove(taskId)
        activeCancellationFlags.remove(taskId)
    }
}
