package com.aidev.four

import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class ProjectMeta(
    val dir: File,
    val name: String,
    val language: String,
    val hasApk: Boolean = false,
    val apkSize: Long = 0L,
    val apkPath: String = "",
)

object ProjectDetector {

    fun findProjectRoot(startDir: File): File? {
        var best: File? = null
        var gitRoot: File? = null
        var current = startDir.absoluteFile
        while (true) {
            if (hasGradleFile(current)) best = current
            if (File(current, ".git").isDirectory && gitRoot == null) gitRoot = current
            val parent = current.parentFile ?: break
            if (parent.name == "Workspace") break
            current = parent
        }
        return best ?: gitRoot
    }

    fun isProjectRoot(dir: File): Boolean = hasGradleFile(dir) || File(dir, ".git").isDirectory

    fun isAndroidProject(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val appBuild = File(dir, "app/build.gradle.kts")
        if (appBuild.isFile) return true
        val appBuildGradle = File(dir, "app/build.gradle")
        if (appBuildGradle.isFile) return true
        return false
    }

    fun findAndroidProjects(workspaceDir: File): List<File> {
        if (!workspaceDir.isDirectory) return emptyList()
        return workspaceDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { langDir ->
                langDir.listFiles()
                    ?.filter { it.isDirectory && isAndroidProject(it) }
                    .orEmpty()
            }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
    }

    fun findProjectsInWorkspace(workspaceDir: File): List<File> {
        if (!workspaceDir.isDirectory) return emptyList()
        return workspaceDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { langDir ->
                langDir.listFiles()
                    ?.filter { it.isDirectory && isProjectRoot(it) }
                    .orEmpty()
            }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
    }

    fun getProjectMeta(dir: File): ProjectMeta {
        val name = dir.name
        val isAndroid = isAndroidProject(dir)
        val language = if (isAndroid) "Android" else detectLanguage(dir)
        val (hasApk, apkSize, apkPath) = if (isAndroid) findLatestApk(dir) else Triple(false, 0L, "")
        return ProjectMeta(
            dir = dir, name = name, language = language,
            hasApk = hasApk, apkSize = apkSize, apkPath = apkPath,
        )
    }

    private fun detectLanguage(dir: File): String {
        if (File(dir, "package.json").isFile) return "Node"
        if (File(dir, "Cargo.toml").isFile) return "Rust"
        if (File(dir, "go.mod").isFile) return "Go"
        if (File(dir, "requirements.txt").isFile || File(dir, "setup.py").isFile) return "Python"
        if (hasGradleFile(dir)) return "Kotlin"
        return "Unknown"
    }

    private fun findLatestApk(projectDir: File): Triple<Boolean, Long, String> {
        val apkDir = File(projectDir, "app/build/outputs/apk/debug")
        if (!apkDir.isDirectory) return Triple(false, 0L, "")
        val apk = apkDir.listFiles()
            ?.filter { it.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() } ?: return Triple(false, 0L, "")
        return Triple(true, apk.length(), apk.absolutePath)
    }

    private fun hasGradleFile(dir: File): Boolean =
        File(dir, "settings.gradle").isFile || File(dir, "settings.gradle.kts").isFile
}
