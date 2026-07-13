package com.aidev.six.chat

import android.content.Context
import com.aidev.six.PathConfig
import com.aidev.six.agent.AgentTaskStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "BuildProgressFeed"

/** 构建进度在聊天界面展示用的本地投影（屏蔽 agent 包的 internal 类型）。 */
data class BuildProgressInfo(
    val name: String,
    val status: String,        // RUNNING / PENDING / SUCCEEDED / FAILED / CANCELLED
    val exitCode: Int,
    val log: String,
    val lastUpdatedAt: Long,
) {
    val isActive: Boolean get() = status == "RUNNING" || status == "PENDING"
    val isRecent: Boolean get() = System.currentTimeMillis() - lastUpdatedAt < 5 * 60 * 1000
    fun tailLines(n: Int = 14): String {
        val lines = log.lines().filter { it.isNotBlank() }
        return if (lines.size <= n) log else lines.takeLast(n).joinToString("\n")
    }
}

/**
 * 把 BuildBridgeService 写入的构建进度（agent-tasks.json）投射到聊天界面。
 *
 * BuildBridge 无论由手动按钮还是 AI 对话触发，都会把同一份进度发布到该文件；
 * 这里按"当前项目"关联最近一次构建任务，供 ChatPanel 实时展示"准备→编译→安装→拉起"
 * 全过程与最终结果，弥补对话里看不到构建过程的断层。
 */
object BuildProgressFeed {

    private fun stateFile(ctx: Context) = File(PathConfig.tasksDir(ctx), "agent-tasks.json")
    private fun bridgeDir(ctx: Context) = File(PathConfig.aidevHome(ctx), ".aidev-build-bridge")

    private fun map(status: String, name: String, exitCode: Int, log: String, last: Long): BuildProgressInfo =
        BuildProgressInfo(name, status, exitCode, log, last)

    /**
     * 读取最近一次构建任务（不限项目：自我进化构建对当前用户总是相关）。
     * 优先活动态，其次按最近更新时间返回（陈旧与否由调用方 isRecent 判定）。
     */
    suspend fun latest(ctx: Context): BuildProgressInfo? = withContext(Dispatchers.IO) {
        val sf = stateFile(ctx)
        val tasks = runCatching { AgentTaskStore.loadState(sf) }.onFailure {
            android.util.Log.w(TAG, "loadState failed: $sf", it)
        }.getOrDefault(emptyList())
        val matched = tasks.filter { it.definition.tags.contains("build") || it.definition.name.contains("构建") }
        if (tasks.isNotEmpty() && matched.isEmpty()) {
            android.util.Log.d(TAG, "agent-tasks.json 有 ${tasks.size} 条记录，但无 build 标签任务：${tasks.map { it.definition.tags }}")
        }
        matched.maxByOrNull { it.lastUpdatedAt }
            ?.let { map(it.status.name, it.definition.name, it.exitCode, it.log, it.lastUpdatedAt) }
    }

    /** 是否存在已提交但尚未被 BuildBridge 认领的构建请求（req-*.json，排除 .processing）。 */
    suspend fun hasPendingRequest(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val dir = bridgeDir(ctx)
        if (!dir.isDirectory) {
            android.util.Log.d(TAG, "bridgeDir 不存在: $dir")
            return@withContext false
        }
        val found = dir.listFiles { _, n ->
            n.startsWith("req-") && n.endsWith(".json") && !n.endsWith(".processing")
        }?.isNotEmpty() ?: false
        if (found) android.util.Log.d(TAG, "发现待认领构建请求于 $dir")
        found
    }

    /**
     * 轮询循环：每 [intervalMs] 拉取一次。
     * 优先返回活动/最近构建任务；若无任务但存在待认领请求，则合成一条 PENDING 卡片，
     * 让"发出请求→立即有反馈"成立，消除等待宇宙 B 调度期间的静默断层。
     */
    suspend fun poll(
        ctx: Context,
        intervalMs: Long = 1500,
        onUpdate: (BuildProgressInfo?) -> Unit,
    ) {
        while (true) {
            val task = latest(ctx)
            val pending = task == null && hasPendingRequest(ctx)
            onUpdate(
                task ?: if (pending) {
                    BuildProgressInfo(
                        name = "构建请求已提交",
                        status = "PENDING",
                        exitCode = -1,
                        log = "已提交构建请求，等待宇宙 B 调度…",
                        lastUpdatedAt = System.currentTimeMillis(),
                    )
                } else {
                    null
                }
            )
            delay(intervalMs)
        }
    }
}
