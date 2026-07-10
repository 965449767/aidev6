package com.aidev.six.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val BACK_PRESS_TIMEOUT_MS = 2000L

@Composable
fun GlobalBackPressHandler(
    activity: Activity,
) {
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < BACK_PRESS_TIMEOUT_MS) {
            activity.finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(activity, "再按一次返回到桌面", Toast.LENGTH_SHORT).show()
        }
    }
}
