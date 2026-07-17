package com.aidev.six.task

/**
 * 构建进度阶段推导（单一真源）。
 *
 * 无论构建由「服务器中心」手动提交，还是在终端调用 `aidev-build-request`
 * 提交，都从同一份构建日志推导出「准备宇宙 B → 编译 → 安装 → 拉起」四个阶段，
 * 保证不同入口在 AF 面板呈现完全一致的过程。
 *
 * OPT-03: 新增 [Phase] 枚举，发布进度时直接用枚举值，不再仅靠日志文本推导。
 * [derive] 保留作为向后兼容的降级路径。
 */
internal object BuildProgress {

    /** 结构化阶段枚举——发布者在关键节点显式设置，不再依赖日志文本匹配。 */
    enum class Phase { PREPARE, COMPILE, INSTALL, LAUNCH }

    private val phaseNames = mapOf(
        Phase.PREPARE to "准备宇宙 B",
        Phase.COMPILE to "编译",
        Phase.INSTALL to "安装",
        Phase.LAUNCH to "拉起",
    )
    private val phaseOrder = Phase.entries

    /** 根据结构化 [current] 阶段生成完整步骤列表（含后续 pending 阶段）。 */
    fun deriveFromPhase(current: Phase): List<TaskStepResult> {
        val idx = phaseOrder.indexOf(current)
        return phaseOrder.mapIndexed { i, phase ->
            val status = when {
                i < idx -> TaskStatus.SUCCEEDED
                i == idx -> TaskStatus.RUNNING
                else -> TaskStatus.PENDING
            }
            TaskStepResult(name = phaseNames[phase] ?: phase.name, status = status)
        }
    }

    /**
     * 仅发布到 [current] 为止的阶段。
     * 构建黑盒只拥有 PREPARE→COMPILE；安装/拉起由独立部署黑盒（DeployBridgeService）负责，
     * 不应在此误报为已完成。故 finalize 也只收尾这些阶段，避免 SUCCESS 时把 INSTALL/LAUNCH 错标成 ✓。
     */
    fun deriveUpTo(current: Phase): List<TaskStepResult> {
        val idx = phaseOrder.indexOf(current)
        return phaseOrder.take(idx + 1).mapIndexed { i, phase ->
            val status = if (i < idx) TaskStatus.SUCCEEDED else TaskStatus.RUNNING
            TaskStepResult(name = phaseNames[phase] ?: phase.name, status = status)
        }
    }

    /** 降级：仅凭日志文本推导阶段（向后兼容）。 */
    fun derive(log: String): List<TaskStepResult> {
        if (log.isBlank()) return emptyList()
        var activeReached = -1
        val markers = mapOf(
            Phase.PREPARE to listOf("准备宇宙 B", "宇宙 B 已就绪", "install-compiler", "JDK17"),
            Phase.COMPILE to listOf("进入宇宙 B 编译", "gradlew assembleDebug", "构建成功", "构建失败"),
            Phase.INSTALL to listOf("通过 Shizuku 安装", "复制 APK", "未找到产物", "已安装"),
            Phase.LAUNCH to listOf("已拉起"),
        )
        phaseOrder.forEachIndexed { idx, phase ->
            if (markers[phase]?.any { log.contains(it) } == true) activeReached = idx
        }
        if (activeReached < 0) return emptyList()
        return phaseOrder.mapIndexed { idx, phase ->
            val status = when {
                idx < activeReached -> TaskStatus.SUCCEEDED
                idx == activeReached -> TaskStatus.RUNNING
                else -> TaskStatus.PENDING
            }
            TaskStepResult(name = phaseNames[phase] ?: phase.name, status = status)
        }
    }

    fun finalize(steps: List<TaskStepResult>, success: Boolean): List<TaskStepResult> {
        if (steps.isEmpty()) return emptyList()
        return steps.map { step ->
            when (step.status) {
                TaskStatus.RUNNING -> step.copy(status = if (success) TaskStatus.SUCCEEDED else TaskStatus.FAILED)
                TaskStatus.PENDING -> if (success) step.copy(status = TaskStatus.SUCCEEDED) else step
                else -> step
            }
        }
    }
}
