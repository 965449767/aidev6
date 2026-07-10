package com.aidev.six

import android.content.Context
import android.os.Environment
import java.io.File

object PathConfig {

    fun aidevHome(ctx: Context) = File(ctx.filesDir, "home")

    /** 宇宙 A：OpenCode + AI 工具宿主（当前实现沿用既有 ubuntu-rootfs 目录） */
    fun rootfs(ctx: Context) = File(aidevHome(ctx), "ubuntu-rootfs")
    fun agentRootfs(ctx: Context) = rootfs(ctx)

    /** 宇宙 B：纯净编译器（JDK + Android SDK + Gradle 缓存），用于自我进化闭环编译 */
    fun compilerRootfs(ctx: Context) = File(aidevHome(ctx), "compiler_rootfs")

    /** 共享硬盘：OpenCode 与编译器零延迟共享同一份源码 */
    fun workspaceDir(ctx: Context) = File(aidevHome(ctx), "workspace")

    fun tasksDir(ctx: Context) = File(aidevHome(ctx), "tasks")
    fun prootLibDir(ctx: Context) = File(aidevHome(ctx), "proot-lib")
    fun devEnvBin(ctx: Context) = File(aidevHome(ctx), "dev-env/bin")

    fun backupDir(ctx: Context): File {
        val p = PreferencesManager(ctx).backupDir
        return if (p.isNotBlank()) File(p) else File("/sdcard/AIDev/backups/")
    }

    fun projectsDir(ctx: Context): File {
        val rel = PreferencesManager(ctx).projectsDirRel
        return if (rel.isNotBlank()) File(rootfs(ctx), rel) else File(rootfs(ctx), "root/projects")
    }

    fun externalAidevDir(ctx: Context): File {
        val p = PreferencesManager(ctx).externalAidevDir
        return if (p.isNotBlank()) File(p) else File("/sdcard/AIDev")
    }

    fun downloadDir(ctx: Context) = File(Environment.getExternalStorageDirectory(), "Download")
}
