package com.aidev.six

import java.util.concurrent.ConcurrentLinkedQueue

object TerminalCommandBus {
    private val queue = ConcurrentLinkedQueue<String>()
    private const val MAX_QUEUE_SIZE = 128

    fun consume(): String? = queue.poll()

    fun post(command: String) {
        if (queue.size >= MAX_QUEUE_SIZE) {
            queue.poll()
        }
        queue.offer(command)
    }
}
