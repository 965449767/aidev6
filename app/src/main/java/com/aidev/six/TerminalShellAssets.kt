package com.aidev.six

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import com.aidev.six.build.BuildPreflight
import com.aidev.six.monitor.SystemMetricsCollector
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
            activity,
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

        // rootfs 辅助脚本每次检查（rootfs 可能被重装），失败不致命
        if (rootfs.isDirectory) {
            runCatching { UbuntuBootstrapScripts.copyAssetScripts(activity, rootfs, home) }
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

    private fun installAidevCommandScripts(activity: Activity, home: File, nativeDir: String, prootExtraLibsPath: String) {
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        val core = File(bin, "aidev-ubuntu-core")
        core.writeText(UbuntuBootstrapScripts.aidevUbuntuCommandScript(home.absolutePath, nativeDir, prootExtraLibsPath))
        core.setReadable(true, false)
        listOf("ubuntu", "install-ubuntu", "aidev-auto-bootstrap", "aidev-doctor").forEach { name ->
            val out = File(bin, name)
            out.writeText("# AIDev command marker. Android 私有目录禁止直接执行脚本；实际入口由 .aidevrc 函数转发。\n")
            out.setReadable(true, false)
        }
        // 系统控制脚本（通知、截图、音量、亮度、剪贴板）
        writeSystemScript(bin, "sysnotify", "send notification")
        writeSystemScript(bin, "screencap", "take screenshot")
        writeSystemScript(bin, "volume", "control volume")
        writeSystemScript(bin, "brightness", "control brightness")
        writeSystemScript(bin, "sysclip", "clipboard get/set")
        writeSystemScript(bin, "aidev-proxy", "proxy manager")
        // 两端共用脚本（开发工具）——统一从 assets/scripts 读取，作为唯一真源，
        // 避免与 rootfs 内的副本（copyAssetScripts）双份维护、改 assets 不生效的问题。
        // 清单与 UbuntuBootstrapScripts.copyAssetScripts 保持一致（改脚本只改 assets/scripts/*.sh）。
        val agentScripts = listOf(
            "check-dev-env.sh", "repair-dev-env.sh", "setup-dev-env.sh",
            "aidev-logcat.sh", "aidev-shizuku.sh",
            "aidev-apk-info.sh", "aidev-build-request.sh", "aidev-build-log.sh",
            "aidev-verify-run.sh", "aidev-gen.sh",
            "aidev-error-why.sh", "aidev-index.sh", "aidev-autoinstall.sh", "android-sh.sh", "aidev-clean.sh",
            "aidev-backup.sh", "aidev-anr.sh", "aidev-tombstone.sh", "aidev-crash-why.sh", "aidev-dumpsys.sh",
            "create-compose-project.sh", "aidev-precache.sh", "aidev-repo.sh", "aidev-bridge.sh", "aidev-notify.sh"
        )
        for (script in agentScripts) {
            val dstName = script.removeSuffix(".sh")
            val dst = File(bin, dstName)
            try {
                runCatching { dst.setWritable(true) }
                activity.assets.open("scripts/$script").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true, false)
                dst.setReadable(true, false)
            } catch (e: Exception) {
                android.util.Log.w("TerminalShellAssets", "无法从 assets 复制脚本 $script: ${e.message}")
            }
        }
        // 公共 lib 目录：被 aidev-build-request / aidev-notify / aidev-anr / aidev-tombstone 等 source，
        // 必须与脚本同目录（/usr/local/bin/lib），否则这些命令会在 set -e 下崩溃（见脚本审计）。
        val libDir = File(bin, "lib").apply { mkdirs() }
        runCatching {
            val libEntries = activity.assets.list("scripts/lib") ?: emptyArray()
            for (entry in libEntries) {
                val dst = File(libDir, entry)
                activity.assets.open("scripts/lib/$entry").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true, false)
                dst.setReadable(true, false)
            }
        }.onFailure { e -> android.util.Log.w("TerminalShellAssets", "无法从 assets 复制 lib 目录: ${e.message}") }
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

    private fun writeCanonicalRc(activity: Activity, home: File, rc: File) {
        val nativeDir = activity.applicationInfo.nativeLibraryDir
        val prootExtraLibs = PathConfig.prootLibDir(activity).absolutePath
        val v = getVersionCode(activity)
        rc.writeText(
            """
            # AIDev canonical shell rc. 自动生成，请不要在这里保存个人配置。
            # source 期间关闭历史记录，避免下方大量函数定义/alias 被写入历史（否则方向键会翻出函数体）。
            set +o history 2>/dev/null || true
            AIDEV_VERSION="$v"
            # 宿主 AIDEV_HOME 为 App 私有绝对路径；在 proot 内(终端环境)宿主 home 绑定于 /host-home。
            # 本 rc 会被 rootfs 的 .bashrc 通过 `. /host-home/.aidevrc` 复用，故须按位置解析，避免命令指向不存在的宿主路径。
            if [ -d /host-home ]; then AIDEV_HOME="/host-home"; else AIDEV_HOME="${home.absolutePath}"; fi
            AIDEV_BIN="${'$'}AIDEV_HOME/dev-env/bin"
            # 用户覆盖层：自定义命令放此处优先于出厂脚本，且不被 copyAssetScripts 覆盖（见 docs/error-journal 2026-07-17）。
            AIDEV_OVERRIDES="${'$'}AIDEV_HOME/overrides/bin"
            AIDEV_ROOTFS="${'$'}AIDEV_HOME/ubuntu-rootfs"
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
            export AIDEV_VERSION AIDEV_HOME AIDEV_BIN AIDEV_OVERRIDES AIDEV_ROOTFS AIDEV_WORKSPACE AIDEV_NATIVE AIDEV_PROOT_LIBS AIDEV_PROOT_EXTRA_LIBS AIDEV_PROOT AIDEV_PROOT_LOADER PROOT_LOADER PROOT_TMP_DIR LD_LIBRARY_PATH
            export ANDROID_SDK_ROOT GRADLE_USER_HOME
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export PATH="${'$'}{AIDEV_OVERRIDES}:/usr/local/bin:${'$'}AIDEV_BIN:${'$'}ANDROID_SDK_ROOT/cmdline-tools/latest/bin:/system/bin:/system/xbin:${'$'}PATH"
            AIDEV_REALM="${'$'}{AIDEV_REALM:-H}"
            case "${'$'}AIDEV_REALM" in
              U) AIDEV_REALM_COLOR="$(printf '\033[32m')" ;;
              H|*) AIDEV_REALM_COLOR="$(printf '\033[36m')" ;;
            esac
            AIDEV_RESET="$(printf '\033[0m')"
            _aidev_ps1() {
              local _p="${'$'}{PWD/#${'$'}HOME/\~}"
              # 用 ANSI-C 引号 $'\001'/$'\002' 产生真实控制字节（mksh 的 printf '\001' 不折叠成字节，
              # 会原样打印成字面 001002）。0x01/0x02 是 readline 的忽略标记：包裹其间的 ANSI 颜色，
              # 行编辑器计为零宽，否则颜色转义被当可见字符算入 PS1 宽度 → 光标错位、命令视觉断开。
              local _i=$'\001'
              local _o=$'\002'
              PS1="${'$'}{_i}${'$'}{AIDEV_REALM_COLOR}${'$'}{_o}aidev[${'$'}{AIDEV_REALM:-H}]${'$'}{_i}${'$'}{AIDEV_RESET}${'$'}{_o}:${'$'}{_p}# "
              pwd > /host-home/.aidev-current-pwd 2>/dev/null || true
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
            compiler() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" ubuntu "${'$'}@"; }
            install-ubuntu() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" install-ubuntu "${'$'}@"; }
            install-compiler() { echo "编译器环境已统一到终端 rootfs，无需单独安装。"; }
            aidev-ensure-envs() { echo "环境已整合为单一 rootfs，无需额外操作。"; }
            aidev-auto-bootstrap() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-auto-bootstrap "${'$'}@"; }
            aidev-doctor() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-doctor "${'$'}@"; }
            setup-dev-env() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" setup-dev-env "${'$'}@"; }
            aidev-current-project() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-current-project" "${'$'}@"; }
            aidev-shizuku() { /system/bin/sh "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-shizuku" "${'$'}@"; }
            aidev-logcat() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-logcat "${'$'}@"; }
            aidev-apk-info() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-apk-info "${'$'}@"; }
            aidev-build-request() { /system/bin/sh "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-build-request" "${'$'}@"; }
            aidev-gen() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-gen "${'$'}@"; }
            aidev-error-why() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-error-why "${'$'}@"; }
            aidev-index() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-index "${'$'}@"; }
            android-sh() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" android-sh "${'$'}@"; }
            aidev-autoinstall() { /system/bin/sh "${'$'}AIDEV_BIN/aidev-ubuntu-core" aidev-autoinstall "${'$'}@"; }
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
            # 交互历史 / 行编辑：必须在所有函数定义之后开启，否则上方函数体被写入历史，
            # 方向键会翻出函数定义文本。source 开头已 set +o history 关闭记录，此处恢复。
            case "$-" in
              *i*)
                export HISTFILE="${'$'}{HOME:-/data/data/com.aidev.six/files/home}/.aidev_sh_history"
                export HISTSIZE=2000 HISTFILESIZE=2000
                # 清理历史文件中的函数定义/转义残片（旧版本把 rc 函数体写进了历史，需一次性净化）。
                # 注意：不得用 grep —— 宿主 /system/bin/grep 可能是坏架构二进制
                # （cannot execute: required file not found），每次 source 本 rc 都会报错。
                _aidev_purge=0
                if [ -f "${'$'}{HISTFILE:-}" ]; then
                  while IFS= read -r _aidev_line; do
                    case "${'$'}_aidev_line" in
                      *'() {'*|*'aidev[A]'*) _aidev_purge=1; break ;;
                    esac
                  done < "${'$'}{HISTFILE}"
                fi
                [ "${'$'}_aidev_purge" = 1 ] && : > "${'$'}{HISTFILE}" 2>/dev/null || true
                set -o emacs 2>/dev/null || true
                set -o history 2>/dev/null || true
                ;;
            esac
            """.trimIndent() + "\n"
        )
    }

    private fun writeSystemScript(binDir: File, name: String, desc: String) {
        val script = File(binDir, name)
        val content = systemScriptContent(name)
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
            # 交互历史 / 行编辑：env 由 exec 继承；rc 亦经 ENV 加载并在末尾开启 history。
            # 此处保持 history 关闭，避免 entry 固定脚本行进历史（rc 顶部已 set +o history）。
            set +o history 2>/dev/null || true
            export HISTFILE="${'$'}{HOME:-/data/data/com.aidev.six/files/home}/.aidev_sh_history"
            export HISTSIZE=2000 HISTFILESIZE=2000
            exec sh -i
            """.trimIndent() + "\n"
        )
    }

    /** ARM64 QEMU 下 aapt2 包装 / APK 拷贝 / 性能调优的 Gradle init 脚本内容。
     *  @param memAvailableMb 可用内存（MB）；低于看门狗阈值时下调 workers.max 以"时间换空间"。传 null 则按 CPU 核数。 */
    fun gradleInitScripts(memAvailableMb: Long? = null): Map<String, String> = mapOf(
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
                // workers 由宿主侧按可用内存动态计算（低内存时降级并行度，防 LMK 杀进程）
                def workers = "${computeWorkers(memAvailableMb)}".toString()
                System.setProperty("org.gradle.workers.max", workers)
                gradle.projectsLoaded {
                    logger.lifecycle "AIDev: workers=" + workers
                }
            """.trimIndent()
        )

    /** 把 init 脚本写入指定 GRADLE_USER_HOME 的 init.d（Gradle 设置 GRADLE_USER_HOME 后只读此目录）。
     *  动态按可用内存计算 workers.max（低内存降级并行度，防 LMK 杀进程）。 */
    fun installGradleUserHomeInit(gradleUserHome: File) {
        val memMb = runCatching { SystemMetricsCollector().getMemoryInfo().memAvailable / 1024 }.getOrNull()
        val initDir = File(gradleUserHome, "init.d").apply { mkdirs() }
        gradleInitScripts(memMb).forEach { (name, content) ->
            File(initDir, name).writeText(content + "\n")
        }
    }

    /** 按可用内存计算 Gradle workers 上限：低内存时降级为核数/2（最小 1）。 */
    private fun computeWorkers(memAvailableMb: Long?): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (memAvailableMb != null && memAvailableMb < BuildPreflight.MEM_WATCHDOG_THRESHOLD_MB) {
            return maxOf(1, cores / 2)
        }
        return cores
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
        // 注：编译器环境已统一到终端 rootfs，无需再单独安装
    }
}
