package com.aidev.six.agent

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class AgentTaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

internal data class AgentTaskDefinition(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val workingDirectory: String,
    val tags: List<String> = emptyList()
)

internal data class AgentTaskStepResult(
    val name: String,
    val status: AgentTaskStatus = AgentTaskStatus.PENDING,
    val exitCode: Int = -1,
    val log: String = ""
)

internal data class AgentTaskRecord(
    val definition: AgentTaskDefinition,
    val status: AgentTaskStatus,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val exitCode: Int = -1,
    val log: String = "",
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val steps: List<AgentTaskStepResult> = emptyList(),
    // 宇宙 A（OpenCode）闭环回填：读到崩溃回流后应用了什么修复（F04/G 反向驱动）
    val note: String = ""
)

internal data class AgentTaskStep(
    val name: String,
    val command: String,
)

internal data class AgentTaskTemplate(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<AgentTaskStep> = emptyList(),
)

internal data class AgentTaskPlan(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<AgentTaskStep> = emptyList(),
) {
    companion object {
        fun fromTemplate(name: String, description: String, template: AgentTaskTemplate): AgentTaskPlan =
            AgentTaskPlan(
                id = template.id,
                name = name,
                description = description,
                steps = template.steps,
            )
    }
}

internal object AgentTaskStore {

    private const val FIELD_SEPARATOR = "\u001F"
    private const val LIST_SEPARATOR = "\u001E"
    private const val STEP_SEPARATOR = "\u001D"
    private const val STEP_FIELD_SEPARATOR = "\u001C"
    private const val DEBOUNCE_MS = 2000L

