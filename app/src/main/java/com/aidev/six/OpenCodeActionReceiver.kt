package com.aidev.six

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

class OpenCodeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        when (intent.action) {
            ACTION_ABORT -> {
                val sessionId = intent.getStringExtra("session_id") ?: run {
                    pendingResult.finish()
                    return
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch {
                    runCatching {
                        val url = URL("${Constants.OPENCODE_BASE_URL}/session/${Uri.encode(sessionId)}/abort")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.doOutput = true
                        conn.outputStream.use { os ->
                            os.write("{}".toByteArray())
                            os.flush()
                        }
                        val code = conn.responseCode
                        conn.disconnect()
                        AIDevLogger.d("OCReceiver", "abort $sessionId -> $code")
                    }.onFailure {
                        AIDevLogger.w("OCReceiver", "abort failed: ${it.message}")
                    }
                    scope.cancel()
                    pendingResult.finish()
                }
            }
            else -> pendingResult.finish()
        }
    }

    companion object {
        private const val ACTION_ABORT = "com.aidev.six.OC_ABORT"
    }
}
