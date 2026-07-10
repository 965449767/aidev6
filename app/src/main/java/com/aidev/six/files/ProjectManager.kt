package com.aidev.six.files

import android.app.Activity
import android.widget.EditText
import com.aidev.six.ProjectCommands
import com.aidev.six.ShellActivity
import com.aidev.six.TerminalCommandBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class ProjectManager(
    private val activity: Activity,
    private val onDirChanged: () -> Unit = {},
    private val onNavigateDir: (File) -> Unit = {}
) {
    fun inspectProject(dir: File) {
        val markers = listOf(
            "package.json" to "Node / 前端项目",
            "build.gradle" to "Gradle 项目",
            "build.gradle.kts" to "Gradle Kotlin 项目",
            "settings.gradle" to "Gradle 多模块项目",
            "pyproject.toml" to "Python 项目",
            "requirements.txt" to "Python 依赖项目",
            "Cargo.toml" to "Rust 项目",
            "go.mod" to "Go 项目",
            ".git" to "Git 仓库",
            "README.md" to "README 文档"
        ).filter { File(dir, it.first).exists() }
        val body = if (markers.isEmpty()) {
            "未识别到常见项目标记。\n\n目录：${dir.absolutePath}"
        } else {
            markers.joinToString("\n") { "✓ ${it.second}：${it.first}" } + "\n\n目录：${dir.absolutePath}"
        }
        val runCmd = when {
            File(dir, "package.json").exists() -> "cd \"${dir.absolutePath}\" && npm install && npm run dev"
            File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists() -> "cd \"${dir.absolutePath}\" && ./gradlew assembleDebug"
            File(dir, "requirements.txt").exists() -> "cd \"${dir.absolutePath}\" && pip install -r requirements.txt --break-system-packages"
            File(dir, "pyproject.toml").exists() -> "cd \"${dir.absolutePath}\" && python3 -m pip install . --break-system-packages"
            else -> "cd \"${dir.absolutePath}\" && ls -la"
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目识别")
            .setMessage(body)
            .setPositiveButton("运行建议") { _, _ ->
                if (activity is ShellActivity) {
                    TerminalCommandBus.post(runCmd)
                    (activity as? ShellActivity)?.switchTo(ShellActivity.TAB_TERMINAL)
                }
            }
            .setNeutralButton("复制路径") { _, _ ->
                copyText(activity, "AIDev 项目路径", dir.absolutePath)
                toast(activity, "已复制项目路径")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun projectWorkspace(dir: File) {
        val items = arrayOf(
            "标记当前项目", "清除当前项目",
            "项目概览", "项目健康检查",
            "运行修复建议", "最近操作",
            "项目脚本", "复制诊断报告",
            "复制修复命令", "项目识别",
            "查看 README", "Git Status", "Git Diff",
            "安装依赖", "运行开发服务",
            "运行测试", "构建项目",
            "终端进入目录", "复制项目命令",
            "导出项目摘要"
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目工作区")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> markCurrentProject(dir)
                    1 -> clearCurrentProject()
                    2 -> projectOverview(dir)
                    3 -> runInTerminal("cd \"${dir.absolutePath}\" && ${ProjectCommands.healthCommand(dir)}")
                    4 -> confirmProjectRepair(dir)
                    5 -> showProjectHistory()
                    6 -> showProjectScripts(dir)
                    7 -> copyProjectReport(dir)
                    8 -> copyRepairCommand(dir)
                    9 -> inspectProject(dir)
                    10 -> showReadme(dir)
                    11 -> runInTerminal("cd \"${dir.absolutePath}\" && git status --short --branch")
                    12 -> runInTerminal("cd \"${dir.absolutePath}\" && git diff --stat")
                    13 -> runInTerminal("cd \"${dir.absolutePath}\" && ${ProjectCommands.installCommand(dir)}")
                    14 -> runInTerminal("cd \"${dir.absolutePath}\" && ${ProjectCommands.devCommand(dir)}")
                    15 -> runInTerminal("cd \"${dir.absolutePath}\" && ${ProjectCommands.testCommand(dir)}")
                    16 -> runInTerminal("cd \"${dir.absolutePath}\" && ${ProjectCommands.buildCommand(dir)}")
                    17 -> runInTerminal("cd \"${dir.absolutePath}\" && pwd && ls -la")
                    18 -> copyProjectCommands(dir)
                    19 -> exportProjectSummary(dir)
                }
            }
            .show()
    }

    fun markCurrentProject(dir: File) {
        activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
            .edit().putString("current_project_path", dir.absolutePath).apply()
        rememberRecentDir(dir)
        toast(activity, "已标记当前项目")
    }

    fun jumpCurrentProject(): File? {
        val path = activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE).getString("current_project_path", "").orEmpty()
        val dir = File(path)
        if (path.isBlank() || !dir.isDirectory) {
            toast(activity, "未标记当前项目")
            return null
        }
        return dir
    }

    fun clearCurrentProject() {
        activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE).edit().remove("current_project_path").apply()
        toast(activity, "已清除当前项目")
    }

    fun projectOverview(dir: File) {
        val current = activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE).getString("current_project_path", "") == dir.absolutePath
        val body = listOf(
            "名称：${dir.name}",
            "路径：${dir.absolutePath}",
            "当前项目：${if (current) "是" else "否"}",
            "README：${if (listOf("README.md", "README.txt", "readme.md").any { File(dir, it).isFile }) "有" else "无"}",
            "Git：${if (File(dir, ".git").exists()) "有" else "无"}",
            "健康摘要：${ProjectCommands.detectSummary(dir)}",
            "安装命令：cd \"${dir.absolutePath}\" && ${ProjectCommands.installCommand(dir)}",
            "开发命令：cd \"${dir.absolutePath}\" && ${ProjectCommands.devCommand(dir)}",
            "测试命令：cd \"${dir.absolutePath}\" && ${ProjectCommands.testCommand(dir)}",
            "构建命令：cd \"${dir.absolutePath}\" && ${ProjectCommands.buildCommand(dir)}",
            "修复命令：cd \"${dir.absolutePath}\" && ${ProjectCommands.repairCommand(dir)}"
        ).joinToString("\n")
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目概览")
            .setMessage(body)
            .setPositiveButton("复制") { _, _ ->
                copyText(activity, "AIDev 项目概览", body)
                toast(activity, "已复制项目概览")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun copyProjectCommands(dir: File) {
        val body = listOf(
            "cd \"${dir.absolutePath}\"",
            ProjectCommands.installCommand(dir),
            ProjectCommands.devCommand(dir),
            ProjectCommands.testCommand(dir),
            ProjectCommands.buildCommand(dir)
        ).joinToString("\n")
        copyText(activity, "AIDev 项目命令", body)
        toast(activity, "已复制项目命令")
    }

    fun copyProjectReport(dir: File) {
        val body = listOf(
            "项目：${dir.name}",
            "路径：${dir.absolutePath}",
            "健康摘要：${ProjectCommands.detectSummary(dir)}",
            "README：${if (listOf("README.md", "README.txt", "readme.md").any { File(dir, it).isFile }) "有" else "无"}",
            "Git：${if (File(dir, ".git").exists()) "有" else "无"}",
            "安装：${ProjectCommands.installCommand(dir)}",
            "开发：${ProjectCommands.devCommand(dir)}",
            "测试：${ProjectCommands.testCommand(dir)}",
            "构建：${ProjectCommands.buildCommand(dir)}",
            "诊断：${ProjectCommands.healthCommand(dir)}",
            "修复：${ProjectCommands.repairCommand(dir)}"
        ).joinToString("\n")
        copyText(activity, "AIDev 项目诊断", body)
        toast(activity, "已复制诊断报告")
    }

    private fun copyRepairCommand(dir: File) {
        copyText(activity, "AIDev 修复命令", ProjectCommands.repairCommand(dir))
        toast(activity, "已复制修复命令")
    }

    private fun confirmProjectRepair(dir: File) {
        val command = "cd \"${dir.absolutePath}\" && ${ProjectCommands.repairCommand(dir)}"
        MaterialAlertDialogBuilder(activity)
            .setTitle("确认修复项目")
            .setMessage("项目：${dir.name}\n路径：${dir.absolutePath}\n\n执行：\n$command\n\n注意：某些修复会删除缓存、依赖目录或锁文件。")
            .setPositiveButton("确认执行") { _, _ ->
                rememberProjectAction("修复", dir, command)
                runInTerminal(command)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showProjectHistory() {
        val prefs = activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
        val rows = prefs.getString("project_action_history", "")?.lines()?.filter { it.isNotBlank() }.orEmpty().takeLast(20).reversed()
        if (rows.isEmpty()) return toast(activity, "暂无项目操作历史")
        MaterialAlertDialogBuilder(activity)
            .setTitle("最近项目操作")
            .setItems(rows.map { row ->
                val parts = row.split("\t", limit = 4)
                "${parts.getOrNull(1) ?: "操作"}\n${parts.getOrNull(3) ?: row}"
            }.toTypedArray()) { _, which ->
                val parts = rows[which].split("\t", limit = 4)
                val path = parts.getOrNull(2).orEmpty()
                val command = parts.getOrNull(3).orEmpty()
                if (path.isNotBlank() && command.isNotBlank()) runInTerminal("cd \"$path\" && $command")
            }
            .setPositiveButton("复制全部") { _, _ ->
                copyText(activity, "AIDev 项目历史", rows.joinToString("\n"))
                toast(activity, "已复制项目历史")
            }
            .setNegativeButton("清空") { _, _ ->
                prefs.edit().remove("project_action_history").apply()
                toast(activity, "已清空历史")
            }
            .show()
    }

    fun showProjectScripts(dir: File) {
        val packageJson = File(dir, "package.json")
        if (!packageJson.isFile) return showNonNodeProjectScripts(dir)
        val text = runCatching { packageJson.readText() }.getOrDefault("")
        val scriptsBlock = Regex("\"scripts\"\\s*:\\s*\\{([\\s\\S]*?)\\}").find(text)?.groupValues?.getOrNull(1).orEmpty()
        val scripts = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(scriptsBlock)
            .map { it.groupValues[1] to it.groupValues[2] }
            .take(30).toList()
        if (scripts.isEmpty()) return toast(activity, "未识别到 npm scripts")
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目脚本")
            .setItems(scripts.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which ->
                runInTerminal("cd \"${dir.absolutePath}\" && npm run ${scripts[which].first}")
            }
            .setPositiveButton("复制脚本") { _, _ ->
                copyText(activity, "AIDev 项目脚本", scripts.joinToString("\n") { "${it.first}: ${it.second}" })
                toast(activity, "已复制项目脚本")
            }
            .show()
    }

    private fun showNonNodeProjectScripts(dir: File) {
        val scripts = when {
            File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists() -> listOf(
                "Gradle 任务列表" to "./gradlew tasks --all",
                "Gradle 测试" to "./gradlew test",
                "Gradle 构建 Debug" to "./gradlew assembleDebug",
                "Gradle 清理" to "./gradlew clean"
            )
            File(dir, "manage.py").exists() -> listOf(
                "Django 开发服务" to "python3 manage.py runserver 0.0.0.0:8000",
                "Django 迁移检查" to "python3 manage.py showmigrations",
                "Django 测试" to "python3 manage.py test"
            )
            File(dir, "requirements.txt").exists() || File(dir, "pyproject.toml").exists() -> listOf(
                "Python 测试" to "python3 -m pytest",
                "Python HTTP 服务" to "python3 -m http.server 8000",
                "Python 版本" to "python3 --version"
            )
            File(dir, "go.mod").exists() -> listOf(
                "Go 测试" to "go test ./...",
                "Go 构建" to "go build ./...",
                "Go 整理依赖" to "go mod tidy"
            )
            File(dir, "Cargo.toml").exists() -> listOf(
                "Cargo 测试" to "cargo test",
                "Cargo 构建" to "cargo build",
                "Cargo 元数据" to "cargo metadata --no-deps"
            )
            else -> emptyList()
        }
        if (scripts.isEmpty()) return toast(activity, "未识别到可用项目脚本")
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目脚本")
            .setItems(scripts.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which ->
                runInTerminal("cd \"${dir.absolutePath}\" && ${scripts[which].second}")
            }
            .setPositiveButton("复制脚本") { _, _ ->
                copyText(activity, "AIDev 项目脚本", scripts.joinToString("\n") { "${it.first}: ${it.second}" })
                toast(activity, "已复制项目脚本")
            }
            .show()
    }

    fun exportProjectSummary(dir: File) {
        val out = File(dir, "aidev-project-summary.txt")
        val body = listOf(
            "AIDev 项目摘要",
            "项目：${dir.name}",
            "路径：${dir.absolutePath}",
            "健康摘要：${ProjectCommands.detectSummary(dir)}",
            "安装：${ProjectCommands.installCommand(dir)}",
            "开发：${ProjectCommands.devCommand(dir)}",
            "测试：${ProjectCommands.testCommand(dir)}",
            "构建：${ProjectCommands.buildCommand(dir)}",
            "诊断：${ProjectCommands.healthCommand(dir)}",
            "修复：${ProjectCommands.repairCommand(dir)}"
        ).joinToString("\n")
        runCatching { out.writeText(body) }
            .onSuccess { toast(activity, "已导出项目摘要"); onDirChanged() }
            .onFailure { toast(activity, "导出失败：${it.message}") }
    }

    private fun showReadme(dir: File) {
        val readme = listOf("README.md", "README.txt", "readme.md").map { File(dir, it) }.firstOrNull { it.isFile }
        if (readme == null) {
            toast(activity, "未找到 README")
            return
        }
        val text = runCatching { readme.readText().take(16000) }.getOrElse { "读取失败：${it.message}" }
        MaterialAlertDialogBuilder(activity)
            .setTitle(readme.name)
            .setMessage(text)
            .setNeutralButton("复制") { _, _ ->
                copyText(activity, "AIDev README", text)
                toast(activity, "已复制 README")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun rememberRecentDir(dir: File) {
        val prefs = activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
        val set = prefs.getStringSet("file_recent_dirs", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(dir.absolutePath)
        while (set.size > 80) set.remove(set.first())
        prefs.edit().putStringSet("file_recent_dirs", set).apply()
    }

    private fun runInTerminal(command: String) {
        rememberProjectAction(commandLabel(command), File(activity.filesDir, "home"), command)
        if (activity is ShellActivity) {
            TerminalCommandBus.post(command)
            activity.switchTo(ShellActivity.TAB_TERMINAL)
        }
    }

    private fun commandLabel(command: String): String = when {
        command.contains("git status") -> "Git"
        command.contains("test") || command.contains("pytest") -> "测试"
        command.contains("build") || command.contains("assemble") -> "构建"
        command.contains("install") || command.contains("fetch") -> "依赖"
        command.contains("clean") || command.contains("tidy") -> "修复"
        command.contains("tasks --all") || command.contains("collect-only") -> "诊断"
        else -> "命令"
    }

    private fun rememberProjectAction(label: String, dir: File, command: String) {
        val prefs = activity.getSharedPreferences("aidev_ui", Activity.MODE_PRIVATE)
        val line = "${System.currentTimeMillis()}\t$label\t${dir.absolutePath}\t$command"
        val old = prefs.getString("project_action_history", "") ?: ""
        val next = (old.lines().filter { it.isNotBlank() } + line).takeLast(20).joinToString("\n")
        prefs.edit().putString("project_action_history", next).apply()
    }
}
