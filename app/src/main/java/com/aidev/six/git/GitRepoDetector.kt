package com.aidev.six.git

import android.content.Context
import com.aidev.six.PathConfig
import com.aidev.six.terminal.ProotLauncher
import java.io.File

/**
 * 在 PRoot 内探测当前 git 仓库路径，避免把仓库位置硬编码死。
 *
 * aidev6 源码在真机 PRoot 内的实际位置因部署而异（/host-home/aidev6、
 * /host-home/workspace/aidev6、/root/projects/... 等），本类按候选列表 +
 * find 兜底定位第一个含 .git 且 `git rev-parse` 可用的目录。
 *
 * 返回的要么是 PRoot 内绝对路径（供 `git -C <path>` 直接使用），
 * 经 [toAndroidPath] 可转回 Android 侧真实文件路径（供 ContextManager 索引）。
 */
object GitRepoDetector {

    /** 工作目录（用户项目所在），PRoot 内路径。扫描只限定在此，避免误扫 ubuntu-rootfs 等系统绑定目录。 */
    const val WORKSPACE_PROOT = "/host-home/workspace"

    /** 仅当 workspace 内无任何项目时的兜底候选。 */
    private val FALLBACK = listOf(
        "/root/projects/aidev6",
        "/root/projects",
        "/host-home/aidev6",
    )

    /** 发现的项目：路径 + 是否为 git 仓库（非 git 项目无法做 diff 评审，需先 git init）。 */
    data class ProjectEntry(val path: String, val isGit: Boolean)

    fun detect(ctx: Context): String? = listProjects(ctx).firstOrNull()?.path

    /**
     * 发现工作目录（/host-home/workspace）下的所有项目：
     *  - 含 .git 的目录（git 项目，可评审）
     *  - 含 build.gradle / settings.gradle 等标记的目录（安卓/gradle 项目，可能未 git 初始化）
     * 两者合并去重，不扫描系统目录。
     */
    fun listProjects(ctx: Context): List<ProjectEntry> {
        val opts = ProotLauncher.Options(
            rootfs = PathConfig.agentRootfs(ctx).absolutePath,
            cwd = "/host-home",
            timeoutSec = 30,
        )
        val gitSet = LinkedHashSet<String>()
        ProotLauncher.run(ctx, "find $WORKSPACE_PROOT -maxdepth 6 -name .git 2>/dev/null", opts)
            .stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .forEach { gitSet.add(it.removeSuffix("/.git")) }
        val gradleSet = LinkedHashSet<String>()
        ProotLauncher.run(
            ctx,
            "find $WORKSPACE_PROOT -maxdepth 6 -name build.gradle -o -name build.gradle.kts -o -name settings.gradle -o -name settings.gradle.kts 2>/dev/null",
            opts,
        ).stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
            .forEach { gradleSet.add(it.substringBeforeLast("/")) }
        val map = LinkedHashMap<String, Boolean>()
        gitSet.forEach { map[it] = true }
        gradleSet.forEach { map[it] = map[it] ?: false }
        var result = map.map { ProjectEntry(it.key, it.value) }
        if (result.isEmpty()) {
            for (c in FALLBACK) {
                if (isWorkTree(ctx, c, opts)) result = result + ProjectEntry(c, true)
            }
        }
        // 去掉嵌套在其它项目内的目录（如项目根 settings.gradle 已收录，其 app 模块的 build.gradle 不应单列）
        val kept = mutableListOf<ProjectEntry>()
        for (e in result.sortedBy { it.path.length }) {
            if (kept.none { e.path.startsWith(it.path + "/") }) kept.add(e)
        }
        return kept
    }

    private fun isWorkTree(ctx: Context, c: String, opts: ProotLauncher.Options): Boolean {
        val r = ProotLauncher.run(
            ctx,
            "git -C $c rev-parse --is-inside-work-tree >/dev/null 2>&1 && echo ok",
            opts,
        )
        return r.stdout.trim() == "ok"
    }

    /** 把 PRoot 内路径转回 Android 侧真实文件路径（供 ContextManager 等直接读文件）。 */
    fun toAndroidPath(ctx: Context, prootPath: String): File {
        val home = PathConfig.aidevHome(ctx).absolutePath
        return when {
            prootPath == "/host-home" -> File(home)
            prootPath.startsWith("/host-home/") -> File(home, prootPath.removePrefix("/host-home/"))
            prootPath.startsWith("/root/") -> File(PathConfig.agentRootfs(ctx), prootPath.removePrefix("/"))
            else -> File(prootPath)
        }
    }
}
