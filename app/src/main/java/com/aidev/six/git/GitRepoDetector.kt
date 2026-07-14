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

    fun detect(ctx: Context): String? {
        val opts = ProotLauncher.Options(
            rootfs = PathConfig.agentRootfs(ctx).absolutePath,
            cwd = "/host-home",
            timeoutSec = 30,
        )
        for (c in CANDIDATES) {
            val r = ProotLauncher.run(
                ctx,
                "git -C $c rev-parse --is-inside-work-tree >/dev/null 2>&1 && echo \"$c\"",
                opts,
            )
            if (r.stdout.trim() == c) return c
        }
        val f = ProotLauncher.run(
            ctx,
            "d=\$(find /host-home -maxdepth 4 -name .git -type d 2>/dev/null | head -1); [ -n \"\$d\" ] && dirname \"\$d\"",
            opts,
        )
        return f.stdout.trim().ifBlank { null }
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
