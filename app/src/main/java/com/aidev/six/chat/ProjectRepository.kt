package com.aidev.six.chat

import android.content.Context
import com.aidev.six.PathConfig
import java.io.File

/**
 * 列出可供 AI 作业的项目目录。
 *
 * android 端 `home/workspace` 被 bind 进宇宙A 的 `/workspace`，因此传给 opencode 的
 * `x-opencode-directory` 使用 proot 内路径（`/workspace` 或 `/workspace/<子目录>`）。
 */
object ProjectRepository {

    const val WORKSPACE_ROOT = "/workspace"

    /** 返回项目列表：首项为 workspace 根，其余为其一级子目录。 */
    fun list(ctx: Context): List<ProjectDir> {
        val ws = PathConfig.workspaceDir(ctx).apply { mkdirs() }
        val root = ProjectDir("workspace（根）", WORKSPACE_ROOT)
        val children = ws.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ProjectDir(it.name, "$WORKSPACE_ROOT/${it.name}") }
            ?: emptyList()
        return listOf(root) + children
    }

    /** 由 proot 路径反推显示名。 */
    fun displayName(path: String?): String = when {
        path.isNullOrBlank() || path == WORKSPACE_ROOT -> "workspace（根）"
        else -> path.substringAfterLast('/')
    }

    /** 在 workspace 下新建项目目录，返回 proot 路径；名字非法或已存在返回 null。 */
    fun create(ctx: Context, name: String): ProjectDir? {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-')
        if (safe.isEmpty()) return null
        val dir = File(PathConfig.workspaceDir(ctx), safe)
        if (dir.exists()) return null
        return if (dir.mkdirs()) ProjectDir(safe, "$WORKSPACE_ROOT/$safe") else null
    }
}
