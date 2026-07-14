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

    /** PRoot 内候选仓库路径（按常见部署排序）。 */
    private val CANDIDATES = listOf(
        "/host-home/aidev6",
        "/host-home/workspace/aidev6",
        "/host-home",
        "/root/projects/aidev6",
        "/root/projects",
    )

    fun detect(ctx: Context): String? = listRepos(ctx).firstOrNull()

    /** 列出 PRoot 内所有 git 仓库（候选路径 + /host-home 下 find 兜底）。 */
    fun listRepos(ctx: Context): List<String> {
        val opts = ProotLauncher.Options(
            rootfs = PathConfig.agentRootfs(ctx).absolutePath,
            cwd = "/host-home",
            timeoutSec = 30,
        )
        val result = mutableListOf<String>()
        for (c in CANDIDATES) {
            if (isWorkTree(ctx, c, opts)) result.add(c)
        }
        val f = ProotLauncher.run(ctx, "find /host-home -maxdepth 4 -name .git 2>/dev/null", opts)
        f.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { gitDir ->
            val repo = gitDir.removeSuffix("/.git")
            if (repo.isNotBlank() && repo !in result) result.add(repo)
        }
        return result.distinct()
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
