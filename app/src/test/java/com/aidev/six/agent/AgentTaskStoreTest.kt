package com.aidev.six.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentTaskStoreTest {

    @Test
    fun saveAndLoadRoundTripPreservesTaskState() {
        val tempDir = Files.createTempDirectory("agent-task-store").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = "task-1",
                name = "构建 APK",
                description = "构建调试包",
                command = "./gradlew assembleDebug",
                workingDirectory = "/tmp/workspace"
            ),
            status = AgentTaskStatus.RUNNING,
            startedAt = 100L,
            finishedAt = 200L,
            exitCode = 0,
            log = "hello from task",
            lastUpdatedAt = 150L
        )

        AgentTaskStore.saveState(stateFile, listOf(task))
        val loaded = AgentTaskStore.loadState(stateFile)

        assertEquals(1, loaded.size)
        assertEquals("构建 APK", loaded.single().definition.name)
        assertEquals(AgentTaskStatus.RUNNING, loaded.single().status)
        assertEquals("hello from task", loaded.single().log)
        assertEquals(0, loaded.single().exitCode)
    }
}
