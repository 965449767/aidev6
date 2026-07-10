package com.aidev.six.agent

import java.io.File

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

internal data class AgentTaskRecord(
    val definition: AgentTaskDefinition,
    val status: AgentTaskStatus,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val exitCode: Int = -1,
    val log: String = "",
    val lastUpdatedAt: Long = System.currentTimeMillis()
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

    fun loadState(file: File): List<AgentTaskRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .filter(String::isNotBlank)
                .mapNotNull(::parseTaskLine)
        }.getOrDefault(emptyList())
    }

    fun saveState(file: File, tasks: List<AgentTaskRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(tasks.joinToString(separator = "\n") { serializeTask(it) })
    }

    fun upsertTask(file: File, task: AgentTaskRecord, limit: Int = 12): List<AgentTaskRecord> {
        val existing = loadState(file).toMutableList()
        existing.removeAll { it.definition.id == task.definition.id }
        existing.add(0, task)
        val trimmed = existing.take(limit)
        saveState(file, trimmed)
        return trimmed
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
            lastUpdatedAt = parts[11].toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun encode(value: String): String {
        return value.replace("\\", "\\\\")
            .replace(FIELD_SEPARATOR, "\\u001f")
            .replace(LIST_SEPARATOR, "\\u001e")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun decode(value: String): String {
        return value.replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\u001e", LIST_SEPARATOR)
            .replace("\\u001f", FIELD_SEPARATOR)
            .replace("\\\\", "\\")
    }
}
