package com.aidev.six.task

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanEngineTest {

    private fun plan(vararg steps: Pair<String, String>): TaskPlan =
        TaskPlan(
            id = "plan-1",
            name = "计划",
            description = "描述",
            steps = steps.map { TaskStep(it.first, it.second) }
        )

    @Test
    fun allStepsSucceedRunsSequentially() {
        val executed = mutableListOf<String>()
        val outcome = PlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test"),
            isCancelled = { false },
            exec = { step, _ ->
                executed.add(step.command)
                PlanEngine.StepOutput(0, "ok")
            }
        )

        assertEquals(listOf("build", "test"), executed)
        assertEquals(TaskStatus.SUCCEEDED, outcome.status)
        assertEquals(0, outcome.exitCode)
        assertEquals(2, outcome.steps.size)
        assertTrue(outcome.steps.all { it.status == TaskStatus.SUCCEEDED })
    }

    @Test
    fun failingStepStopsRemainingSteps() {
        val executed = mutableListOf<String>()
        val outcome = PlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test", "发布" to "deploy"),
            isCancelled = { false },
            exec = { step, _ ->
                executed.add(step.command)
                if (step.command == "test") PlanEngine.StepOutput(2, "boom")
                else PlanEngine.StepOutput(0, "ok")
            }
        )

        assertEquals(listOf("build", "test"), executed)
        assertEquals(TaskStatus.FAILED, outcome.status)
        assertEquals(2, outcome.exitCode)
        assertEquals(TaskStatus.SUCCEEDED, outcome.steps[0].status)
        assertEquals(TaskStatus.FAILED, outcome.steps[1].status)
        assertEquals(TaskStatus.PENDING, outcome.steps[2].status)
    }

    @Test
    fun launchFailureMarksStepFailed() {
        val outcome = PlanEngine.execute(
            plan = plan("构建" to "build"),
            isCancelled = { false },
            exec = { _, _ -> PlanEngine.StepOutput(-1, "", IllegalStateException("工作目录不存在")) }
        )

        assertEquals(TaskStatus.FAILED, outcome.status)
        assertEquals(TaskStatus.FAILED, outcome.steps.single().status)
        assertTrue(outcome.log.contains("工作目录不存在"))
    }

    @Test
    fun cancellationBeforeStepStopsExecution() {
        val executed = mutableListOf<String>()
        val outcome = PlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test"),
            isCancelled = { true },
            exec = { step, _ ->
                executed.add(step.command)
                PlanEngine.StepOutput(0, "ok")
            }
        )

        assertTrue(executed.isEmpty())
        assertEquals(TaskStatus.CANCELLED, outcome.status)
    }

    @Test
    fun progressCallbackReceivesRunningState() {
        val statuses = mutableListOf<TaskStatus>()
        PlanEngine.execute(
            plan = plan("构建" to "build"),
            isCancelled = { false },
            onProgress = { steps, _ -> statuses.add(steps.first().status) },
            exec = { _, onLine ->
                onLine("compiling")
                PlanEngine.StepOutput(0, "compiling\n")
            }
        )

        assertTrue(statuses.contains(TaskStatus.RUNNING))
    }
}
