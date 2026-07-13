package com.aidev.six

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.File

@Immutable
data class TerminalShellAssetPaths(
    val home: File,
    val entry: File
)

@Suppress("SetWorldReadable")
object TerminalShellAssets {

    fun ensure(activity: Activity): TerminalShellAssetPaths {
        val home = File(activity.filesDir, "home").apply { mkdirs() }
        File(home, "workspace").mkdirs()
        val rc = File(home, ".aidevrc")
        val entry = File(home, ".aidev_shell_entry")
        val rootfs = File(home, "ubuntu-rootfs")

        // 第一类：生成脚本（含核心入口 aidev-ubuntu-core），无条件写，且必须先于 .aidevrc
        installAidevCommandScripts(
            home,
            PathConfig.nativeLibDir(activity).absolutePath,
            PathConfig.prootLibDir(activity).absolutePath
        )
        // 校验核心入口已生成，避免"有 .aidevrc 提示符却无 aidev-ubuntu-core 命令"的半残状态
        val core = File(home, "dev-env/bin/aidev-ubuntu-core")
        if (!core.isFile || core.length() == 0L) {
            throw IllegalStateException("dev-env 核心脚本生成失败: ${core.absolutePath}")
        }
        writeCanonicalRc(activity, home, rc)
        writeShellEntry(home, rc, entry)
        writeGradleInitScripts(home, rootfs)
        // 工作区规范 AGENTS.md（每次启动刷新，确保与内置规范同步）
        runCatching { deployWorkspaceAgents(activity, home) }
            .onFailure { Log.e("TerminalShellAssets", "deployWorkspaceAgents failed (non-fatal)", it) }

        // 第二类：大文件用 versionCode 门控（rootfs 相关，失败不致命，不连累核心环境）
        val deployMarker = File(home, ".asset-deploy-code")
        val currentCode = getVersionCode(activity)
        // libtalloc.so.2 符号链接缺失（首装/被清）时无视版本门控重建
        val prootLinkMissing = !File(PathConfig.prootLibDir(activity), "libtalloc.so.2").exists()
        val needsDeploy = prootLinkMissing ||
            !deployMarker.exists() || deployMarker.readText().trim() != currentCode.toString()
        if (needsDeploy) {
            runCatching {
                installProotSupportLibraries(activity)
                if (rootfs.isDirectory) {
                    deployLargeAssets(activity, rootfs)
                }
            }.onFailure { Log.e("TerminalShellAssets", "deploy gated assets failed (non-fatal)", it) }
            runCatching { deployMarker.writeText("$currentCode\n") }
        }

        // OpenCode 命令配置文件每次启动都部署（确保与新命令同步），失败不致命
        if (rootfs.isDirectory) {
            runCatching { deployOpenCodeCommands(activity, rootfs) }
                .onFailure { Log.e("TerminalShellAssets", "deployOpenCodeCommands failed (non-fatal)", it) }
            // rootfs 辅助脚本每次检查（rootfs 可能被重装），失败不致命
            runCatching { UbuntuBootstrapScripts.copyAssetScripts(activity, rootfs) }
                .onFailure { Log.e("TerminalShellAssets", "copyAssetScripts failed (non-fatal)", it) }
        }

        return TerminalShellAssetPaths(home, entry)
    }

