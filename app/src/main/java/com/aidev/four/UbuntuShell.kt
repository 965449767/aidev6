package com.aidev.four

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

object UbuntuShell {

    fun execute(context: Context, command: String, workDir: String? = null): ShellResult {
        val cd = if (workDir != null) "cd \"$workDir\" 2>/dev/null && " else ""
        val fullCmd = "${cd}$command"
        return runInProot(context, fullCmd)
    }

    fun executeGit(context: Context, repoDir: String, gitArgs: String): ShellResult {
        return execute(context, gitArgs, workDir = repoDir)
    }

    private fun runInProot(c: Context, command: String): ShellResult {
        val nativeDir = c.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so").absolutePath
        val prootLoader = File(nativeDir, "libproot_loader.so").absolutePath
        val rootfs = File(c.filesDir, "home/ubuntu-rootfs").absolutePath
        val aidevHome = File(c.filesDir, "home").absolutePath
        val prootLibDir = File(c.filesDir, "home/proot-lib").absolutePath
        val prootTmpDir = File(c.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath

        val shellCmd = buildString {
            append("$proot --link2symlink -0 -r $rootfs ")
            append("-b /dev -b /proc -b /sys -b /system/bin -b /system/etc ")
            append("-b /system/framework -b /sdcard -b /storage ")
            append("-b $aidevHome:/host-home -w /root ")
            append("/usr/bin/env -i HOME=/root ")
            append("PATH=/host-home/dev-env/bin:/system/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            append("TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8 ")
            append("/bin/sh -c \"${command.replace("\"", "\\\"")}\"")
        }

        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
            val env = pb.environment()
            env["PROOT_LOADER"] = prootLoader
            env["PROOT_TMP_DIR"] = prootTmpDir
            env["LD_LIBRARY_PATH"] = "$prootLibDir:$nativeDir"
            val process = pb.start()
            try {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(30, TimeUnit.SECONDS)
                val exitCode = if (exited) process.exitValue() else { process.destroyForcibly(); -1 }
                ShellResult(stdout.trimEnd(), stderr.trimEnd(), exitCode)
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }
}
