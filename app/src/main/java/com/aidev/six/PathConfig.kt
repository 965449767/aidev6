package com.aidev.six

import android.content.Context
import android.os.Environment
import java.io.File

object PathConfig {

    fun aidevHome(ctx: Context) = File(ctx.filesDir, "home")

    /** 终端开发环境（ubuntu-rootfs），所有操作（包括编译）均在此环境内执行 */
    fun rootfs(ctx: Context) = File(aidevHome(ctx), "ubuntu-rootfs")
    fun agentRootfs(ctx: Context) = rootfs(ctx)

    /**
     * 编译器环境（已统一到终端环境，不再区分独立宇宙）。
     * 保留此函数名以保持向后兼容，实际返回终端 rootfs。
     */
    fun compilerRootfs(ctx: Context) = rootfs(ctx)

    /** 共享工作区：终端与编译器零延迟共享同一份源码 */
    fun workspaceDir(ctx: Context) = File(aidevHome(ctx), "workspace")

    fun tasksDir(ctx: Context) = File(aidevHome(ctx), "tasks")

    /** 统一日志目录：所有构建/崩溃/桥接服务的日志集中存放（外部存储，方便调试分享） */
    fun logsDir(ctx: Context) = File("/sdcard/AIDev/logs").apply { mkdirs() }

    /**
     * PRoot 可执行体（libproot.so / libproot_loader.so）+ 依赖 .so（libtalloc.so /
     * libandroid-shmem.so）的真实所在目录。
     *
     * 这是 App 唯一可靠的可执行区：APK 内 `lib/<abi>/lib*.so` 由系统在安装时解包到
     * `nativeLibraryDir`（label = apk_data_file，挂载 exec，SELinux 允许 App exec）。
     * filesDir / cacheDir / code_cache 在本机 HyperOS/Android 16 上均被 W^X 拒绝 exec。
     */
    fun nativeLibDir(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir)

    /**
     * proot 依赖的版本化 soname（libtalloc.so.2）无法作为 `lib*.so` 被系统解包，
     * 故在此可写目录放一个符号链接 libtalloc.so.2 -> [nativeLibDir]/libtalloc.so，
     * 并把本目录加入 LD_LIBRARY_PATH。符号链接文件本身无可执行内容，链接器最终 mmap 的是
     * nativeLibDir 里的真实文件（exec 允许）。
     */
    fun prootLibDir(ctx: Context) = File(aidevHome(ctx), "proot-lib")
    fun devEnvBin(ctx: Context) = File(aidevHome(ctx), "dev-env/bin")

    fun backupDir(ctx: Context): File {
        val p = PreferencesManager(ctx).backupDir
        return if (p.isNotBlank()) File(p) else File("/sdcard/AIDev/backups/")
    }

    fun externalAidevDir(ctx: Context): File {
        val p = PreferencesManager(ctx).externalAidevDir
        return if (p.isNotBlank()) File(p) else File("/sdcard/AIDev")
    }

    fun downloadDir(ctx: Context) = File(Environment.getExternalStorageDirectory(), "Download")
}
