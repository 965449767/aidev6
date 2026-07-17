package com.aidev.six.task

/**
 * 纯逻辑的分步任务执行引擎，不依赖 Android 运行时。
 * 负责按顺序执行步骤、失败即停、聚合日志与步骤状态，便于单元测试。
 */
internal object PlanEngine {

    data class StepOutput(val exitCode: Int, val output: String, val failure: Throwable? = null)

    data class PlanOutcome(
        val status: TaskStatus,
        val steps: List<TaskStepResult>,
        val exitCode: Int,
        val log: String,
    )

    fun execute(
        plan: TaskPlan,
        isCancelled: () -> Boolean,
        onProgress: (steps: List<TaskStepResult>, log: String) -> Unit = { _, _ -> },
        exec: (step: TaskStep, onLine: (String) -> Unit) -> StepOutput,
    ): PlanOutcome {
        val logBuilder = StringBuilder()
        val stepResults = plan.steps
            .map { TaskStepResult(name = it.name, status = TaskStatus.PENDING) }
            .toMutableList()

        onProgress(stepResults.toList(), logBuilder.toString())

        var overallStatus = TaskStatus.SUCCEEDED
        var lastExitCode = 0

        for ((index, step) in plan.steps.withIndex()) {
            if (isCancelled()) {
                overallStatus = TaskStatus.CANCELLED
                break
            }

            stepResults[index] = stepResults[index].copy(status = TaskStatus.RUNNING)
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
                TaskStatus.SUCCEEDED -> logBuilder.append("✔ 步骤完成：${step.name}\n\n")
                TaskStatus.CANCELLED -> {
                    logBuilder.append("■ 已取消：${step.name}\n")
                    overallStatus = TaskStatus.CANCELLED
                }
                else -> {
                    val reason = result.failure?.message?.let { "：$it" } ?: ""
                    logBuilder.append("✖ 步骤失败：${step.name} (exit=${result.exitCode})$reason\n")
                    logBuilder.append("↳ 已停止后续步骤\n")
                    overallStatus = TaskStatus.FAILED
                }
            }

            onProgress(stepResults.toList(), logBuilder.toString())
            if (stepStatus != TaskStatus.SUCCEEDED) break
        }

        val finalExitCode = if (overallStatus == TaskStatus.SUCCEEDED) 0 else lastExitCode
        return PlanOutcome(overallStatus, stepResults.toList(), finalExitCode, logBuilder.toString())
    }

    private fun resolveStatus(cancelled: Boolean, result: StepOutput): TaskStatus = when {
        cancelled -> TaskStatus.CANCELLED
        result.failure != null -> TaskStatus.FAILED
        result.exitCode == 0 -> TaskStatus.SUCCEEDED
        else -> TaskStatus.FAILED
    }
}
