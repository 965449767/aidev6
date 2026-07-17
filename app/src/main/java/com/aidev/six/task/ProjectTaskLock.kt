package com.aidev.six.task

import java.util.concurrent.ConcurrentHashMap

/**
 * 按项目粒度的单写锁：同一项目同时只允许一个「写/构建」任务运行。
 *
 * 终端与「服务器中心」面板两个前端共享同一套构建入口，会话本身不加锁；真正的高危场景是
 * 两个任务同时对同一项目改文件 / 触发构建，导致文件竞争或产物损坏。所有构建请求都
 * 经 [com.aidev.six.BuildBridgeService] 汇聚，故在此处按项目 key 互斥即可覆盖全部来源
 * （手动按钮 / 终端 `aidev-build-request` / 面板）。
 *
 * 纯内存实现：进程被杀后锁自然释放（BuildBridge onStart 会清理残留 .processing）。
 */
object ProjectTaskLock {
    data class Holder(val projectKey: String, val source: String, val since: Long)

    private val locks = ConcurrentHashMap<String, Holder>()

    /** 尝试为 [projectKey] 获取写锁。成功返回 true；已被占用返回 false。 */
    fun tryAcquire(projectKey: String, source: String): Boolean {
        val holder = Holder(projectKey, source, System.currentTimeMillis())
        return locks.putIfAbsent(projectKey, holder) == null
    }

    fun release(projectKey: String) {
        locks.remove(projectKey)
    }

    fun holder(projectKey: String): Holder? = locks[projectKey]

    fun isLocked(projectKey: String): Boolean = locks.containsKey(projectKey)
}