    private fun getVersionCode(activity: Activity): Long {
        val pkgInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        }
    }

    /**
     * proot 可执行体 + 依赖 .so 已随 APK 解包到 nativeLibraryDir（唯一 exec 允许区），无需再拷贝。
     * 此处仅补一个版本化 soname 符号链接：libtalloc.so.2 -> nativeLibDir/libtalloc.so
     * （libtalloc.so.2 无法作为 lib*.so 被系统解包，见 PathConfig.prootLibDir）。
     */
    private fun installProotSupportLibraries(activity: Activity) {
        val linkDir = PathConfig.prootLibDir(activity).apply { mkdirs() }
        val nativeDir = PathConfig.nativeLibDir(activity)
        val link = File(linkDir, "libtalloc.so.2")
        val target = File(nativeDir, "libtalloc.so")
        runCatching {
            if (link.exists() || isSymlink(link)) link.delete()
            android.system.Os.symlink(target.absolutePath, link.absolutePath)
        }.onFailure {
            Log.e("TerminalShellAssets", "installProotSupportLibraries: symlink libtalloc.so.2 failed", it)
            // 兜底：符号链接失败则退化为读拷贝（部分文件系统不支持 symlink）
            runCatching {
                target.inputStream().use { input ->
                    link.outputStream().use { output -> input.copyTo(output) }
                }
                link.setReadable(true, false)
            }
        }
    }

    private fun isSymlink(f: File): Boolean = runCatching {
        f.absolutePath != f.canonicalPath
    }.getOrDefault(false)

    private fun installAidevCommandScripts(home: File, nativeDir: String, prootExtraLibsPath: String) {
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        val core = File(bin, "aidev-ubuntu-core")
        core.writeText(UbuntuBootstrapScripts.aidevUbuntuCommandScript(home.absolutePath, nativeDir, prootExtraLibsPath))
        core.setReadable(true, false)
        listOf("ubuntu", "install-ubuntu", "aidev-auto-bootstrap", "aidev-doctor").forEach { name ->
            val out = File(bin, name)
            out.writeText("# AIDev command marker. Android 私有目录禁止直接执行脚本；实际入口由 .aidevrc 函数转发。\n")
            out.setReadable(true, false)
        }
        // 系统控制脚本（通知、截图、音量、亮度、剪贴板、应用管理）
        writeSystemScript(bin, "sysnotify", "send notification")
        writeSystemScript(bin, "screencap", "take screenshot")
        writeSystemScript(bin, "volume", "control volume")
        writeSystemScript(bin, "brightness", "control brightness")
        writeSystemScript(bin, "sysclip", "clipboard get/set")
        writeSystemScript(bin, "startapp", "start app")
        writeSystemScript(bin, "stopapp", "stop app")
        writeSystemScript(bin, "installapk", "install apk")
        writeSystemScript(bin, "uninstallapp", "uninstall app")
        writeSystemScript(bin, "aidev-proxy", "proxy manager")
        // 两端共用脚本（agent 辅助 + 系统工具）
        UbuntuBootstrapScripts.agentHostScripts().forEach { (name, content) ->
            val out = File(bin, name)
            out.writeText(content + "\n")
            out.setExecutable(true, false)
            out.setReadable(true, false)
        }
        val prootBin = File(bin, ".privot").apply { mkdirs() }
        UbuntuBootstrapScripts.agentPrivotScripts().forEach { (name, content) ->
            val out = File(prootBin, name)
            out.writeText(content + "\n")
            out.setExecutable(true, false)
            out.setReadable(true, false)
        }
        File(home, ".aidev_shell_fallback").delete()
    }

    /** 部署大文件（curl/ca-certs），仅在 versionCode 变更时执行 */
    private fun deployLargeAssets(activity: Activity, rootfs: File) {
        val targetDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        runCatching {
            activity.assets.open("tools/curl").use { input ->
                File(targetDir, "curl").outputStream().use { output -> input.copyTo(output) }
            }
            File(targetDir, "curl").setExecutable(true)
        }.onFailure { Log.e("TerminalShellAssets", "deployLargeAssets: curl failed", it) }

        val caCertsDir = File(rootfs, "etc/ssl/certs").apply { mkdirs() }
        runCatching {
            activity.assets.open("tools/ca-certificates.crt").use { input ->
                File(caCertsDir, "ca-certificates.crt").outputStream().use { output -> input.copyTo(output) }
            }
            File(caCertsDir, "ca-certificates.crt").setReadable(true)
        }.onFailure { Log.e("TerminalShellAssets", "deployLargeAssets: ca-certificates failed", it) }
    }

    /** 部署 OpenCode 命令配置 .md 文件，每次启动都执行 */
    private fun deployOpenCodeCommands(activity: Activity, rootfs: File) {
        val cmdsDir = File(rootfs, "root/.config/opencode/commands").apply { mkdirs() }
        listOf(
            "aidev-build", "aidev-build-request", "aidev-crash-report", "aidev-verify-run", "aidev-deploy", "aidev-apk-info", "aidev-create-android-project",
            "aidev-gen", "aidev-error-why", "aidev-logcat", "aidev-index"
        ).forEach { name ->
            runCatching {
                activity.assets.open("config/opencode/commands/$name.md").use { input ->
                    File(cmdsDir, "$name.md").outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Log.e("TerminalShellAssets", "deployOpenCodeCommands: $name.md failed", it) }
        }
        // 全局 AGENTS.md：OpenCode 每次会话都加载 ~/.config/opencode/AGENTS.md，
        // 用它给 vibe coding 立宇宙B 构建规范（黄金版本/项目须在 /workspace/禁模块级 repositories 等）
        runCatching {
            val cfgDir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
            activity.assets.open("config/opencode/AGENTS.md").use { input ->
                File(cfgDir, "AGENTS.md").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { Log.e("TerminalShellAssets", "deployOpenCodeCommands: AGENTS.md failed", it) }
    }

    /** 把工作区规范 AGENTS.md 放到 workspace 根，作为项目级规范（与全局互为补充），每次启动刷新 */
    private fun deployWorkspaceAgents(activity: Activity, home: File) {
        runCatching {
            val ws = File(home, "workspace").apply { mkdirs() }
            activity.assets.open("config/opencode/AGENTS.md").use { input ->
                File(ws, "AGENTS.md").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { Log.e("TerminalShellAssets", "deployWorkspaceAgents failed", it) }
    }

    private fun writeCanonicalRc(activity: Activity, home: File, rc: File) {
        val nativeDir = activity.applicationInfo.nativeLibraryDir
        val prootExtraLibs = PathConfig.prootLibDir(activity).absolutePath
        val v = getVersionCode(activity)
        rc.writeText(
            """
            # AIDev canonical shell rc. 自动生成，请不要在这里保存个人配置。
            AIDEV_VERSION="$v"
            # 宿主(宇宙H) AIDEV_HOME 为 App 私有绝对路径；在 proot 内(宇宙A/B)宿主 home 绑定于 /host-home。
            # 本 rc 会被 rootfs 的 .bashrc 通过 `. /host-home/.aidevrc` 复用，故须按位置解析，避免命令指向不存在的宿主路径。
            if [ -d /host-home ]; then AIDEV_HOME="/host-home"; else AIDEV_HOME="${home.absolutePath}"; fi
            AIDEV_BIN="${'$'}AIDEV_HOME/dev-env/bin"
            AIDEV_ROOTFS="${'$'}AIDEV_HOME/ubuntu-rootfs"
            AIDEV_COMPILER_ROOTFS="${'$'}AIDEV_HOME/compiler_rootfs"
            AIDEV_WORKSPACE="${'$'}AIDEV_HOME/workspace"
            AIDEV_NATIVE="$nativeDir"
            # proot 可执行体在 nativeLibraryDir（唯一 exec 允许区）
            AIDEV_PROOT_LIBS="$nativeDir"
            # libtalloc.so.2 版本化 soname 的符号链接目录（加入 LD_LIBRARY_PATH）
            AIDEV_PROOT_EXTRA_LIBS="$prootExtraLibs"
            AIDEV_PROOT="${'$'}AIDEV_PROOT_LIBS/libproot.so"
            AIDEV_PROOT_LOADER="${'$'}AIDEV_PROOT_LIBS/libproot_loader.so"
            PROOT_LOADER="${'$'}AIDEV_PROOT_LOADER"
            PROOT_TMP_DIR="${'$'}AIDEV_HOME/proot-tmp"
            LD_LIBRARY_PATH="${'$'}AIDEV_PROOT_EXTRA_LIBS:${'$'}AIDEV_NATIVE${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
            ANDROID_SDK_ROOT="${'$'}AIDEV_HOME/android-sdk"
            GRADLE_USER_HOME="${'$'}AIDEV_HOME/gradle-cache"
            export AIDEV_VERSION AIDEV_HOME AIDEV_BIN AIDEV_ROOTFS AIDEV_COMPILER_ROOTFS AIDEV_WORKSPACE AIDEV_NATIVE AIDEV_PROOT_LIBS AIDEV_PROOT_EXTRA_LIBS AIDEV_PROOT AIDEV_PROOT_LOADER PROOT_LOADER PROOT_TMP_DIR LD_LIBRARY_PATH
            export ANDROID_SDK_ROOT GRADLE_USER_HOME
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export PATH="/usr/local/bin:${'$'}AIDEV_BIN:${'$'}ANDROID_SDK_ROOT/cmdline-tools/latest/bin:/system/bin:/system/xbin:${'$'}PATH"
            AIDEV_REALM_COLOR="$(printf '\033[0m')"
            case "${'$'}{AIDEV_REALM:-H}" in
              A) AIDEV_REALM_COLOR="$(printf '\033[32m')" ;;
              B) AIDEV_REALM_COLOR="$(printf '\033[33m')" ;;
              H|*) AIDEV_REALM_COLOR="$(printf '\033[36m')" ;;
            esac
            AIDEV_RESET="$(printf '\033[0m')"
            _aidev_ps1() {
              local _p="${'$'}{PWD/#${'$'}HOME/\~}"
              PS1="${'$'}{AIDEV_REALM_COLOR}aidev[${'$'}{AIDEV_REALM:-H}]${'$'}{AIDEV_RESET}:${'$'}{_p}# "
            }
            case "${'$'}{PROMPT_COMMAND:-}" in
              *_aidev_ps1*) ;;
              *) PROMPT_COMMAND="_aidev_ps1${'$'}{PROMPT_COMMAND:+;${'$'}{PROMPT_COMMAND}}" ;;
            esac
            alias ll='ls -lah'
            [ -f "${'$'}HOME/.aidev_aliases" ] && . "${'$'}HOME/.aidev_aliases"
            android-sh() { /system/bin/sh -lc "${'$'}*"; }
            pmx() { android-sh "pm ${'$'}*"; }
            amx() { android-sh "am ${'$'}*"; }
            getpropx() { android-sh "getprop ${'$'}*"; }
            logcatx() { android-sh "logcat ${'$'}*"; }
            ubuntu() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" ubuntu "${'$'}@"; }
            compiler() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" compiler "${'$'}@"; }
            install-ubuntu() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" install-ubuntu "${'$'}@"; }
            install-compiler() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" install-compiler "${'$'}@"; }
            aidev-ensure-envs() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-ensure-envs "${'$'}@"; }
            aidev-auto-bootstrap() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-auto-bootstrap "${'$'}@"; }
            aidev-doctor() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-doctor "${'$'}@"; }
            setup-dev-env() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" setup-dev-env "${'$'}@"; }
            opencode-check() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" opencode-check "${'$'}@"; }
            setup-opencode() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" setup-opencode "${'$'}@"; }
            aidev-current-project() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-current-project" "${'$'}@"; }
            aidev-agent-context() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-context" "${'$'}@"; }
            aidev-agent-context-file() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-context-file" "${'$'}@"; }
            aidev-agent-summary() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-summary" "${'$'}@"; }
            aidev-agent-log() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-log" "${'$'}@"; }
            aidev-agent-tail() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-tail" "${'$'}@"; }
            aidev-shizuku() { /system/bin/sh "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-shizuku" "${'$'}@"; }
            aidev-logcat() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-logcat "${'$'}@"; }
            aidev-apk-info() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-apk-info "${'$'}@"; }
            aidev-build() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-build "${'$'}@"; }
            aidev-build-request() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-build-request" "${'$'}@"; }
            aidev-crash-report() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-crash-report" "${'$'}@"; }
            aidev-create-android-project() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-create-android-project "${'$'}@"; }
            aidev-gen() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-gen "${'$'}@"; }
            aidev-error-why() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-error-why "${'$'}@"; }
            aidev-index() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-index "${'$'}@"; }
            android-sh() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" android-sh "${'$'}@"; }
            installapk() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" installapk "${'$'}@"; }
            uninstallapp() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" uninstallapp "${'$'}@"; }
            aidev-clean() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-clean "${'$'}@"; }
            list-listen-ports() { /system/bin/sh "${'$'}AIDEV_BIN/list-listen-ports" "${'$'}@"; }
            task-list() { /system/bin/sh "${'$'}AIDEV_BIN/task-list" "${'$'}@"; }
            task-run() { /system/bin/sh "${'$'}AIDEV_BIN/task-run" "${'$'}@"; }
            aidev-proxy() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-proxy" "${'$'}@"; }
            aidev-backup() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-backup "${'$'}@"; }
            aidev-anr() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-anr "${'$'}@"; }
            aidev-tombstone() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-tombstone "${'$'}@"; }
            aidev-crash-why() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-crash-why "${'$'}@"; }
            aidev-dumpsys() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-dumpsys "${'$'}@"; }
            """.trimIndent() + "\n"
        )
    }

    private fun writeSystemScript(binDir: File, name: String, desc: String) {
        val script = File(binDir, name)
        val content = when (name) {
            "sysnotify" -> """#!/bin/sh
                # AIDev system notification script
                # Usage: sysnotify [--priority min|low|default|high|max] [--ongoing] [--alert-once] <title> <message>
                PRIORITY=""
                ONGOING="false"
                ALERT_ONCE="false"
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --priority) PRIORITY="${'$'}2"; shift 2 ;;
                        --ongoing)  ONGOING="true"; shift ;;
                        --alert-once) ALERT_ONCE="true"; shift ;;
                        --help|-h)
                            echo "Usage: sysnotify [--priority min|low|default|high|max] [--ongoing] [--alert-once] <title> <message>"
                            exit 0 ;;
                        *) break ;;
                    esac
                done
                if [ ${'$'}# -lt 2 ]; then
                    echo "Usage: sysnotify [options] <title> <message>"
                    exit 1
                fi
                TITLE="${'$'}1"; shift
                MSG="${'$'}*"
                json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
                TITLE_ESC=$(printf '%s' "${'$'}TITLE" | json_escape)
                MSG_ESC=$(printf '%s' "${'$'}MSG" | json_escape)
                PRIORITY_ESC=$(printf '%s' "${'$'}PRIORITY" | json_escape)
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-notify"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"title\":\"${'$'}TITLE_ESC\",\"message\":\"${'$'}MSG_ESC\",\"priority\":\"${'$'}PRIORITY_ESC\",\"ongoing\":${'$'}ONGOING,\"alert_only_once\":${'$'}ALERT_ONCE}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo '{"status":"success","action":"notification sent"}'
                """.trimIndent()

            "screencap" -> """#!/bin/sh
                # AIDev screenshot script
                # Usage: screencap [output_path]
                OUT="${'$'}{1:-/sdcard/screenshot_$(date +%Y%m%d_%H%M%S).png}"
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"screencap\",\"path\":\"${'$'}OUT\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"screencap requested\",\"path\":\"${'$'}OUT\"}"
                """.trimIndent()

            "volume" -> """#!/bin/sh
                # AIDev volume control script
                # Usage: volume [media|ring|alarm|call] [0-15|+|-]
                STREAM="${'$'}{1:-media}"
                VAL="${'$'}2"
                case "${'$'}STREAM" in
                    media)  CODE=3 ;;
                    ring)   CODE=2 ;;
                    alarm)  CODE=4 ;;
                    call)   CODE=0 ;;
                    *) echo '{"status":"error","error":"stream must be media|ring|alarm|call"}'; exit 1 ;;
                esac
                if [ -z "${'$'}VAL" ]; then
                    CUR=$(/system/bin/dumpsys audio 2>/dev/null | grep -i "${'$'}STREAM" | head -1)
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"volume\":\"${'$'}CUR\"}"
                elif [ "${'$'}VAL" = "+" ] || [ "${'$'}VAL" = "-" ]; then
                    KEY=$(if [ "${'$'}VAL" = "+" ]; then echo 24; else echo 25; fi)
                    /system/bin/input keyevent "${'$'}KEY"
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"action\":\"${'$'}VAL\"}"
                else
                    REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                    mkdir -p "${'$'}REQ_DIR"
                    echo "{\"action\":\"volume\",\"stream\":${'$'}CODE,\"volume\":${'$'}VAL}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"volume\":${'$'}VAL}"
                fi
                """.trimIndent()

            "brightness" -> """#!/bin/sh
                # AIDev brightness control script
                # Usage: brightness [0-255|auto]
                VAL="${'$'}1"
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                if [ -z "${'$'}VAL" ]; then
                    CUR=$(/system/bin/settings get system screen_brightness 2>/dev/null || echo "unknown")
                    echo "{\"status\":\"success\",\"brightness\":${'$'}CUR}"
                elif [ "${'$'}VAL" = "auto" ]; then
                    echo "{\"action\":\"brightness\",\"auto\":true}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo '{"status":"success","mode":"auto"}'
                else
                    echo "{\"action\":\"brightness\",\"brightness\":${'$'}VAL}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo "{\"status\":\"success\",\"brightness\":${'$'}VAL}"
                fi
                """.trimIndent()

            "startapp" -> """#!/bin/sh
                # AIDev start app script
                # Usage: startapp <package_name>
                if [ -z "${'$'}1" ]; then
                    echo "Usage: startapp <package_name>"
                    exit 1
                fi
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"startapp\",\"package\":\"${'$'}1\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"started\",\"package\":\"${'$'}1\"}"
                """.trimIndent()

            "stopapp" -> """#!/bin/sh
                # AIDev stop app script
                # Usage: stopapp <package_name>
                if [ -z "${'$'}1" ]; then
                    echo "Usage: stopapp <package_name>"
                    exit 1
                fi
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"stopapp\",\"package\":\"${'$'}1\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"stopped\",\"package\":\"${'$'}1\"}"
                """.trimIndent()

            "installapk" -> """#!/bin/sh
                # AIDev install APK script
                # Usage: installapk <apk_path>
                if [ -z "${'$'}1" ] || [ ! -f "${'$'}1" ]; then
                    echo "Usage: installapk <apk_path>"
                    exit 1
                fi
                # resolve /host-home/ to real Android path
                APK="${'$'}1"
                case "${'$'}APK" in /host-home/*)
                    APK="${'$'}{AIDEV_HOME}${'$'}{APK#/host-home}"
                esac
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"installapk\",\"path\":\"${'$'}APK\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"install launched\",\"path\":\"${'$'}1\"}"
                """.trimIndent()

            "uninstallapp" -> """#!/bin/sh
                # AIDev uninstall app script
                # Usage: uninstallapp <package_name>
                if [ -z "${'$'}1" ]; then
                    echo "Usage: uninstallapp <package_name>"
                    exit 1
                fi
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"uninstallapp\",\"package\":\"${'$'}1\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"uninstall launched\",\"package\":\"${'$'}1\"}"
                """.trimIndent()

            "sysclip" -> """#!/bin/sh
                # AIDev clipboard script
                # Usage: sysclip set <text>
                #        sysclip get
                CMD="${'$'}{1:-}"
                if [ "${'$'}CMD" = "get" ]; then
                    /system/bin/service call clipboard 2 2>/dev/null || echo '{"status":"error","error":"clipboard read not supported"}'
                    exit 0
                fi
                shift 2>/dev/null
                TEXT="${'$'}*"
                json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                ESCAPED=$(printf '%s' "${'$'}TEXT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null)
                if [ -z "${'$'}ESCAPED" ]; then
                    ESCAPED=$(printf '%s' "${'$'}TEXT" | json_escape)
                    ESCAPED="\"${'$'}ESCAPED\""
                fi
                echo "{\"action\":\"clipboard\",\"text\":${'$'}ESCAPED}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"clipboard set\"}"
                """.trimIndent()

            "aidev-proxy" -> """#!/bin/sh
                # AIDev proxy manager (tinyproxy)
                # Usage: aidev-proxy [start|stop|restart|status]
                CONF="${'$'}{AIDEV_HOME}/tinyproxy.conf"
                PIDFILE="/tmp/tinyproxy.pid"
                mkdir -p "${'$'}{AIDEV_HOME}/proxy-cache"
                case "${'$'}1" in
                    start)
                        if [ -f "${'$'}PIDFILE" ] && kill -0 $(cat "${'$'}PIDFILE") 2>/dev/null; then
                            echo "proxy already running (pid $(cat ${'$'}PIDFILE))"
                            exit 0
                        fi
                        cat > "${'$'}CONF" << EOF
Port 18080
Listen 127.0.0.1
Timeout 30
Syslog Off
LogLevel Warning
PidFile ${'$'}PIDFILE
XTinyProxy Off
CacheDir ${'$'}{AIDEV_HOME}/proxy-cache
CacheSize 500
CacheMaxExpire 1440
EOF
                        tinyproxy -c "${'$'}CONF" && echo "proxy started (port 18080)" && \
                        echo "export GRADLE_OPTS=\"${'$'}GRADLE_OPTS -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080\"" || \
                        echo "proxy start failed" ;;
                    stop)
                        [ -f "${'$'}PIDFILE" ] && kill $(cat "${'$'}PIDFILE") 2>/dev/null && rm -f "${'$'}PIDFILE" && echo "proxy stopped" || echo "proxy not running" ;;
                    restart) "${'$'}0" stop && "${'$'}0" start ;;
                    status)
                        if [ -f "${'$'}PIDFILE" ] && kill -0 $(cat "${'$'}PIDFILE") 2>/dev/null; then
                            echo "proxy running (pid $(cat ${'$'}PIDFILE), port 18080)"
                            echo "cache: ${'$'}{AIDEV_HOME}/proxy-cache"
                            echo "usage: GRADLE_OPTS with proxy settings"
                        else
                            echo "proxy not running"
                        fi ;;
                    *) echo "Usage: aidev-proxy [start|stop|restart|status]" ;;
                esac
                """.trimIndent()
            else -> "#!/bin/sh\necho 'Unknown command: $name'\n"
        }
        script.writeText(content + "\n")
        script.setExecutable(true, false)
        script.setReadable(true, false)
    }

    private fun writeShellEntry(home: File, rc: File, entry: File) {
        val core = File(home, "dev-env/bin/aidev-ubuntu-core")
        val ready = File(home, "ubuntu-rootfs/.aidev-rootfs-ready")
        entry.writeText(
            """
            export ENV="${rc.absolutePath}"
            if [ -f "${ready.absolutePath}" ] && [ -f "${core.absolutePath}" ]; then
              /system/bin/sh "${core.absolutePath}" aidev-auto-bootstrap
            fi
            exec sh -i
            """.trimIndent() + "\n"
        )
    }

    /** ARM64 QEMU 下 aapt2 包装 / APK 拷贝 / 性能调优的 Gradle init 脚本内容。 */
    fun gradleInitScripts(): Map<String, String> = mapOf(
            "wrap-native.gradle" to """
                // AIDev: Auto-wrap aapt2 for ARM64 QEMU environment
                def arch = System.getProperty("os.arch", "")
                if (!arch.contains("aarch64")) return
                if (System.getProperty("android.aapt2DaemonMode") == null) {
                    System.setProperty("android.aapt2DaemonMode", "false")
                }
                def aapt2 = System.getProperty("android.aapt2FromMavenOverride")
                if (aapt2 == null) {
                    def sdkDir = System.getenv("ANDROID_SDK_ROOT") ?: "/Android"
                    def btDir = new File(sdkDir, "build-tools")
                    if (btDir.exists()) {
                        def dirs = btDir.listFiles().findAll { it.isDirectory() }.sort().reverse()
                        for (d in dirs) {
                            def f = new File(d, "aapt2")
                            if (f.canExecute()) {
                                System.setProperty("android.aapt2FromMavenOverride", f.absolutePath)
                                aapt2 = f.absolutePath
                                break
                            }
                        }
                    }
                }
                logger.lifecycle "AIDev: ARM64 QEMU wrapper" + (aapt2 ? " (aapt2=" + aapt2 + ")" : "")
            """.trimIndent(),
            "copy-apk.gradle" to """
                // AIDev: Copy built APKs to /sdcard/ for easy installation
                gradle.projectsLoaded {
                    rootProject.allprojects { p ->
                        p.afterEvaluate {
                            if (!p.plugins.hasPlugin("com.android.application")) return
                            p.android.applicationVariants.configureEach { variant ->
                                def caps = variant.name.substring(0,1).toUpperCase() + variant.name.substring(1)
                                def assemble = tasks.named("assemble" + caps)
                                assemble.configure {
                                    doLast {
                                        def apk = variant.outputs.first()?.outputFile
                                        if (apk != null && apk.exists()) {
                                            def target = new File("/sdcard/AIDev/", apk.name)
                                            target.parentFile.mkdirs()
                                            target.bytes = apk.bytes
                                            logger.lifecycle "AIDev: APK -> " + target
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent(),
            "performance.gradle" to """
                // AIDev: Gradle performance tuning
                def workers = Runtime.runtime.availableProcessors().toString()
                System.setProperty("org.gradle.workers.max", workers)
                gradle.projectsLoaded {
                    logger.lifecycle "AIDev: workers=" + workers
                }
            """.trimIndent()
        )

    /** 把 init 脚本写入指定 GRADLE_USER_HOME 的 init.d（Gradle 设置 GRADLE_USER_HOME 后只读此目录）。 */
    fun installGradleUserHomeInit(gradleUserHome: File) {
        val initDir = File(gradleUserHome, "init.d").apply { mkdirs() }
        gradleInitScripts().forEach { (name, content) ->
            File(initDir, name).writeText(content + "\n")
        }
    }

    private fun writeGradleInitScripts(home: File, rootfs: File) {
        val scripts = gradleInitScripts()

        val androidDir = File(home, "gradle-init.d").apply { mkdirs() }
        scripts.forEach { (name, content) ->
            File(androidDir, name).writeText(content + "\n")
        }

        // 关键：构建用 GRADLE_USER_HOME=home/gradle-cache，Gradle 只读其 init.d
        installGradleUserHomeInit(File(home, "gradle-cache"))

        if (rootfs.isDirectory) {
            val ubuntuDir = File(rootfs, "root/.gradle/init.d").apply { mkdirs() }
            scripts.forEach { (name, content) ->
                File(ubuntuDir, name).writeText(content + "\n")
            }
        }

        // 宇宙 B（编译器 rootfs）同样需要 Gradle 初始化脚本（aapt2 包装 + APK 拷贝）
        val compilerRootfs = File(home, "compiler_rootfs")
        if (compilerRootfs.isDirectory) {
            val compilerDir = File(compilerRootfs, "root/.gradle/init.d").apply { mkdirs() }
            scripts.forEach { (name, content) ->
                File(compilerDir, name).writeText(content + "\n")
            }
        }
    }
}
