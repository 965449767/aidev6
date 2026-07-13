package com.aidev.six.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun saveAndLoadRoundTripPreservesSteps() {
        val tempDir = Files.createTempDirectory("agent-task-store-steps").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = "plan-1",
                name = "Android 闭环",
                description = "构建并验证",
                command = "./gradlew assembleDebug\n./gradlew test",
                workingDirectory = "/tmp/workspace"
            ),
            status = AgentTaskStatus.FAILED,
            exitCode = 2,
            log = "aggregated log",
            steps = listOf(
                AgentTaskStepResult("构建", AgentTaskStatus.SUCCEEDED, 0, "build ok"),
                AgentTaskStepResult("测试", AgentTaskStatus.FAILED, 2, "test\nfailed")
            )
        )

        AgentTaskStore.saveState(stateFile, listOf(task))
        val loaded = AgentTaskStore.loadState(stateFile).single()

        assertEquals(2, loaded.steps.size)
        assertEquals("构建", loaded.steps[0].name)
        assertEquals(AgentTaskStatus.SUCCEEDED, loaded.steps[0].status)
        assertEquals(AgentTaskStatus.FAILED, loaded.steps[1].status)
        assertEquals(2, loaded.steps[1].exitCode)
        assertEquals("test\nfailed", loaded.steps[1].log)
    }

    @Test
    fun legacyRecordWithoutStepsParsesWithEmptySteps() {
        val tempDir = Files.createTempDirectory("agent-task-store-legacy").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val sep = "\u001F"
        val legacyLine = listOf(
            "task-legacy", "旧任务", "描述", "ls -la", "/tmp",
            "", "SUCCEEDED", "100", "200", "0", "done", "150"
        ).joinToString(sep)
        stateFile.writeText(legacyLine)

        val loaded = AgentTaskStore.loadState(stateFile).single()

        assertEquals("旧任务", loaded.definition.name)
        assertEquals(AgentTaskStatus.SUCCEEDED, loaded.status)
        assertEquals(0, loaded.steps.size)
    }

    @Test
    fun upsertTaskUsesCacheAndFlushWritesToDisk() {
        val tempDir = Files.createTempDirectory("agent-task-cache").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task1 = AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = "t1", name = "任务1", description = "desc",
                command = "ls", workingDirectory = "/tmp"
            ),
            status = AgentTaskStatus.RUNNING
        )

        // upsertTask 应立即更新缓存，loadState 从缓存读取
        val result = AgentTaskStore.upsertTask(stateFile, task1)
        assertEquals(1, result.size)
        assertEquals("任务1", result[0].definition.name)

        // 立即 loadState 应从缓存返回（无需等 debounce）
        val loaded = AgentTaskStore.loadState(stateFile)
        assertEquals(1, loaded.size)

        // flush 后磁盘应有数据
        AgentTaskStore.flush()
        assertTrue("flush 后文件应存在", stateFile.exists())
        assertTrue("flush 后文件应非空", stateFile.length() > 0)

        tempDir.deleteRecursively()
    }

    @Test
    fun clearTasksUpdatesCache() {
        val tempDir = Files.createTempDirectory("agent-task-clear").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = AgentTaskRecord(
            definition = AgentTaskDefinition(
                id = "t1", name = "任务", description = "desc",
                command = "ls", workingDirectory = "/tmp"
            ),
            status = AgentTaskStatus.SUCCEEDED
        )
        AgentTaskStore.saveState(stateFile, listOf(task))
        assertEquals(1, AgentTaskStore.loadState(stateFile).size)

        AgentTaskStore.clearTasks(stateFile)
        assertEquals(0, AgentTaskStore.loadState(stateFile).size)

        AgentTaskStore.flush()
        assertTrue("clearTasks 后 flush 文件应为空", stateFile.readText().isBlank())

        tempDir.deleteRecursively()
    }
}
