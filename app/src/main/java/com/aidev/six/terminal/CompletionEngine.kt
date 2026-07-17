package com.aidev.six.terminal

import android.app.Activity
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.aidev.six.Constants
import com.aidev.six.TerminalImeProxyEditText
import com.termux.view.TerminalView
import java.io.File

class CompletionEngine(
    private var activity: Activity? = null,
    private var homeDir: File? = null,
    private var session: com.termux.terminal.TerminalSession? = null,
    private var terminalView: TerminalView? = null,
    private var inputProxy: TerminalImeProxyEditText? = null,
    private val onCompletionsChanged: () -> Unit = {}
) {
    var inputBuffer: String = ""
        internal set
    var composingBuffer: String = ""
        internal set
    @Volatile var cachedCompletionPwd: String = ""
        internal set

    fun updateActivity(act: Activity) { activity = act }
    fun updateHomeDir(dir: File?) { homeDir = dir }
    fun updateSession(s: com.termux.terminal.TerminalSession?) { session = s }
    fun updateTerminalView(v: TerminalView?) { terminalView = v }
    fun updateInputProxy(p: TerminalImeProxyEditText?) { inputProxy = p }

    fun refreshCompletions() { onCompletionsChanged() }

    fun completionSuggestions(): List<TerminalCompletion> {
        val raw = completionInput().trimStart()
        val act = activity ?: return emptyList()

        val paths = pathCompletions(raw)
        val pinned = pinnedCompletions(act)
        val allCmds = (staticCommands() + filesystemCommands()).distinctBy { it.insertText }
        val source = (paths + pinned + allCmds).distinctBy { it.insertText }

        if (raw.isBlank()) return source.take(8)

        val p = raw.lowercase()
        val matches = source.filter { item ->
            val text = item.insertText.lowercase()
            val label = item.label.lowercase()
            text.startsWith(p) ||
            label.startsWith(p) ||
            (p.length >= 2 && acronymMatch(p, text)) ||
            fuzzyCompletionMatch(raw, item)
        }

        return matches
            .sortedWith(compareBy<TerminalCompletion> { completionRank(p, it) }
                .thenByDescending { completionFrequency(it.insertText) }
                .thenBy { it.insertText.length }
                .thenBy { it.insertText })
            .take(8)
    }

    private fun completionInput(): String = inputBuffer + composingBuffer

    // ── Phase 0: Acronym matching ────────────────────────────────
    private fun acronymMatch(prefix: String, text: String): Boolean {
        val segments = text.split(Regex("[^\\p{L}\\p{N}]+"))
        val initials = segments.mapNotNull { it.firstOrNull()?.lowercaseChar() }
        if (initials.size < 2) return false

        var pi = 0
        for (initial in initials) {
            if (pi < prefix.length && prefix[pi] == initial) pi++
        }
        return pi == prefix.length
    }

    // ── Ranking (acronym tier + frequency) ───────────────────────
    private fun completionRank(prefix: String, item: TerminalCompletion): Int {
        val p = prefix.lowercase()
        val text = item.insertText.lowercase()
        val kindBase = when (item.kind) {
            "PIN" -> 0
            "DIR" -> 10
            "FILE" -> 11
            "HIDDEN_DIR" -> 12
            "HIDDEN_FILE" -> 13
            "ENV" -> 20
            else -> 40
        }
        return when {
            text == p -> kindBase
            text.startsWith(p) -> kindBase + 1
            acronymMatch(p, text) -> kindBase + 2
            text.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(p) } -> kindBase + 4
            else -> kindBase + 9
        }
    }

    // ── Fuzzy match (fallback) ───────────────────────────────────
    private fun fuzzyCompletionMatch(prefix: String, item: TerminalCompletion): Boolean {
        if (prefix.length < 2) return false
        val normalizedPrefix = prefix.lowercase().filter { it.isLetterOrDigit() }
        if (normalizedPrefix.length < 2) return false
        return item.insertText
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .any { it.startsWith(normalizedPrefix) }
    }

    // ── Phase 1: Full static command list ────────────────────────
    private fun staticCommands(): List<TerminalCompletion> = listOf(
        // Core aidev commands (via aidev-ubuntu-core)
        TerminalCompletion("ubuntu", "ubuntu", "CMD"),
        TerminalCompletion("install-ubuntu", "install-ubuntu", "CMD"),
        TerminalCompletion("aidev-auto-bootstrap", "aidev-auto-bootstrap", "CMD"),
        TerminalCompletion("aidev-doctor", "aidev-doctor", "CMD"),
        TerminalCompletion("setup-dev-env", "setup-dev-env", "CMD"),
        TerminalCompletion("opencode-check", "opencode-check", "CMD"),
        TerminalCompletion("setup-opencode", "setup-opencode", "CMD"),
        TerminalCompletion("aidev-build-request", "aidev-build-request", "CMD"),
        TerminalCompletion("aidev-apk-info", "aidev-apk-info", "CMD"),
        TerminalCompletion("aidev-create-android-project", "aidev-create-android-project", "CMD"),
        TerminalCompletion("aidev-gen", "aidev-gen", "CMD"),
        TerminalCompletion("aidev-error-why", "aidev-error-why", "CMD"),
        TerminalCompletion("aidev-index", "aidev-index", "CMD"),
        TerminalCompletion("aidev-opencode", "aidev-opencode", "CMD"),
        TerminalCompletion("aidev-install", "aidev-install", "CMD"),
        TerminalCompletion("aidev-clean", "aidev-clean", "CMD"),
        TerminalCompletion("aidev-proxy", "aidev-proxy", "CMD"),
        TerminalCompletion("aidev-logcat", "aidev-logcat", "CMD"),
        TerminalCompletion("aidev-logcat --follow", "aidev-logcat --follow", "CMD"),
        TerminalCompletion("aidev-logcat --clear", "aidev-logcat --clear", "CMD"),
        TerminalCompletion("aidev-logcat --level ERROR", "aidev-logcat --level ERROR", "CMD"),
        TerminalCompletion("aidev-logcat --lines 500", "aidev-logcat --lines 500", "CMD"),
        TerminalCompletion("aidev-shizuku", "aidev-shizuku", "CMD"),
        TerminalCompletion("aidev-shizuku status", "aidev-shizuku status", "CMD"),
        TerminalCompletion("install-aitool", "install-aitool", "CMD"),
        TerminalCompletion("check-dev-env", "check-dev-env", "CMD"),
        TerminalCompletion("repair-dev-env", "repair-dev-env", "CMD"),
        TerminalCompletion("aidev-backup", "aidev-backup ", "CMD"),
        TerminalCompletion("aidev-backup create", "aidev-backup create", "CMD"),
        TerminalCompletion("aidev-backup create --quick", "aidev-backup create --quick", "CMD"),
        TerminalCompletion("aidev-backup list", "aidev-backup list", "CMD"),
        TerminalCompletion("aidev-backup restore", "aidev-backup restore ", "CMD"),
        TerminalCompletion("aidev-anr", "aidev-anr ", "CMD"),
        TerminalCompletion("aidev-anr list", "aidev-anr list", "CMD"),
        TerminalCompletion("aidev-anr latest", "aidev-anr latest", "CMD"),
        TerminalCompletion("aidev-anr summary", "aidev-anr summary", "CMD"),
        TerminalCompletion("aidev-tombstone", "aidev-tombstone ", "CMD"),
        TerminalCompletion("aidev-tombstone list", "aidev-tombstone list", "CMD"),
        TerminalCompletion("aidev-tombstone latest", "aidev-tombstone latest", "CMD"),
        TerminalCompletion("aidev-crash-why", "aidev-crash-why ", "CMD"),
        TerminalCompletion("aidev-crash-why --anr", "aidev-crash-why --anr", "CMD"),
        TerminalCompletion("aidev-dumpsys", "aidev-dumpsys ", "CMD"),
        TerminalCompletion("aidev-dumpsys meminfo", "aidev-dumpsys meminfo", "CMD"),
        TerminalCompletion("aidev-dumpsys activity", "aidev-dumpsys activity", "CMD"),
        TerminalCompletion("aidev-dumpsys battery", "aidev-dumpsys battery", "CMD"),

        // Agent helper scripts
        TerminalCompletion("aidev-current-project", "aidev-current-project", "CMD"),
        TerminalCompletion("aidev-agent-context", "aidev-agent-context", "CMD"),
        TerminalCompletion("aidev-agent-context-file", "aidev-agent-context-file", "CMD"),
        TerminalCompletion("aidev-agent-summary", "aidev-agent-summary", "CMD"),
        TerminalCompletion("aidev-agent-log", "aidev-agent-log", "CMD"),
        TerminalCompletion("aidev-agent-tail", "aidev-agent-tail", "CMD"),

        // System control
        TerminalCompletion("sysnotify", "sysnotify", "CMD"),
        TerminalCompletion("screencap", "screencap", "CMD"),
        TerminalCompletion("volume", "volume", "CMD"),
        TerminalCompletion("brightness", "brightness", "CMD"),
        TerminalCompletion("sysclip", "sysclip", "CMD"),

        // Shell utilities
        TerminalCompletion("list-listen-ports", "list-listen-ports", "CMD"),
        TerminalCompletion("task-list", "task-list", "CMD"),
        TerminalCompletion("task-run", "task-run", "CMD"),

        // Android shell helpers
        TerminalCompletion("android-sh", "android-sh", "CMD"),
        TerminalCompletion("pmx", "pmx", "CMD"),
        TerminalCompletion("amx", "amx", "CMD"),
        TerminalCompletion("getpropx", "getpropx", "CMD"),
        TerminalCompletion("logcatx", "logcatx", "CMD"),

        // Aliases
        TerminalCompletion("ll", "ll", "CMD"),
        TerminalCompletion("alias ll='ls -lah'", "alias ll='ls -lah'", "CMD"),

        // Common shell commands
        TerminalCompletion("help", "help", "CMD"),
        TerminalCompletion("history", "history", "CMD"),
        TerminalCompletion("pwd", "pwd", "CMD"),
        TerminalCompletion("clear", "clear", "CMD"),
        TerminalCompletion("alias", "alias", "CMD"),
        TerminalCompletion("ls", "ls", "CMD"),
        TerminalCompletion("ls -la", "ls -la", "CMD"),
        TerminalCompletion("cd", "cd", "CMD"),

        // Shell tools
        TerminalCompletion("grep -R", "grep -R ", "CMD"),
        TerminalCompletion("find", "find . -maxdepth 2 -type f", "CMD"),
        TerminalCompletion("df -h", "df -h", "CMD"),
        TerminalCompletion("ps aux", "ps aux", "CMD"),
        TerminalCompletion("env", "env | sort", "CMD"),
        TerminalCompletion("whoami", "whoami", "CMD"),
        TerminalCompletion("cat /etc/os-release", "cat /etc/os-release", "CMD"),

        // Gradle commands
        TerminalCompletion("./gradlew :app:assembleDebug", "./gradlew :app:assembleDebug", "CMD"),
        TerminalCompletion("./gradlew :app:compileDebugKotlin", "./gradlew :app:compileDebugKotlin", "CMD"),
        TerminalCompletion("./gradlew :app:testDebugUnitTest", "./gradlew :app:testDebugUnitTest", "CMD"),
        TerminalCompletion("./gradlew :app:testShellScripts", "./gradlew :app:testShellScripts", "CMD"),
        TerminalCompletion("./gradlew :app:assembleRelease", "./gradlew :app:assembleRelease", "CMD"),
        TerminalCompletion("./gradlew clean", "./gradlew clean", "CMD"),
        TerminalCompletion("./gradlew clean :app:assembleDebug", "./gradlew clean :app:assembleDebug", "CMD"),
        TerminalCompletion("./gradlew build", "./gradlew build", "CMD"),
        TerminalCompletion("./gradlew tasks", "./gradlew tasks", "CMD"),

        // Git commands (full coverage for subcommand completion)
        TerminalCompletion("git status", "git status", "CMD"),
        TerminalCompletion("git status --short", "git status --short", "CMD"),
        TerminalCompletion("git add .", "git add .", "CMD"),
        TerminalCompletion("git commit -m \"\"", "git commit -m \"\"", "CMD"),
        TerminalCompletion("git diff", "git diff", "CMD"),
        TerminalCompletion("git diff --stat", "git diff --stat", "CMD"),
        TerminalCompletion("git diff --cached", "git diff --cached", "CMD"),
        TerminalCompletion("git log", "git log", "CMD"),
        TerminalCompletion("git log --oneline -10", "git log --oneline -10", "CMD"),
        TerminalCompletion("git log --graph", "git log --graph", "CMD"),
        TerminalCompletion("git pull", "git pull", "CMD"),
        TerminalCompletion("git push", "git push", "CMD"),
        TerminalCompletion("git switch", "git switch ", "CMD"),
        TerminalCompletion("git checkout", "git checkout ", "CMD"),
        TerminalCompletion("git branch", "git branch", "CMD"),
        TerminalCompletion("git branch -a", "git branch -a", "CMD"),
        TerminalCompletion("git merge", "git merge", "CMD"),
        TerminalCompletion("git rebase", "git rebase", "CMD"),
        TerminalCompletion("git stash", "git stash", "CMD"),
        TerminalCompletion("git stash pop", "git stash pop", "CMD"),
        TerminalCompletion("git reset", "git reset", "CMD"),
        TerminalCompletion("git reset --hard", "git reset --hard", "CMD"),
        TerminalCompletion("git clone", "git clone", "CMD"),
        TerminalCompletion("git fetch", "git fetch", "CMD"),
        TerminalCompletion("git remote -v", "git remote -v", "CMD"),
    )

    // ── Phase 1: Filesystem scan ─────────────────────────────────
    private fun filesystemCommands(): List<TerminalCompletion> {
        val home = homeDir ?: return emptyList()
        val bin = File(home, "dev-env/bin")
        if (!bin.isDirectory) return emptyList()
        return bin.listFiles().orEmpty()
            .filter { it.isFile && it.canExecute() && !it.name.startsWith(".") && !it.name.startsWith("_") }
            .filter { it.name != "aidev_write_pwd" }
            .map { TerminalCompletion(it.name, it.name, "CMD") }
    }

    // ── Phase 3: Frequency tracking ──────────────────────────────
    private fun completionFrequency(command: String): Int {
        val act = activity ?: return 0
        return act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .getInt("completion_freq_$command", 0)
    }

    fun recordCompletion(item: TerminalCompletion) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val key = "completion_freq_${item.insertText}"
        val count = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, count).apply()
    }

    // ── Pinned completions ───────────────────────────────────────
    private fun pinnedCompletions(activity: Activity): List<TerminalCompletion> =
        activity.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            .getString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS, "")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.map { TerminalCompletion(it, it, "PIN") }
            .orEmpty()

    // ── Path completion ──────────────────────────────────────────
    private fun pathCompletions(input: String): List<TerminalCompletion> {
        val home = homeDir ?: return emptyList()
        val token = input.substringAfterLast(' ', "")
        val command = input.substringBefore(' ', "").lowercase()
        val pathMode = input.contains(' ') && command in setOf(
            "cd", "ls", "cat", "less", "tail", "head", "nano", "vim",
            "rm", "cp", "mv", "mkdir", "touch", "grep"
        ) || token.startsWith("/") || token.startsWith("./") ||
            token.startsWith("../") || token.startsWith("~")
        if (!pathMode) return emptyList()

        val currentDir = currentUbuntuDirFile(home)
        val hostHome = home
        val (baseDir, typedPrefix, displayPrefix) = when {
            token.startsWith("/root/") -> {
                pathParts(token.removePrefix("/root/"), "/root/",
                    File(home, "ubuntu-rootfs/root"))
            }
            token == "/root" || token == "~" || token == "~/" ->
                Triple(File(home, "ubuntu-rootfs/root"), "",
                    if (token.startsWith("~")) "~/" else "/root/")
            token.startsWith("/host-home/") -> {
                pathParts(token.removePrefix("/host-home/"), "/host-home/", hostHome)
            }
            token.startsWith("/") -> {
                val relative = token.removePrefix("/")
                pathParts(relative, "/", File(home, "ubuntu-rootfs"))
            }
            token.contains('/') -> {
                val slash = token.lastIndexOf('/')
                val dirPart = token.substring(0, slash)
                val namePart = token.substring(slash + 1)
                val cleanDir = dirPart.removePrefix("./")
                Triple(File(currentDir, cleanDir), namePart,
                    if (dirPart.isBlank()) "" else "$dirPart/")
            }
            else -> Triple(currentDir, token, "")
        }
        if (!baseDir.isDirectory) return emptyList()

        val beforeToken = input.dropLast(token.length)
        return baseDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.name.startsWith(typedPrefix, ignoreCase = true) }
            .filter { file ->
                when (command) {
                    "cd" -> file.isDirectory
                    "cat", "less", "tail", "head", "nano", "vim", "grep" -> file.isFile
                    else -> true
                }
            }
            .sortedWith(compareBy<File> { it.name.startsWith(".") }
                .thenBy { !it.isDirectory }
                .thenBy { it.name.lowercase() })
            .take(8)
            .map { file ->
                val suffix = if (file.isDirectory) "/" else ""
                val path = displayPrefix + file.name + suffix
                val kind = when {
                    file.name.startsWith(".") && file.isDirectory -> "HIDDEN_DIR"
                    file.name.startsWith(".") -> "HIDDEN_FILE"
                    file.isDirectory -> "DIR"
                    else -> "FILE"
                }
                TerminalCompletion(path, beforeToken + path, kind)
            }
            .toList()
    }

    private fun pathParts(relative: String, displayRoot: String,
                          hostRoot: File): Triple<File, String, String> {
        val slash = relative.lastIndexOf('/')
        val dirPart = if (slash >= 0) relative.substring(0, slash) else ""
        val namePart = if (slash >= 0) relative.substring(slash + 1) else relative
        return Triple(File(hostRoot, dirPart), namePart,
            displayRoot + dirPart.let { if (it.isBlank()) "" else "$it/" })
    }

    private fun currentUbuntuDirFile(home: File): File {
        val pwd = cachedCompletionPwd.takeIf { it.isNotBlank() }
            ?: File(home, ".aidev-current-pwd").takeIf { it.isFile }
                ?.readText()?.trim().orEmpty()
                .ifBlank { "/root" }
        return when {
            pwd == "/host-home" -> home
            pwd.startsWith("/host-home/") -> File(home, pwd.removePrefix("/host-home/"))
            // 共享 workspace 绑定于宿主 home/workspace（宇宙A/B 内均为 /workspace）
            pwd == "/workspace" -> File(home, "workspace")
            pwd.startsWith("/workspace/") -> File(home, "workspace/${pwd.removePrefix("/workspace/")}")
            pwd == "/root" -> File(home, "ubuntu-rootfs/root")
            pwd.startsWith("/root/") -> File(home, "ubuntu-rootfs/root/${pwd.removePrefix("/root/")}")
            pwd.startsWith("/") -> File(home, "ubuntu-rootfs/${pwd.removePrefix("/")}")
            else -> File(home, "ubuntu-rootfs/root")
        }
    }

    // ── Apply completion ─────────────────────────────────────────
    fun applyCompletion(item: TerminalCompletion) {
        recordCompletion(item)
        val target = item.insertText
        val committed = inputBuffer
        val current = completionInput()
        val insert = if (composingBuffer.isNotEmpty() &&
            target.equals(current, ignoreCase = true)) {
            if (target.startsWith(committed)) target.drop(committed.length) else target
        } else if (target.equals(current, ignoreCase = true) ||
            target.equals(committed, ignoreCase = true)) {
            ""
        } else if (target.startsWith(committed) && composingBuffer.isEmpty()) {
            target.drop(committed.length)
        } else {
            "\u007F".repeat(committed.length) + target
        }
        if (insert.isEmpty()) return
        clearComposingInput()
        session?.write(insert)
        inputBuffer = target
        refreshCompletions()
        focusTerminalInput()
    }

    fun executeCompletion(item: TerminalCompletion) {
        recordCompletion(item)
        val committed = inputBuffer
        if (committed.isNotEmpty()) {
            session?.write("\u007F".repeat(committed.length))
        }
        session?.write(item.insertText.trimEnd() + "\r")
        inputBuffer = ""
        clearComposingInput()
        refreshCompletions()
        focusTerminalInput()
    }

    private fun clearComposingInput() {
        composingBuffer = ""
        inputProxy?.let { proxy ->
            proxy.clearProxyText()
            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager)?.restartInput(proxy)
        }
    }

    fun focusTerminalInput() {
        val proxy = inputProxy
        if (proxy != null) {
            proxy.requestFocus()
            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager)?.restartInput(proxy)
        } else {
            terminalView?.requestFocus()
        }
    }

    fun updateInputBuffer(text: String) {
        text.forEach { ch ->
            when (ch) {
                '\r', '\n' -> { inputBuffer = ""; composingBuffer = "" }
                '\b', '\u007F' -> inputBuffer = inputBuffer.dropLast(1)
                else -> if (!ch.isISOControl()) inputBuffer += ch
            }
        }
        refreshCompletions()
    }

    fun currentProjectDir(): File? =
        activity?.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
            ?.getString(Constants.PrefKeys.CURRENT_PROJECT_PATH, "")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

    fun pinCompletion(item: TerminalCompletion) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS, "")
            ?.lines()?.filter { it.isNotBlank() && it != item.insertText }.orEmpty()
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS,
            (listOf(item.insertText) + old).take(12).joinToString("\n")).apply()
        refreshCompletions()
        android.widget.Toast.makeText(act, "已固定到常用", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun unpinCompletion(item: TerminalCompletion) {
        val act = activity ?: return
        val prefs = act.getSharedPreferences(Constants.PREFS_NAME, Activity.MODE_PRIVATE)
        val old = prefs.getString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS, "")?.lines().orEmpty()
        prefs.edit().putString(Constants.PrefKeys.TERMINAL_PINNED_COMPLETIONS,
            old.filter { it.isNotBlank() && it != item.insertText }.joinToString("\n")).apply()
        refreshCompletions()
        android.widget.Toast.makeText(act, "已取消固定", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun focusTerminalInputDelayed() {
        activity?.let { act ->
            terminalView?.postDelayed({ focusTerminalInput() }, 600)
        }
    }
}
