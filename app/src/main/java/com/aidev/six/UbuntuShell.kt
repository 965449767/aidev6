package com.aidev.six

import android.content.Context
import com.aidev.six.terminal.ProotLauncher

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
        val rootfs = PathConfig.agentRootfs(c).absolutePath
        return ProotLauncher.run(c, command, ProotLauncher.Options(rootfs = rootfs))
    }
}
