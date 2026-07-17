package com.aidev.six

import android.content.Context
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.Thread.UncaughtExceptionHandler

/**
 * 宿主全局未捕获异常守卫（P0-1）。
 *
 * 捕获 aidev6 宿主自身的任意线程未捕获异常，写一个本地 crash 请求文件到
 * `${aidevHome}/.aidev-crash-bridge/`，供人类在终端用 aidev-crash-why 等命令排查；
 * 随后立即委托原 handler 终止进程，保留系统原生崩溃行为（不自吞、不阻塞）。
 *
 * 本类只做极轻量的本地文件写入（几 KB），绝不在崩溃路径上做网络/Shizuku/重 IO，
 * 不触发任何自动修复或 AI 闭环。
 */
object CrashGuard {
    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashGuardHandler(old, context.applicationContext))
    }

    /**
     * 把宿主崩溃写成本地 crash 请求文件（package=宿主自身），供人类排查。
     * 返回是否写入成功；失败不影响崩溃流程（仍会委托原 handler 终止）。
     */
    internal fun writeCrashRequest(home: File, packageName: String): Boolean {
        val dir = File(home, ".aidev-crash-bridge").apply { mkdirs() }
        val payload = JSONObject().apply {
            put("package", packageName)
            put("lines", 2000)
        }
        return runCatching {
            File(dir, "req-${System.currentTimeMillis()}.json").writeText(payload.toString())
        }.isSuccess
    }

    private class CrashGuardHandler(
        private val delegate: UncaughtExceptionHandler?,
        private val appCtx: Context,
    ) : UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, ex: Throwable) {
            runCatching {
                writeCrashRequest(PathConfig.aidevHome(appCtx), appCtx.packageName)
            }.onFailure { Log.e("CrashGuard", "写入崩溃请求失败", it) }
            delegate?.uncaughtException(thread, ex)
                ?: run { Process.killProcess(Process.myPid()); System.exit(1) }
        }
    }
}
