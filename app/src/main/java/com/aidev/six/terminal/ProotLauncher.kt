package com.aidev.six.terminal

import android.content.Context
import com.aidev.six.ShellResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 统一 PRoot 调用构造与执行。
 *
 * 三处历史实现（UbuntuShell / ProjectManagerState.createProotProcess / aidev-ubuntu-core 的 shell 生成器）
 * 中，两个 Kotlin 调用点在此收敛；交互式终端会话仍经 shell 入口脚本复用相同参数。
 *
 * 单 rootfs 架构：所有操作统一在终端环境（[com.aidev.six.PathConfig.rootfs]）内执行，
 * 包括开发终端与编译构建共享同一环境。工作区通过 [ProotBind] 把 [com.aidev.six.PathConfig.workspaceDir]
 * 绑入内部 /workspace 实现源码共享。
 */
object ProotLauncher {

    data class ProotBind(val host: String, val guest: String? = null)

    data class Options(
        val rootfs: String,
        val cwd: String = "/root",
        val binds: List<ProotBind> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val timeoutSec: Long = 30,
        val redirectErrorStream: Boolean = false,
        /** 若非空，proot 以 `-q <qemuPath>` 通过 QEMU 运行异构(x86_64)程序，如 aapt2。传宿主机绝对路径。 */
        val qemuPath: String? = null
    )

    private const val COMMON_PATH =
        "PATH=/host-home/dev-env/bin:/system/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun buildCommand(ctx: Context, command: String, opts: Options): String {
        val nativeDir = com.aidev.six.PathConfig.nativeLibDir(ctx).absolutePath
        val proot = File(nativeDir, "libproot.so").absolutePath
        val aidevHome = File(ctx.filesDir, "home").absolutePath
        val sb = StringBuilder()
        sb.append("$proot --link2symlink -0 ")
        if (!opts.qemuPath.isNullOrBlank()) sb.append("-q ${opts.qemuPath} ")
        sb.append("-r ${opts.rootfs} ")
        sb.append("-b /dev -b /proc -b /sys -b /system/bin -b /system/etc ")
        sb.append("-b /system/framework -b /sdcard -b /storage ")
        for (b in opts.binds) {
            sb.append("-b ${b.host}")
            if (b.guest != null) sb.append(":${b.guest}")
            sb.append(" ")
        }
        sb.append("-b $aidevHome:/host-home -w ${opts.cwd} ")
        sb.append("/usr/bin/env -i HOME=/root ")
        sb.append("$COMMON_PATH ")
        sb.append("TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 ")
        for ((k, v) in opts.env) sb.append("$k=$v ")
        val escaped = command
            .replace("\\", "\\\\")
            .replace("\$", "\\\$")
            .replace("\"", "\\\"")
        sb.append("/bin/sh -c \"$escaped\"")
        return sb.toString()
    }

    private fun setupEnv(ctx: Context, pb: ProcessBuilder, opts: Options) {
        val nativeDir = com.aidev.six.PathConfig.nativeLibDir(ctx).absolutePath
        val extraLibDir = com.aidev.six.PathConfig.prootLibDir(ctx).absolutePath
        val prootTmpDir = File(ctx.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath
        val env = pb.environment()
        env["PROOT_LOADER"] = File(nativeDir, "libproot_loader.so").absolutePath
        env["PROOT_TMP_DIR"] = prootTmpDir
        env["LD_LIBRARY_PATH"] = "$extraLibDir:$nativeDir"
        if (opts.redirectErrorStream) pb.redirectErrorStream(true)
    }

    /** 捕获式执行：一次性读取 stdout/stderr 与退出码。
     *  @param processTracker 可选回调，进程启动后立即拿到 [Process] 引用（用于取消/强杀）。 */
    fun run(ctx: Context, command: String, opts: Options, processTracker: ((Process) -> Unit)? = null): ShellResult {
        val shellCmd = buildCommand(ctx, command, opts)
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
            setupEnv(ctx, pb, opts)
            val process = pb.start()
            processTracker?.invoke(process)
            try {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(opts.timeoutSec, TimeUnit.SECONDS)
                val exitCode = if (exited) process.exitValue() else { process.destroyForcibly(); -1 }
                ShellResult(stdout.trimEnd(), stderr.trimEnd(), exitCode)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    /** 流式执行：返回 Process 供调用方逐行读取（用于编译/SDK 下载等长时间任务）。 */
    fun start(ctx: Context, command: String, opts: Options): Process {
        val shellCmd = buildCommand(ctx, command, opts)
        val pb = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
        setupEnv(ctx, pb, opts)
        return pb.start()
    }
}
