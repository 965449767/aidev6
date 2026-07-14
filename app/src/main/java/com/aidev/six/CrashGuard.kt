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
 * 让「崩溃回流 → 自我进化闭环」也能覆盖 aidev6 宿主自身：捕获任意线程未捕获异常后，
 * 写一个 crash 请求到 CrashReportBridge 入队目录（与 aidev-crash-report 同格式），
 * 随后立即委托原 handler 终止进程，保留系统原生崩溃行为（不自吞、不阻塞）。
 *
 * 真正的 logcat 抓取与 crash-*.json 报告生成由 CrashReportBridgeService 异步完成，
 * 本类只做极轻量的文件写入（几 KB），绝不在崩溃路径上做网络/Shizuku/重 IO。
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
     * 把宿主崩溃写成 CrashReportBridge 的 req 文件（package=宿主自身，让闭环也覆盖宿主）。
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
