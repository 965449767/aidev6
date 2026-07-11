package com.aidev.six.agent

/**
 * 纯逻辑的分步任务执行引擎，不依赖 Android 运行时。
 * 负责按顺序执行步骤、失败即停、聚合日志与步骤状态，便于单元测试。
 */
internal object AgentPlanEngine {

    data class StepOutput(val exitCode: Int, val output: String, val failure: Throwable? = null)

    data class PlanOutcome(
        val status: AgentTaskStatus,
        val steps: List<AgentTaskStepResult>,
        val exitCode: Int,
        val log: String,
    )

    fun execute(
        plan: AgentTaskPlan,
        isCancelled: () -> Boolean,
        onProgress: (steps: List<AgentTaskStepResult>, log: String) -> Unit = { _, _ -> },
        exec: (step: AgentTaskStep, onLine: (String) -> Unit) -> StepOutput,
    ): PlanOutcome {
        val logBuilder = StringBuilder()
        val stepResults = plan.steps
            .map { AgentTaskStepResult(name = it.name, status = AgentTaskStatus.PENDING) }
            .toMutableList()

        onProgress(stepResults.toList(), logBuilder.toString())

        var overallStatus = AgentTaskStatus.SUCCEEDED
        var lastExitCode = 0

        for ((index, step) in plan.steps.withIndex()) {
            if (isCancelled()) {
                overallStatus = AgentTaskStatus.CANCELLED
                break
            }

            stepResults[index] = stepResults[index].copy(status = AgentTaskStatus.RUNNING)
            logBuilder.append("▶ 步骤 ${index + 1}/${plan.steps.size}：${step.name}\n")
            onProgress(stepResults.toList(), logBuilder.toString())

            val stepLog = StringBuilder()
            val result = exec(step) { line ->
                stepLog.append(line).append('\n')
                logBuilder.append(line).append('\n')
                stepResults[index] = stepResults[index].copy(log = stepLog.toString())
                onProgress(stepResults.toList(), logBuilder.toString())
            }
            lastExitCode = result.exitCode

            val stepStatus = resolveStatus(isCancelled(), result)
            stepResults[index] = stepResults[index].copy(
                status = stepStatus,
                exitCode = result.exitCode,
                log = stepLog.toString(),
            )

            when (stepStatus) {
                AgentTaskStatus.SUCCEEDED -> logBuilder.append("✔ 步骤完成：${step.name}\n\n")
                AgentTaskStatus.CANCELLED -> {
                    logBuilder.append("■ 已取消：${step.name}\n")
                    overallStatus = AgentTaskStatus.CANCELLED
                }
                else -> {
                    val reason = result.failure?.message?.let { "：$it" } ?: ""
                    logBuilder.append("✖ 步骤失败：${step.name} (exit=${result.exitCode})$reason\n")
                    logBuilder.append("↳ 已停止后续步骤\n")
                    overallStatus = AgentTaskStatus.FAILED
                }
            }

            onProgress(stepResults.toList(), logBuilder.toString())
            if (stepStatus != AgentTaskStatus.SUCCEEDED) break
        }

        val finalExitCode = if (overallStatus == AgentTaskStatus.SUCCEEDED) 0 else lastExitCode
        return PlanOutcome(overallStatus, stepResults.toList(), finalExitCode, logBuilder.toString())
    }

    private fun resolveStatus(cancelled: Boolean, result: StepOutput): AgentTaskStatus = when {
        cancelled -> AgentTaskStatus.CANCELLED
        result.failure != null -> AgentTaskStatus.FAILED
        result.exitCode == 0 -> AgentTaskStatus.SUCCEEDED
        else -> AgentTaskStatus.FAILED
    }
}
