package com.aidev.six.bridge

import java.util.concurrent.ConcurrentHashMap

/**
 * 桥接注册表：按桥名登记各 [BridgeService] 单例，供 Socket 服务在收到帧时即时分发。
 * 各桥在 [BridgeService.start] 时登记、[BridgeService.stop] 时注销。
 */
object BridgeRegistry {
    private val map = ConcurrentHashMap<String, BridgeService>()

    fun register(b: BridgeService) {
        if (b.bridgeName.isNotBlank()) map[b.bridgeName] = b
    }

    fun unregister(b: BridgeService) {
        if (map[b.bridgeName] === b) map.remove(b.bridgeName)
    }

    fun dispatch(frame: BridgeFrame): BridgeFrame? =
        runCatching { map[frame.bridge]?.dispatch(frame) }.getOrNull()

    fun names(): Set<String> = map.keys.toSet()
}
