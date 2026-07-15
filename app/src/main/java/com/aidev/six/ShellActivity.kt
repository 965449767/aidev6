package com.aidev.six

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.aidev.six.navigation.AppNavHost
import com.aidev.six.navigation.GlobalBackPressHandler
import com.aidev.six.navigation.DialogHost
import com.aidev.six.navigation.LocalDialogManager
import com.aidev.six.navigation.LocalImeBottomPx
import com.aidev.six.navigation.rememberDialogManagerState
import com.aidev.six.ui.theme.AIDevTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
class ShellActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    val terminalPage = EmbeddedTerminalPage()
    private val _currentTab = mutableIntStateOf(TAB_TERMINAL)
    private val _refreshKey = mutableIntStateOf(0)
    private val _imeBottomPx = mutableIntStateOf(0)

    private val bgImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            prefs.edit().putString("bg_image_uri", uri.toString()).apply()
            prefs.edit().putString("bg_mode", "image").apply()
            refreshShellSkin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        prefs = getSharedPreferences("aidev_ui", MODE_PRIVATE)
        requestEssentialPermissions()
        ensureNotificationChannel()
        if (prefs.getBoolean(Constants.PrefKeys.KEEPALIVE_AUTO, true)) runCatching { KeepAliveService.start(this) }
            .onFailure { Log.e("ShellActivity", "KeepAliveService start failed", it) }

        val savedTab = savedInstanceState?.getInt("last_tab", -1) ?: -1
        val intentTab = intent?.getIntExtra("shell_tab", -1) ?: -1
        val prefsTab = prefs.getInt(Constants.PrefKeys.LAST_TAB, -1)
        val initial = when {
            savedTab in TAB_TERMINAL..TAB_SETTINGS -> savedTab
            intentTab in TAB_TERMINAL..TAB_SETTINGS -> intentTab
            prefsTab in TAB_TERMINAL..TAB_SETTINGS -> prefsTab
            else -> TAB_TERMINAL
        }

        terminalPage.init(this)

        _currentTab.intValue = initial

        setContent {
            val currentTab = _currentTab.intValue
            AIDevTheme(prefs) {
                GlobalBackPressHandler(
                    activity = this@ShellActivity,
                )
                val dialogManager = rememberDialogManagerState()
                CompositionLocalProvider(
                    LocalDialogManager provides dialogManager,
                    LocalImeBottomPx provides _imeBottomPx.intValue,
                ) {
                    DialogHost(
                        onExecuteCommand = { command ->
                            TerminalCommandBus.post(command)
                            switchTo(TAB_TERMINAL)
                        },
                    ) {
                        AppNavHost(
                            currentTab = currentTab,
                            onTabSelected = { tab -> switchTo(tab) },
                            onExecuteCommand = { command ->
                                TerminalCommandBus.post(command)
                                switchTo(TAB_TERMINAL)
                            },
                            terminalPage = terminalPage,
                        )
                    }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(android.R.id.content)
        ) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            _imeBottomPx.intValue = (imeBottom - sysBottom).coerceAtLeast(0)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra("shell_tab", -1)
        if (tab in TAB_TERMINAL..TAB_SETTINGS) {
            switchTo(tab)
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putInt(Constants.PrefKeys.LAST_TAB, _currentTab.intValue).apply()
    }

    fun refreshShellSkin() {
        _refreshKey.intValue++
    }

    override fun onDestroy() {
        terminalPage.onDestroy(this)
        super.onDestroy()
    }

    fun pickBackgroundImage() {
        bgImagePicker.launch(arrayOf("image/*"))
    }

    fun switchTo(index: Int) {
        if (index in TAB_TERMINAL..TAB_SETTINGS) {
            _currentTab.intValue = index
        }
    }

    fun syncTerminalCd(ubuntuPath: String) {
        terminalPage.silentCd(ubuntuPath)
    }

    companion object {
        private const val REQ_NOTIFICATION = 4302
        const val TAB_TERMINAL = 0
        const val TAB_SETTINGS = 1

        fun open(activity: Activity, tab: Int) {
            val intent = Intent(activity, ShellActivity::class.java)
                .putExtra("shell_tab", tab)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(intent)
            if (Build.VERSION.SDK_INT >= 34) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_left, R.anim.slide_out_right)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        }
    }

    private fun requestEssentialPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (!android.provider.Settings.System.canWrite(this)) {
                if (!prefs.getBoolean("write_settings_prompted", false)) {
                    prefs.edit().putBoolean("write_settings_prompted", true).apply()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("需要修改系统设置权限")
                        .setMessage("亮度调节等功能需要\"修改系统设置\"权限。请在接下来的系统设置中开启此权限。")
                        .setPositiveButton("去开启") { _, _ ->
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = android.net.Uri.parse("package:$packageName")
                            })
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                "aidev_terminal",
                "AIDev Terminal",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "AIDev Terminal 系统通知"
            }
            nm.createNotificationChannel(channel)
        }
    }
}
