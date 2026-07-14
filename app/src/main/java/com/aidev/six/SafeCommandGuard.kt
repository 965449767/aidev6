package com.aidev.six

/**
 * Agent 命令安全护栏：在 [com.aidev.six.agent.AgentTaskRunner] 通过
 * `/system/bin/sh -c <command>` 执行命令前做校验，防止 AI 自动跑出毁灭性命令。
 *
 * 规则是单一事实源；[scripts.safe_bash_guard] 的 shell 版仅作 PRoot 终端可选 wrapper，需与这里保持一致。
 * 设计目标：只拦"明确危险 / 对受保护外部路径的破坏性写"，放行正常构建产物拷贝等良性操作，避免误伤自进化闭环。
 */
object SafeCommandGuard {

    enum class Verdict { ALLOW, BLOCK, REQUIRE_CONFIRM }

    data class Result(val verdict: Verdict, val reason: String = "")

    /** 危险命令模式（小写子串匹配，fail-safe：命中即拦截）。 */
    private val DANGEROUS_PATTERNS = listOf(
        "rm -rf /",
        "rm -rf .",
        "rm -rf ~",
        "mkfs",
        "dd if=",
        ":(){",
        ":(){ :|:& };:",
        "git reset --hard",
        "git clean -fd",
        "git push --force",
        "git push -f ",
        "drop database",
        "truncate table",
        "supabase db reset",
        "prisma migrate reset",
    )

    /** 受保护的外部写路径前缀（写屏障）。 */
    private val PROTECTED_PREFIXES = listOf(
        "/sdcard/",
        "/storage/emulated/0/",
        "/storage/emulated/",
        "/data/",
    )

    /**
     * 对受保护路径的"破坏性"写动词（非交互上下文需确认）。
     * 注意：cp / mv 等良性拷贝（构建产物落盘）不在此列，正常放行。
     */
    private val DESTRUCTIVE_VERBS = listOf(
        "rm ", "mkfs", "dd ", "truncate ", "chmod ", "chown ", "format ", ">", "shred ",
    )

    /**
     * 校验命令。
     * @param command 待执行命令
     * @param interactive 是否为用户交互会话（交互时 REQUIRE_CONFIRM 可交由用户决定；非交互直接视为拦截）
     */
    fun check(command: String, interactive: Boolean = false): Result {
        val lower = command.lowercase()

        for (pattern in DANGEROUS_PATTERNS) {
            if (lower.contains(pattern)) {
                return Result(Verdict.BLOCK, "命中危险命令模式：$pattern")
            }
        }

        if (!interactive) {
            for (prefix in PROTECTED_PREFIXES) {
                if (lower.contains(prefix) && DESTRUCTIVE_VERBS.any { lower.contains(it) }) {
                    return Result(
                        Verdict.REQUIRE_CONFIRM,
                        "将对受保护路径 $prefix 执行破坏性写操作，需用户确认：$command",
                    )
                }
            }
        }

        return Result(Verdict.ALLOW)
    }
}
