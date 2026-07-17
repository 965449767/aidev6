package com.aidev.six.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TaskStoreTest {

    @Test
    fun saveAndLoadRoundTripPreservesTaskState() {
        val tempDir = Files.createTempDirectory("agent-task-store").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = TaskRecord(
            definition = TaskDefinition(
                id = "task-1",
                name = "构建 APK",
                description = "构建调试包",
                command = "./gradlew assembleDebug",
                workingDirectory = "/tmp/workspace"
            ),
            status = TaskStatus.RUNNING,
            startedAt = 100L,
            finishedAt = 200L,
            exitCode = 0,
            log = "hello from task",
            lastUpdatedAt = 150L
        )

        TaskStore.saveState(stateFile, listOf(task))
        val loaded = TaskStore.loadState(stateFile)

        assertEquals(1, loaded.size)
        assertEquals("构建 APK", loaded.single().definition.name)
        assertEquals(TaskStatus.RUNNING, loaded.single().status)
        assertEquals("hello from task", loaded.single().log)
        assertEquals(0, loaded.single().exitCode)
    }

    @Test
    fun saveAndLoadRoundTripPreservesSteps() {
        val tempDir = Files.createTempDirectory("agent-task-store-steps").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = TaskRecord(
            definition = TaskDefinition(
                id = "plan-1",
                name = "Android 闭环",
                description = "构建并验证",
                command = "./gradlew assembleDebug\n./gradlew test",
                workingDirectory = "/tmp/workspace"
            ),
            status = TaskStatus.FAILED,
            exitCode = 2,
            log = "aggregated log",
            steps = listOf(
                TaskStepResult("构建", TaskStatus.SUCCEEDED, 0, "build ok"),
                TaskStepResult("测试", TaskStatus.FAILED, 2, "test\nfailed")
            )
        )

        TaskStore.saveState(stateFile, listOf(task))
        val loaded = TaskStore.loadState(stateFile).single()

        assertEquals(2, loaded.steps.size)
        assertEquals("构建", loaded.steps[0].name)
        assertEquals(TaskStatus.SUCCEEDED, loaded.steps[0].status)
        assertEquals(TaskStatus.FAILED, loaded.steps[1].status)
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

        val loaded = TaskStore.loadState(stateFile).single()

        assertEquals("旧任务", loaded.definition.name)
        assertEquals(TaskStatus.SUCCEEDED, loaded.status)
        assertEquals(0, loaded.steps.size)
    }

    @Test
    fun upsertTaskUsesCacheAndFlushWritesToDisk() {
        val tempDir = Files.createTempDirectory("agent-task-cache").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task1 = TaskRecord(
            definition = TaskDefinition(
                id = "t1", name = "任务1", description = "desc",
                command = "ls", workingDirectory = "/tmp"
            ),
            status = TaskStatus.RUNNING
        )

        // upsertTask 应立即更新缓存，loadState 从缓存读取
        val result = TaskStore.upsertTask(stateFile, task1)
        assertEquals(1, result.size)
        assertEquals("任务1", result[0].definition.name)

        // 立即 loadState 应从缓存返回（无需等 debounce）
        val loaded = TaskStore.loadState(stateFile)
        assertEquals(1, loaded.size)

        // flush 后磁盘应有数据
        TaskStore.flush()
        assertTrue("flush 后文件应存在", stateFile.exists())
        assertTrue("flush 后文件应非空", stateFile.length() > 0)

        tempDir.deleteRecursively()
    }

    @Test
    fun clearTasksUpdatesCache() {
        val tempDir = Files.createTempDirectory("agent-task-clear").toFile()
        val stateFile = File(tempDir, "tasks.json")

        val task = TaskRecord(
            definition = TaskDefinition(
                id = "t1", name = "任务", description = "desc",
                command = "ls", workingDirectory = "/tmp"
            ),
            status = TaskStatus.SUCCEEDED
        )
        TaskStore.saveState(stateFile, listOf(task))
        assertEquals(1, TaskStore.loadState(stateFile).size)

        TaskStore.clearTasks(stateFile)
        assertEquals(0, TaskStore.loadState(stateFile).size)

        TaskStore.flush()
        assertTrue("clearTasks 后 flush 文件应为空", stateFile.readText().isBlank())

        tempDir.deleteRecursively()
    }
}
