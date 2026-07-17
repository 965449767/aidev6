package com.aidev.six

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aidev.six.monitor.OpenCodeEngine

class OpenCodeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        when (intent.action) {
            ACTION_ABORT -> {
                val sessionId = intent.getStringExtra("session_id") ?: run {
                    pendingResult.finish()
                    return
                }
                // 中止逻辑委托给 AIEngine 实现（OpenCodeEngine），不再直连硬编码 URL。
                OpenCodeEngine(context).abortSession(sessionId)
                pendingResult.finish()
            }
            else -> pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_ABORT = "com.aidev.six.OC_ABORT"
    }
}
