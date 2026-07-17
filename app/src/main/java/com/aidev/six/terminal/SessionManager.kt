package com.aidev.six.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.FileObserver
import android.util.Log
import android.view.View
import android.widget.Toast
import com.aidev.six.ClipboardHelper
import com.aidev.six.Constants
import com.aidev.six.NotifyBridgeService
import com.aidev.six.BuildBridgeService
import com.aidev.six.DeployBridgeService
import com.aidev.six.ShizukuBridgeService
import com.aidev.six.ShizukuLogcat
import com.aidev.six.TerminalCommandBus
import com.aidev.six.TerminalImeProxyEditText
import com.aidev.six.TerminalShellAssets
import com.aidev.six.TerminalShellAssetPaths
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import com.aidev.six.terminal.TerminalRenderScheduler
import com.aidev.six.terminal.TerminalInputBuffer

class SessionManager(
    private var activity: Activity? = null,
    private var terminalView: TerminalView? = null,
    private var inputProxy: TerminalImeProxyEditText? = null,
    private val completionEngine: CompletionEngine? = null,
    private val keyboardManager: VirtualKeyboardManager? = null
) {
    companion object {
        private const val TAG = "SessionManager"
        @Volatile private var shellAssetsDeployed = false
        private const val PREFS_KEY_COUNT = "terminal_session_count"
        private const val PREFS_KEY_SESSION = "terminal_session_"
        private const val MAX_SESSIONS = 8
    }

    var homeDir: File? = null
        internal set
    var scope: CoroutineScope? = null
    var cachedCompletionPwd: String
        get() = completionEngine?.cachedCompletionPwd ?: ""
        set(value) { completionEngine?.cachedCompletionPwd = value }
    private var pwdObserverJob: Job? = null
    private var pwdFileObserver: FileObserver? = null

    private val _sessions = mutableStateListOf<EmbeddedTermSession>()
    val sessions: List<EmbeddedTermSession> get() = _sessions

    private val _sessionColors = mutableMapOf<Int, String>()

    fun getSessionColor(id: Int): String = _sessionColors[id] ?: ""

    fun setSessionColor(id: Int, color: String) {
        _sessionColors[id] = color
        persistSessions()
    }

    private val _currentIndex = mutableIntStateOf(-1)
    val currentIndex: Int get() = _currentIndex.intValue

    val currentSession: EmbeddedTermSession? get() = sessions.getOrNull(currentIndex)
    val currentTerminalSession: TerminalSession? get() = currentSession?.session

    private val pendingRunnables = mutableListOf<Pair<View, Runnable>>()
    private var consumeLoopJob: kotlinx.coroutines.Job? = null
    private var finishSessionJob: kotlinx.coroutines.Job? = null

    /** Coalesces PTY-driven screen updates to the display refresh cadence. */
    var renderScheduler: TerminalRenderScheduler? = null
    fun updateRenderScheduler(s: TerminalRenderScheduler?) { renderScheduler = s }

    /** Batches IME/key input into the PTY; flushed before any programmatic write. */
    var inputBuffer: TerminalInputBuffer? = null
    fun updateInputBuffer(b: TerminalInputBuffer?) { inputBuffer = b }
    fun flushInput() = inputBuffer?.flushNow()

    fun updateActivity(act: Activity?) { activity = act }
    fun updateTerminalView(v: TerminalView?) { terminalView = v }
    fun updateInputProxy(p: TerminalImeProxyEditText?) { inputProxy = p }

    fun startShizukuBridge() {
        val act = activity ?: return
        val home = homeDir ?: return
        NotifyBridgeService.start(act, home)
        BuildBridgeService.start(act, home)
        DeployBridgeService.start(act, home)
        if (ShizukuLogcat.isAvailable()) {
            ShizukuBridgeService.start(act, home)
        }
    }

    fun ensureSession(onReady: () -> Unit = {}) {
        val s = scope ?: return
        val act = activity ?: return
        val savedTheme = currentTheme()
        Log.d(TAG, "ensureSession: start, shellAssetsDeployed=$shellAssetsDeployed")
        s.launch(Dispatchers.IO) {
            val shellAssets: TerminalShellAssetPaths
            if (shellAssetsDeployed) {
                val home = TerminalShellAssetPaths(File(act.filesDir, "home"), File(act.filesDir, "home/.aidev_shell_entry"))
                shellAssets = home
            } else {
                val t0 = System.currentTimeMillis()
                val result = runCatching { TerminalShellAssets.ensure(act) }
                Log.d(TAG, "ensureSession: TerminalShellAssets.ensure() took ${System.currentTimeMillis() - t0}ms")
                shellAssets = result.getOrElse {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(act, "终端环境初始化失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                shellAssetsDeployed = true
            }
            withContext(Dispatchers.Main) {
                Log.d(TAG, "ensureSession: onMain start, sessions.isEmpty=${_sessions.isEmpty()}")
                if (this@SessionManager.activity != act) return@withContext
                homeDir = shellAssets.home
                if (_sessions.isNotEmpty()) {
                    attachCurrentSession()
                    homeDir?.let { completionEngine?.updateHomeDir(it) }
                    startShizukuBridge()
                    return@withContext
                }
                val restored = restoreSessions(savedTheme)
                if (!restored) {
                    runCatching { doCreateSession() }.onFailure { e ->
                        Toast.makeText(act, "会话创建失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                if (restored && savedTheme != "classic-dark") applyThemeToAll(savedTheme)
                onReady()
                startConsumeLoop()
            }
        }
    }

    private fun createTerminalSession(id: Int): TerminalSession {
        val home = homeDir ?: throw IllegalStateException("homeDir is null")
        val rc = File(home, ".aidevrc")
        val entry = File(home, ".aidev_shell_entry")
        val nativeDir = activity?.applicationInfo?.nativeLibraryDir ?: ""
        val aidevBin = File(home, "dev-env/bin").absolutePath
        val extraLibDir = activity?.let { com.aidev.six.PathConfig.prootLibDir(it).absolutePath } ?: ""
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor",
            "HOME=${home.absolutePath}", "PWD=${home.absolutePath}",
            "TMPDIR=${File(activity?.cacheDir, "tmp").apply { mkdirs() }.absolutePath}",
            "AIDEV_HOME=${home.absolutePath}", "AIDEV_BIN=$aidevBin",
            "AIDEV_NATIVE=$nativeDir", "AIDEV_PROOT=$nativeDir/libproot.so",
            "AIDEV_PROOT_LOADER=$nativeDir/libproot_loader.so",
            "PROOT_LOADER=$nativeDir/libproot_loader.so",
            "PROOT_TMP_DIR=${File(activity?.cacheDir, "proot_tmp").apply { mkdirs() }.absolutePath}",
            "LD_LIBRARY_PATH=$extraLibDir:$nativeDir",
            "ENV=${rc.absolutePath}", "PATH=$aidevBin:/system/bin:/system/xbin"
        )
        return TerminalSession("/system/bin/sh", homeDir?.absolutePath ?: "/data/data/com.aidev.six/files/home",
            arrayOf("sh", entry.absolutePath), env, 5000, null).apply {
            mSessionName = "AIDev-Shell-$id"
        }
    }

    fun addSession(aiSession: Boolean = false) {
        val act = activity ?: return
        if (_sessions.size >= MAX_SESSIONS) {
            val oldest = _sessions.firstOrNull()
            val title = if (aiSession) "AI-会话" else "会话"
            MaterialAlertDialogBuilder(act)
                .setTitle("会话上限")
                .setMessage("已达到最大会话数（8 个）。关闭最早的「${oldest?.title ?: title}」后创建新会话吗？")
                .setPositiveButton("确认") { _, _ ->
                    oldest?.let { closeSession(it.id) }
                    if (_sessions.size < MAX_SESSIONS) {
                        doCreateSession(aiSession = aiSession)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        doCreateSession(aiSession = aiSession)
    }

    private fun doCreateSession(title: String? = null, aiSession: Boolean = false) {
        val act = activity ?: return
        homeDir = File(act.filesDir, "home").apply { mkdirs() }
        val usedIds = _sessions.map { it.id }.toSet()
        var id = 1
        while (id in usedIds) { id++ }
        val displayTitle = title ?: if (aiSession) "AI-会话$id" else "会话$id"
        val ts = createTerminalSession(id)
        val item = EmbeddedTermSession(id, displayTitle, ts, aiSession = aiSession)
        _sessions.add(item)
        switchSession(item.id)
        applyThemeToAll(currentTheme())
        persistSessions()
    }

    fun closeSession(id: Int) {
        val idx = _sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (_sessions[idx].locked) return

        val sessionToFinish = _sessions[idx].session
        finishSessionJob?.cancel()
        finishSessionJob = scope?.launch(Dispatchers.IO) { sessionToFinish.finishIfRunning() }
        _sessions.removeAt(idx)

        if (_sessions.isEmpty()) {
            _currentIndex.intValue = -1
            doCreateSession()
        } else {
            if (idx < _currentIndex.intValue) {
                _currentIndex.intValue -= 1
            } else if (_currentIndex.intValue >= _sessions.size) {
                _currentIndex.intValue = _sessions.size - 1
            }
            attachCurrentSession()
        }
        persistSessions()
    }

    fun switchSession(id: Int) {
        val idx = _sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        _currentIndex.intValue = idx
        attachCurrentSession()
    }

    fun toggleLock(id: Int) {
        val idx = _sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        _sessions[idx] = _sessions[idx].copy(locked = !_sessions[idx].locked)
        persistSessions()
    }

    fun cloneSession() {
        val act = activity ?: return
        val pwd = cachedCompletionPwd.ifEmpty {
            homeDir?.let { h -> File(h, ".aidev-current-pwd").takeIf { it.isFile }?.readText()?.trim() } ?: ""
        }
        val doCreateThenSendCd: () -> Unit = {
            doCreateSession()
            if (pwd.isNotEmpty()) {
                val ts = currentTerminalSession
                if (ts != null) {
                    scope?.launch {
                        delay(1000)
                        inputBuffer?.flushNow()
                        ts.write("cd $pwd\r")
                    }
                }
            }
        }
        if (_sessions.size >= MAX_SESSIONS) {
            val oldest = _sessions.firstOrNull()
            MaterialAlertDialogBuilder(act)
                .setTitle("会话已达上限")
                .setMessage("已达到最大会话数（8 个）。关闭最早的「${oldest?.title ?: ""}」后创建新会话吗？")
                .setPositiveButton("确定") { _, _ ->
                    if (oldest != null) closeSession(oldest.id)
                    if (_sessions.size < MAX_SESSIONS) {
                        doCreateThenSendCd()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doCreateThenSendCd()
        }
    }

    private fun attachCurrentSession() {
        val item = currentSession ?: return
        item.session.updateTerminalSessionClient(createTerminalSessionClient())
        terminalView?.attachSession(item.session)
        terminalView?.requestFocus()
        renderScheduler?.flushNow() ?: terminalView?.onScreenUpdated()
        completionEngine?.updateSession(item.session)
        keyboardManager?.updateSession(item.session)
    }

    private fun createTerminalSessionClient(): TerminalSessionClient {
        val tv = terminalView
        return object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                renderScheduler?.scheduleUpdate() ?: tv?.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                renderScheduler?.scheduleUpdate() ?: tv?.onScreenUpdated()
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val act = activity ?: return
                val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AIDev Terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession) {
                val act = activity ?: return
                val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
                if (!clip.isNullOrEmpty()) {
                    inputBuffer?.flushNow()
                    session.write(clip)
                }
            }
            override fun onBell(session: TerminalSession) {
                tv?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            override fun onColorsChanged(session: TerminalSession) {
                renderScheduler?.scheduleInvalidate() ?: tv?.invalidate()
            }
            override fun onTerminalCursorStateChange(state: Boolean) {
                renderScheduler?.scheduleInvalidate() ?: tv?.invalidate()
            }
            override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
            override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
            override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
            override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
            override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
            override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
            override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "Terminal exception", e) }
        }
    }

    fun renameCurrentSession() {
        val item = currentSession ?: return
        val act = activity ?: return
        val edit = android.widget.EditText(act).apply {
            setText(item.title)
            selectAll()
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("重命名会话")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    val idx = _currentIndex.intValue
                    if (idx >= 0 && idx < _sessions.size) {
                        _sessions[idx] = _sessions[idx].copy(title = name.take(24))
                        persistSessions()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun send(command: String) {
        val ts = currentTerminalSession ?: return
        inputBuffer?.flushNow()
        ts.write("$command\r")
        inputProxy?.let { proxy ->
            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.restartInput(proxy)
        }
    }

    fun silentCd(ubuntuPath: String) {
        inputBuffer?.flushNow()
        currentTerminalSession?.write("cd $ubuntuPath\r")
    }

    fun prefillCdCommand(ubuntuPath: String) {
        val ts = currentTerminalSession ?: return
        inputBuffer?.flushNow()
        val cdCmd = "cd $ubuntuPath"
        completionEngine?.inputBuffer = ""
        completionEngine?.composingBuffer = ""
        ts.write("\u0015$cdCmd")
        completionEngine?.inputBuffer = cdCmd
        completionEngine?.refreshCompletions()
        completionEngine?.focusTerminalInput()
    }

    fun focusTerminalInput() {
        completionEngine?.focusTerminalInput()
    }

    fun initPwdObserver() {
        pwdObserverJob?.cancel()
        pwdObserverJob = null
        pwdFileObserver?.stopWatching()
        val home = homeDir ?: return
        val pwdFile = File(home, ".aidev-current-pwd")
        if (pwdFile.isFile) {
            try {
                val content = pwdFile.readText().trim()
                if (content.isNotBlank()) cachedCompletionPwd = content
            } catch (e: Exception) {
                Log.w("SessionManager", "read pwd failed", e)
            }
        }
        @Suppress("DEPRECATION")
        pwdFileObserver = object : FileObserver(pwdFile.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (pwdFile.isFile) {
                    try {
                        val content = pwdFile.readText().trim()
                        if (content.isNotBlank()) cachedCompletionPwd = content
                    } catch (e: Exception) {
                        Log.w("SessionManager", "FileObserver read pwd failed", e)
                    }
                }
            }
        }.apply { startWatching() }
    }

    fun consumePendingCommand() {
        val command = TerminalCommandBus.consume() ?: return
        send(command)
    }

    fun startConsumeLoop() {
        consumeLoopJob?.cancel()
        consumeLoopJob = scope?.launch {
            while (isActive) {
                consumePendingCommand()
                delay(500)
            }
        }
    }

    fun applyThemeToAll(themeName: String) {
        val overrideBg = activity?.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            ?.getInt(Constants.PrefKeys.TERMINAL_BG_OVERRIDE, -1) ?: -1
        for (item in sessions) {
            TerminalThemeManager.applyByName(item.session, themeName)
            if (overrideBg != -1) {
                TerminalThemeManager.applyBackgroundOverride(item.session, overrideBg)
            }
        }
    }

    private fun currentTheme(): String {
        return activity?.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            ?.getString(Constants.PrefKeys.TERMINAL_THEME, "classic-dark") ?: "classic-dark"
    }

    fun trackPostDelayed(view: View, delayMs: Long, action: () -> Unit) {
        val r = Runnable { action() }
        pendingRunnables.add(view to r)
        view.postDelayed(r, delayMs)
    }

    fun cleanup() {
        pwdObserverJob?.cancel()
        pwdObserverJob = null
        pwdFileObserver?.stopWatching()
        pwdFileObserver = null
        consumeLoopJob?.cancel()
        consumeLoopJob = null
        finishSessionJob?.cancel()
        finishSessionJob = null
        scope?.cancel()
        scope = null
        ShizukuLogcat.cancelScope()
        ShizukuBridgeService.stop()
        NotifyBridgeService.stop()
        for ((view, runnable) in pendingRunnables) {
            view.removeCallbacks(runnable)
        }
        pendingRunnables.clear()
    }

    private fun persistSessions() {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE).edit()
        val oldCount = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE).getInt(PREFS_KEY_COUNT, 0)
        for (i in _sessions.size until oldCount) {
            val key = "${PREFS_KEY_SESSION}${i}"
            prefs.remove("${key}_title")
            prefs.remove("${key}_id")
            prefs.remove("${key}_ai")
            prefs.remove("${key}_locked")
        }
        prefs.putInt(PREFS_KEY_COUNT, _sessions.size)
        for ((i, s) in _sessions.withIndex()) {
            val key = "${PREFS_KEY_SESSION}${i}"
            prefs.putString("${key}_title", s.title)
            prefs.putInt("${key}_id", s.id)
            prefs.putBoolean("${key}_ai", s.aiSession)
            prefs.putBoolean("${key}_locked", s.locked)
            prefs.putString("${key}_color", _sessionColors[s.id] ?: "")
        }
        prefs.apply()
    }

    private fun restoreSessions(themeName: String = "classic-dark"): Boolean {
        val act = activity ?: return false
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val count = prefs.getInt(PREFS_KEY_COUNT, 0)
        if (count == 0) return false
        for (i in 0 until count) {
            val key = "${PREFS_KEY_SESSION}${i}"
            val title = prefs.getString("${key}_title", null)
            if (title == null) {
                android.util.Log.w(TAG, "restoreSessions: null title at index $i, skipping")
                continue
            }
            val id = prefs.getInt("${key}_id", i + 1)
            val aiSession = prefs.getBoolean("${key}_ai", false)
            val locked = prefs.getBoolean("${key}_locked", false)
            val color = prefs.getString("${key}_color", "") ?: ""
            val ts = runCatching { createTerminalSession(id) }.getOrElse { e ->
                android.util.Log.e(TAG, "restoreSessions: failed to create session $id", e)
                continue
            }
            _sessions.add(EmbeddedTermSession(id, title, ts, aiSession = aiSession, locked = locked))
            if (color.isNotEmpty()) _sessionColors[id] = color
        }
        if (_sessions.isNotEmpty()) {
            switchSession(_sessions.last().id)
        }
        applyThemeToAll(themeName)
        return _sessions.isNotEmpty()
    }
}
