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

    /** 危险命令模式（小写 + 空白归一化后子串匹配，fail-safe：命中即拦截）。 */
    private val DANGEROUS_PATTERNS = listOf(
        "rm -rf /",
        "rm -rf .",
        "rm -rf ~",
        "mkfs",
        "dd ",                       // 覆盖 dd if= 与 dd of= 两种写法
        "shred ",
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
        "eval ",                     // 阻止 eval 动态执行
        "sh -c",                     // 阻止嵌套 sh -c 混淆
        "bash -c",
    )

    /** 命令替换 / 反引号执行（高风险，BYOD 上下文一律拦截）。 */
    private val SUBSTITUTION_PATTERNS = listOf("$(", "`")

    /**
     * 非交互（agent 自动执行）上下文的「可执行名白名单」（P2-8 纵深防御）。
     * 仅放行构建/工具链常见命令；其余一律拦截，避免 agent 自生成命令跑出意外程序。
     * 交互会话（用户手动）不受此限，仅受下方危险模式/受保护路径约束。
     */
    private val AGENT_ALLOWED_BINARIES = setOf(
        "gradlew", "git", "cp", "mv", "mkdir", "rm", "cat", "echo", "ls", "cd",
        "pwd", "touch", "test", "find", "sed", "awk", "grep", "egrep", "tar",
        "unzip", "zip", "chmod", "diff", "head", "tail", "sort", "uniq", "wc", "cut",
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
     * 把 shell 命令按链接触发符切分为若干子句，提取每句的「首个词」作为可执行名候选。
     * 忽略 | && || ; & ( ) < > 换行 等元字符分隔，覆盖 `a && b | c` 这类复合命令。
     */
    private fun extractExecutables(command: String): List<String> {
        return command.split(Regex("\\|\\||&&|\\||;|&|\\(|\\)|<|>|\n"))
            .map { seg -> seg.trim().split(Regex("\\s+")).firstOrNull()?.trim() ?: "" }
            .filter { it.isNotBlank() }
    }

    /** 判断某可执行 token 是否落入 agent 白名单（支持相对路径 ./x、aidev-* 工具、已知工作区绝对路径）。 */
    private fun isAllowedExecutable(token: String): Boolean {
        if (token.startsWith("./")) return true
        if (token.startsWith("/workspace/") || token.startsWith("/host-home/") ||
            token.startsWith("/Android/") || token.startsWith("/root/")
        ) return true
        val name = token.substringAfterLast('/')
        if (name.startsWith("aidev")) return true
        return name in AGENT_ALLOWED_BINARIES
    }

    /**
     * 校验命令。
     * @param command 待执行命令
     * @param interactive 是否为用户交互会话（交互时 REQUIRE_CONFIRM 可交由用户决定；非交互直接视为拦截）
     */
    fun check(command: String, interactive: Boolean = false): Result {
        // 空白归一化（折叠连续空格/制表符），消除 "rm  -rf /" 这类绕过；保持小写便于大小写不敏感
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        val lower = command.lowercase()

        for (pattern in DANGEROUS_PATTERNS) {
            if (normalized.contains(pattern)) {
                return Result(Verdict.BLOCK, "命中危险命令模式：$pattern")
            }
        }
        for (pattern in SUBSTITUTION_PATTERNS) {
            if (lower.contains(pattern)) {
                return Result(Verdict.BLOCK, "命中命令替换/反引号执行（高风险）：$pattern")
            }
        }

        // P2-8：非交互（agent）上下文强制可执行名白名单，防止自生成命令跑出未授权程序
        if (!interactive) {
            for (exe in extractExecutables(command)) {
                if (!isAllowedExecutable(exe)) {
                    return Result(Verdict.BLOCK, "非交互上下文命令含未授权可执行程序：$exe（仅允许白名单内构建/工具命令）")
                }
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