    // 内存缓存：file.absolutePath → tasks
    private val cache = ConcurrentHashMap<String, List<AgentTaskRecord>>()
    // 脏标记：哪些文件有未落盘的修改
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AgentTaskStore-debounce").apply { isDaemon = true }
    }
    private val lock = Any()

    fun loadState(file: File): List<AgentTaskRecord> {
        val key = file.absolutePath
        synchronized(lock) {
            cache[key]?.let { return it }
            if (!file.exists()) return emptyList()
            return runCatching {
                file.readLines()
                    .filter(String::isNotBlank)
                    .mapNotNull(::parseTaskLine)
            }.getOrDefault(emptyList()).also { cache[key] = it }
        }
    }

    fun saveState(file: File, tasks: List<AgentTaskRecord>) {
        val key = file.absolutePath
        synchronized(lock) {
            cache[key] = tasks
            dirty.remove(key)
            file.parentFile?.mkdirs()
            file.writeText(tasks.joinToString(separator = "\n") { serializeTask(it) })
        }
    }

    fun upsertTask(file: File, task: AgentTaskRecord, limit: Int = 12): List<AgentTaskRecord> {
        val key = file.absolutePath
        synchronized(lock) {
            val existing = (cache[key] ?: loadState(file).toMutableList()).toMutableList()
            existing.removeAll { it.definition.id == task.definition.id }
            existing.add(0, task)
            val trimmed = existing.take(limit)
            cache[key] = trimmed
        }
        scheduleDebounce(file)
        return cache[key] ?: emptyList()
    }

    fun removeTask(file: File, id: String): List<AgentTaskRecord> {
        val key = file.absolutePath
        val remaining: List<AgentTaskRecord>
        synchronized(lock) {
            val existing = (cache[key] ?: loadState(file)).toMutableList()
            remaining = existing.filterNot { it.definition.id == id }
            cache[key] = remaining
        }
        scheduleDebounce(file)
        return remaining
    }

    fun clearTasks(file: File): List<AgentTaskRecord> {
        val key = file.absolutePath
        synchronized(lock) {
            cache[key] = emptyList()
        }
        scheduleDebounce(file)
        return emptyList()
    }

    /** 强制将所有脏数据立即写盘，用于进程退出前调用。 */
    fun flush() {
        synchronized(lock) {
            for (key in dirty.toList()) {
                val tasks = cache[key] ?: continue
                val file = File(key)
                file.parentFile?.mkdirs()
                file.writeText(tasks.joinToString(separator = "\n") { serializeTask(it) })
            }
            dirty.clear()
        }
    }

    private fun scheduleDebounce(file: File) {
        val key = file.absolutePath
        dirty.add(key)
        scheduler.schedule({
            if (!dirty.contains(key)) return@schedule
            val tasks = cache[key] ?: return@schedule
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(tasks.joinToString(separator = "\n") { serializeTask(it) })
            }
            dirty.remove(key)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    private fun serializeTask(task: AgentTaskRecord): String {
        return buildString {
            append(encode(task.definition.id))
            append(FIELD_SEPARATOR)
            append(encode(task.definition.name))
            append(FIELD_SEPARATOR)
            append(encode(task.definition.description))
            append(FIELD_SEPARATOR)
            append(encode(task.definition.command))
            append(FIELD_SEPARATOR)
            append(encode(task.definition.workingDirectory))
            append(FIELD_SEPARATOR)
            append(task.definition.tags.joinToString(LIST_SEPARATOR) { encode(it) })
            append(FIELD_SEPARATOR)
            append(task.status.name)
            append(FIELD_SEPARATOR)
            append(task.startedAt)
            append(FIELD_SEPARATOR)
            append(task.finishedAt)
            append(FIELD_SEPARATOR)
            append(task.exitCode)
            append(FIELD_SEPARATOR)
            append(encode(task.log))
            append(FIELD_SEPARATOR)
            append(task.lastUpdatedAt)
            append(FIELD_SEPARATOR)
            append(serializeSteps(task.steps))
            append(FIELD_SEPARATOR)
            append(encode(task.note))
        }
    }

    private fun serializeSteps(steps: List<AgentTaskStepResult>): String {
        return steps.joinToString(STEP_SEPARATOR) { step ->
            buildString {
                append(encode(step.name))
                append(STEP_FIELD_SEPARATOR)
                append(step.status.name)
                append(STEP_FIELD_SEPARATOR)
                append(step.exitCode)
                append(STEP_FIELD_SEPARATOR)
                append(encode(step.log))
            }
        }
    }

    private fun parseSteps(value: String): List<AgentTaskStepResult> {
        if (value.isEmpty()) return emptyList()
        return value.split(STEP_SEPARATOR).mapNotNull { raw ->
            val fields = raw.split(STEP_FIELD_SEPARATOR)
            if (fields.size < 4) return@mapNotNull null
            AgentTaskStepResult(
                name = decode(fields[0]),
                status = runCatching { AgentTaskStatus.valueOf(fields[1]) }.getOrDefault(AgentTaskStatus.PENDING),
                exitCode = fields[2].toIntOrNull() ?: -1,
                log = decode(fields[3])
            )
        }
    }

    private fun parseTaskLine(line: String): AgentTaskRecord? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 12) return null
        val tags = parts[5].takeIf { it.isNotEmpty() }?.split(LIST_SEPARATOR)?.map(::decode)?.filter(String::isNotBlank) ?: emptyList()
        return AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = decode(parts[0]),
                name = decode(parts[1]),
                description = decode(parts[2]),
                command = decode(parts[3]),
                workingDirectory = decode(parts[4]),
                tags = tags
            ),
            status = runCatching { AgentTaskStatus.valueOf(parts[6]) }.getOrDefault(AgentTaskStatus.PENDING),
            startedAt = parts[7].toLongOrNull() ?: 0L,
            finishedAt = parts[8].toLongOrNull() ?: 0L,
            exitCode = parts[9].toIntOrNull() ?: -1,
            log = decode(parts[10]),
            lastUpdatedAt = parts[11].toLongOrNull() ?: System.currentTimeMillis(),
            steps = if (parts.size >= 13) parseSteps(parts[12]) else emptyList(),
            note = if (parts.size >= 14) decode(parts[13]) else ""
        )
    }

    private fun encode(value: String): String {
        return value.replace("\\", "\\\\")
            .replace(FIELD_SEPARATOR, "\\u001f")
            .replace(LIST_SEPARATOR, "\\u001e")
            .replace(STEP_SEPARATOR, "\\u001d")
            .replace(STEP_FIELD_SEPARATOR, "\\u001c")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun decode(value: String): String {
        return value.replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\u001c", STEP_FIELD_SEPARATOR)
            .replace("\\u001d", STEP_SEPARATOR)
            .replace("\\u001e", LIST_SEPARATOR)
            .replace("\\u001f", FIELD_SEPARATOR)
            .replace("\\\\", "\\")
    }
}
