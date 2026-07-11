package com.aidev.six.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentPlanEngineTest {

    private fun plan(vararg steps: Pair<String, String>): AgentTaskPlan =
        AgentTaskPlan(
            id = "plan-1",
            name = "计划",
            description = "描述",
            steps = steps.map { AgentTaskStep(it.first, it.second) }
        )

    @Test
    fun allStepsSucceedRunsSequentially() {
        val executed = mutableListOf<String>()
        val outcome = AgentPlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test"),
            isCancelled = { false },
            exec = { step, _ ->
                executed.add(step.command)
                AgentPlanEngine.StepOutput(0, "ok")
            }
        )

        assertEquals(listOf("build", "test"), executed)
        assertEquals(AgentTaskStatus.SUCCEEDED, outcome.status)
        assertEquals(0, outcome.exitCode)
        assertEquals(2, outcome.steps.size)
        assertTrue(outcome.steps.all { it.status == AgentTaskStatus.SUCCEEDED })
    }

    @Test
    fun failingStepStopsRemainingSteps() {
        val executed = mutableListOf<String>()
        val outcome = AgentPlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test", "发布" to "deploy"),
            isCancelled = { false },
            exec = { step, _ ->
                executed.add(step.command)
                if (step.command == "test") AgentPlanEngine.StepOutput(2, "boom")
                else AgentPlanEngine.StepOutput(0, "ok")
            }
        )

        assertEquals(listOf("build", "test"), executed)
        assertEquals(AgentTaskStatus.FAILED, outcome.status)
        assertEquals(2, outcome.exitCode)
        assertEquals(AgentTaskStatus.SUCCEEDED, outcome.steps[0].status)
        assertEquals(AgentTaskStatus.FAILED, outcome.steps[1].status)
        assertEquals(AgentTaskStatus.PENDING, outcome.steps[2].status)
    }

    @Test
    fun launchFailureMarksStepFailed() {
        val outcome = AgentPlanEngine.execute(
            plan = plan("构建" to "build"),
            isCancelled = { false },
            exec = { _, _ -> AgentPlanEngine.StepOutput(-1, "", IllegalStateException("工作目录不存在")) }
        )

        assertEquals(AgentTaskStatus.FAILED, outcome.status)
        assertEquals(AgentTaskStatus.FAILED, outcome.steps.single().status)
        assertTrue(outcome.log.contains("工作目录不存在"))
    }

    @Test
    fun cancellationBeforeStepStopsExecution() {
        val executed = mutableListOf<String>()
        val outcome = AgentPlanEngine.execute(
            plan = plan("构建" to "build", "测试" to "test"),
            isCancelled = { true },
            exec = { step, _ ->
                executed.add(step.command)
                AgentPlanEngine.StepOutput(0, "ok")
            }
        )

        assertTrue(executed.isEmpty())
        assertEquals(AgentTaskStatus.CANCELLED, outcome.status)
    }

    @Test
    fun progressCallbackReceivesRunningState() {
        val statuses = mutableListOf<AgentTaskStatus>()
        AgentPlanEngine.execute(
            plan = plan("构建" to "build"),
            isCancelled = { false },
            onProgress = { steps, _ -> statuses.add(steps.first().status) },
            exec = { _, onLine ->
                onLine("compiling")
                AgentPlanEngine.StepOutput(0, "compiling\n")
            }
        )

        assertTrue(statuses.contains(AgentTaskStatus.RUNNING))
    }
}
