package com.aidev.six

import android.content.Context
import com.aidev.six.terminal.ProotLauncher
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 编译环境初始化：宇宙 B rootfs、JDK、aapt2（x86_64/qemu）、APK 包名提取。
 */
internal object BuildEnvironmentSetup {

    private const val JDK_SHA256 = "83a52172678ec8975164648654869cb2e71d7c748b47aca94b29bbfa10c18e81"

    fun ensureCompilerRootfs(
        ctx: Context,
        id: String,
        append: (String) -> Unit,
        activeProcesses: ConcurrentHashMap<String, Process>,
        cancelledIds: MutableSet<String>
    ) {
        val compilerRootfs = PathConfig.compilerRootfs(ctx)
        if (File(compilerRootfs, ".aidev-rootfs-ready").isFile &&
            File(compilerRootfs, "usr/bin/bash").isFile
        ) {
            append("宇宙 B 已就绪: ${compilerRootfs.absolutePath}")
            ensureJdk(ctx, id, append, activeProcesses, cancelledIds)
            return
        }
        append("→ 准备宇宙 B（编译器 rootfs）...")

        val installExit = BuildExecutor.runStreaming(
            ctx,
            id,
            "AIDEV_HOME=/host-home " +
                "AIDEV_ROOTFS=/host-home/compiler_rootfs " +
                "AIDEV_PROOT=/bin/true " +
                "sh /host-home/dev-env/bin/aidev-ubuntu-core install-ubuntu",
            ProotLauncher.Options(
                rootfs = compilerRootfs.absolutePath,
                timeoutSec = 600,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "准备宇宙 B 基础系统（首次下载解压 Ubuntu base）",
            activeProcesses = activeProcesses,
            cancelledIds = cancelledIds
        )
        if (installExit != 0) {
            append("⚠ 宇宙 B Ubuntu 基础环境安装失败(exit=$installExit)，编译可能失败")
        }

        ensureJdk(ctx, id, append, activeProcesses, cancelledIds)
    }

    /** 从 assets 拷贝单个文件到目标（不存在或空才拷），可执行。 */
    private fun copyAsset(ctx: Context, assetPath: String, dst: File, exec: Boolean) {
        if (dst.isFile && dst.length() > 0L) { if (exec) dst.setExecutable(true, false); return }
        dst.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        if (exec) dst.setExecutable(true, false)
    }

    /**
     * 部署自带的 x86_64 aapt2 运行环境（qemu + aapt2.real + loader + glibc），生成包装脚本，
     * 返回其在 universe B 内的路径 (/host-home/x86_64/aapt2)。失败返回 null。幂等。
     */
    fun ensureX86Aapt2(ctx: Context): String? {
        return runCatching {
            val home = PathConfig.aidevHome(ctx)
            copyAsset(ctx, "tools/qemu-x86_64-static", File(home, "qemu-x86_64-static"), exec = true)
            val x86 = File(home, "x86_64")
            copyAsset(ctx, "tools/x86_64/aapt2.real", File(x86, "aapt2.real"), exec = true)
            val libs = listOf(
                "ld-linux-x86-64.so.2", "libc.so.6", "libm.so.6", "libdl.so.2",
                "libpthread.so.0", "librt.so.1", "libgcc_s.so.1", "libstdc++.so.6", "libz.so.1"
            )
            for (l in libs) copyAsset(ctx, "tools/x86_64/lib/$l", File(x86, "lib/$l"), exec = true)
            val wrapper = File(x86, "aapt2")
            wrapper.writeText(
                "#!/bin/sh\n" +
                "DIR=/host-home/x86_64\n" +
                "export QEMU_LD_PREFIX=\$DIR\n" +
                "exec /host-home/qemu-x86_64-static \$DIR/lib/ld-linux-x86-64.so.2 --library-path \$DIR/lib \$DIR/aapt2.real \"\$@\"\n"
            )
            wrapper.setExecutable(true, false)
            "/host-home/x86_64/aapt2"
        }.getOrNull()
    }

    /**
     * 从编译产物 APK 解析包名（applicationId），供部署黑盒与面板「安装/拉起」按钮直接使用。
     * 用 aidev-deploy 同源的 aapt2（x86_64/qemu）dump badging；失败返回空串（部署按钮将提示先成功构建）。
     */
    fun extractPackage(ctx: Context, apk: String, append: (String) -> Unit): String {
        val aapt2 = ensureX86Aapt2(ctx) ?: return ""
        val ws = PathConfig.workspaceDir(ctx)
        val prootApk = if (apk.startsWith(ws.absolutePath)) {
            "/workspace" + apk.removePrefix(ws.absolutePath).replace('\\', '/')
        } else apk
        val cmd = "$aapt2 d badging '$prootApk' 2>/dev/null"
        val out = runCatching {
            ProotLauncher.run(
                ctx, cmd,
                ProotLauncher.Options(
                    rootfs = PathConfig.compilerRootfs(ctx).absolutePath,
                    cwd = "/workspace",
                    binds = listOf(ProotLauncher.ProotBind(ws.absolutePath, "/workspace")),
                    env = mapOf("ANDROID_SDK_ROOT" to "/host-home/android-sdk"),
                    timeoutSec = 120,
                    redirectErrorStream = true
                )
            )
        }.getOrNull() ?: return ""
        return Regex("package: name='([^']+)'").find(out.stdout)?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * 确保宇宙 B 内 java 可用。采用便携版 JDK tarball（自带完整 cacerts），
     * 免 apt/dpkg/debconf——本机 rootfs 的 dpkg/debconf 环境损坏，apt 装 openjdk 的 postinst 必崩。
     * JDK 解压到共享持久目录 /host-home/jdk17，跨重建复用。幂等。
     */
    fun ensureJdk(ctx: Context, id: String, append: (String) -> Unit, activeProcesses: ConcurrentHashMap<String, Process>, cancelledIds: MutableSet<String>) {
        val compilerRootfs = PathConfig.compilerRootfs(ctx)
        val wsBind = ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")
        append("→ 检查/安装宇宙 B JDK17（便携版，免 apt）...")

        runCatching {
            val curlDst = File(compilerRootfs, "usr/local/bin/curl")
            if (!curlDst.isFile || curlDst.length() == 0L) {
                curlDst.parentFile?.mkdirs()
                ctx.assets.open("tools/curl").use { i -> curlDst.outputStream().use { o -> i.copyTo(o) } }
                curlDst.setExecutable(true)
            }
            val caDst = File(compilerRootfs, "etc/ssl/certs/ca-certificates.crt")
            if (!caDst.isFile) {
                caDst.parentFile?.mkdirs()
                ctx.assets.open("tools/ca-certificates.crt").use { i -> caDst.outputStream().use { o -> i.copyTo(o) } }
            }
        }.onFailure { AIDevLogger.e("BuildBridge", "部署 curl/ca 到宇宙 B 失败", it) }

        val script = """
            set -u
            JDK_DIR=/host-home/jdk17
            if [ -x "${'$'}JDK_DIR/bin/java" ]; then ln -sf "${'$'}JDK_DIR/bin/java" /usr/bin/java 2>/dev/null || true; "${'$'}JDK_DIR/bin/java" -version 2>&1 | head -1; exit 0; fi
            if command -v java >/dev/null 2>&1; then java -version 2>&1 | head -1; exit 0; fi
            export CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
            CURL=/usr/local/bin/curl
            [ -x "${'$'}CURL" ] || CURL=curl
            EXPECTED_SHA="${JDK_SHA256}"
            JDK_FILE=/host-home/jdk17.tar.gz
            REPO_JDK_DEC=$(aidev-repo decide android-jdk17 2>/dev/null || true)
            case "${'$'}REPO_JDK_DEC" in
              repo:*)
                REPO_JDK=${'$'}{REPO_JDK_DEC#repo:}
                echo "→ 使用离线仓库 JDK: ${'$'}REPO_JDK"
                cp "${'$'}REPO_JDK" "${'$'}JDK_FILE"
                ;;
              network)
                echo "ℹ️ 离线仓库无 JDK，回退网络下载（可在 AIDevRepo 缓存 android-jdk17 以离线）"
                ;;
              deny)
                echo "❌ 离线优先模式：仓库无 JDK，已禁止网络下载。请先在 AIDevRepo 缓存该资源，或把「离线优先」开关关闭。"
                exit 1
                ;;
            esac
            if [ ! -f "${'$'}JDK_FILE" ]; then
            MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz https://mirrors.ustc.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.19_10.tar.gz"
            DOWNLOADED=0
            for url in ${'$'}MIRRORS; do
                echo "尝试镜像: ${'$'}url"
                if "${'$'}CURL" -fL --retry 3 --connect-timeout 15 --max-time 900 -o "${'$'}JDK_FILE" "${'$'}url" 2>/dev/null; then
                    ACTUAL_SHA=""
                    if command -v sha256sum >/dev/null 2>&1; then
                        ACTUAL_SHA=$(sha256sum "${'$'}JDK_FILE" | cut -d' ' -f1)
                    elif command -v openssl >/dev/null 2>&1; then
                        ACTUAL_SHA=$(openssl dgst -sha256 "${'$'}JDK_FILE" | awk '{print ${'$'}NF}')
                    fi
                    if [ -n "${'$'}EXPECTED_SHA" ] && [ -n "${'$'}ACTUAL_SHA" ] && [ "${'$'}ACTUAL_SHA" != "${'$'}EXPECTED_SHA" ]; then
                        echo "⚠️ SHA256 校验失败: 期望 ${'$'}EXPECTED_SHA, 实际 ${'$'}ACTUAL_SHA，尝试下一个镜像"
                        rm -f "${'$'}JDK_FILE"
                        continue
                    fi
                    if [ -n "${'$'}ACTUAL_SHA" ]; then echo "✅ SHA256 校验通过"; fi
                    DOWNLOADED=1
                    break
                else
                    echo "✗ 镜像不可用: ${'$'}url"
                fi
            done
            if [ "${'$'}DOWNLOADED" -ne 1 ]; then
                echo "✗ 所有镜像均下载失败，请检查网络后重试"
                exit 1
            fi
            else
                echo "✅ 已获得 JDK 包（仓库提供），跳过下载"
            fi
            echo "解压 JDK..."
            rm -rf "${'$'}JDK_DIR"; mkdir -p "${'$'}JDK_DIR"
            tar -xzf "${'$'}JDK_FILE" -C "${'$'}JDK_DIR" --strip-components=1
            rm -f "${'$'}JDK_FILE"
            mkdir -p /usr/bin
            ln -sf "${'$'}JDK_DIR/bin/java" /usr/bin/java
            ln -sf "${'$'}JDK_DIR/bin/javac" /usr/bin/javac
            "${'$'}JDK_DIR/bin/java" -version 2>&1 | head -1
        """.trimIndent()
        val javaExit = BuildExecutor.runStreaming(
            ctx,
            id,
            script,
            ProotLauncher.Options(
                rootfs = compilerRootfs.absolutePath,
                cwd = "/root",
                binds = listOf(wsBind),
                env = mapOf(
                    "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache",
                    "AIDEV_REPO_MODE" to com.aidev.six.PreferencesManager(ctx).repoMode
                ),
                timeoutSec = 1800,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "下载/解压便携 JDK17",
            activeProcesses = activeProcesses,
            cancelledIds = cancelledIds
        )
        append("→ 宇宙 B JDK17: ${if (javaExit == 0) "OK" else "返回非零(可重试)"}")
    }
}
