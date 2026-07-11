package com.aidev.six.agent

/**
 * 构建进度阶段推导（单一真源）。
 *
 * 无论构建由「服务器中心」手动提交，还是宇宙 A（OpenCode）在终端调用 `aidev-build-request`
 * 自动提交，都从同一份构建日志推导出「准备宇宙 B → 编译 → 安装 → 拉起」四个阶段，
 * 保证两条路径在 AF 面板呈现完全一致的过程。
 */
internal object BuildProgress {

    private data class Phase(val name: String, val markers: List<String>)

    private val phaseOrder = listOf(
        Phase("准备宇宙 B", listOf("准备宇宙 B", "宇宙 B 已就绪", "install-compiler", "JDK17")),
        Phase("编译", listOf("进入宇宙 B 编译", "gradlew assembleDebug", "构建成功", "构建失败")),
        Phase("安装", listOf("通过 Shizuku 安装", "复制 APK", "未找到产物", "已安装")),
        Phase("拉起", listOf("已拉起")),
    )

    fun derive(log: String): List<AgentTaskStepResult> {
        if (log.isBlank()) return emptyList()
        var activeReached = -1
        phaseOrder.forEachIndexed { idx, phase ->
            if (phase.markers.any { log.contains(it) }) activeReached = idx
        }
        if (activeReached < 0) return emptyList()
        return phaseOrder.mapIndexed { idx, phase ->
            val status = when {
                idx < activeReached -> AgentTaskStatus.SUCCEEDED
                idx == activeReached -> AgentTaskStatus.RUNNING
                else -> AgentTaskStatus.PENDING
            }
            AgentTaskStepResult(name = phase.name, status = status)
        }
    }

    fun finalize(steps: List<AgentTaskStepResult>, success: Boolean): List<AgentTaskStepResult> {
        if (steps.isEmpty()) return emptyList()
        return steps.map { step ->
            when (step.status) {
                AgentTaskStatus.RUNNING -> step.copy(status = if (success) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED)
                AgentTaskStatus.PENDING -> if (success) step.copy(status = AgentTaskStatus.SUCCEEDED) else step
                else -> step
            }
        }
    }
}
