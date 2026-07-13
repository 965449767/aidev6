package com.aidev.six.agent

import android.content.Context
import com.aidev.six.CrashReportBridgeService
import com.aidev.six.DeployBridgeService
import com.aidev.six.PathConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 「服务器中心」面板「安装 / 拉起」按钮的提交器（F02 同类）。
 *
 * 只负责把部署请求写到 home/.aidev-deploy-bridge/req-<id>.json 并等待结果，
 * 真正的部署执行与面板状态回流全部由 [DeployBridgeService]（单一真源）完成——
 * 与 BuildRequestTracker 调 BuildBridgeService 保持完全一致的服务一致性。
 */
internal class DeployRequestTracker {

    private val executor = Executors.newCachedThreadPool()

    fun submit(
        context: Context,
        id: String,
        apk: String,
        pkg: String,
        launch: Boolean,
        onDone: () -> Unit,
    ) {
        val appCtx = context.applicationContext
        runCatching {
            DeployBridgeService.start(appCtx, PathConfig.aidevHome(appCtx))
            CrashReportBridgeService.start(appCtx, PathConfig.aidevHome(appCtx))
        }

        val bridgeDir = File(PathConfig.aidevHome(appCtx), ".aidev-deploy-bridge").apply { mkdirs() }
        val payload = JSONObject().apply {
            put("id", id)
            put("apk", apk)
            put("pkg", pkg)
            put("launch", launch)
        }
        val writeOk = runCatching { File(bridgeDir, "req-$id.json").writeText(payload.toString()) }.isSuccess
        if (!writeOk) { onDone(); return }

        executor.execute {
            val resultFile = File(bridgeDir, "result-$id.json")
            val deadline = System.currentTimeMillis() + 6 * 60 * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (resultFile.isFile) { onDone(); return@execute }
                Thread.sleep(800)
            }
            onDone()
        }
    }
}
