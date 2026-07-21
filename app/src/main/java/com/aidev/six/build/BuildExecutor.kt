package com.aidev.six.build

import android.content.Context
import com.aidev.six.monitor.SystemMetricsCollector
import com.aidev.six.task.BuildProgress.Phase
import com.aidev.six.terminal.ProotLauncher
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 编译执行器：项目编译流程、PRoot 流式执行、预构建体检。
 * 所有编译统一在终端环境（ubuntu-rootfs）内执行，不再区分独立编译器宇宙。
 */
internal object BuildExecutor {

    /**
     * 流式执行 PRoot 命令：stdout 逐行写入 [append]（UI 通过 tail 日志实时看到进度）；
     * 若输出静默超过 15s，打印一条"仍在进行"心跳，防止用户误判卡死。
     * @return 退出码；超时或异常返回 -1。
     */
    fun runStreaming(
        ctx: Context,
        id: String,
        command: String,
        opts: ProotLauncher.Options,
        append: (String) -> Unit,
        heartbeat: String,
        activeProcesses: ConcurrentHashMap<String, Process>,
        cancelledIds: MutableSet<String>
    ): Int {
        val process = ProotLauncher.start(ctx, command, opts.copy(redirectErrorStream = true))
        activeProcesses[id] = process
        val running = AtomicBoolean(true)
        val lastOutput = AtomicLong(System.currentTimeMillis())
        val startedAt = System.currentTimeMillis()
        val hb = Thread {
            while (running.get()) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                if (!running.get()) break
                if (System.currentTimeMillis() - lastOutput.get() >= 15000) {
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    append("… $heartbeat（已用时 ${sec}s，仍在进行）")
                    lastOutput.set(System.currentTimeMillis())
                }
            }
        }.apply { isDaemon = true; start() }
        val exit = try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    lastOutput.set(System.currentTimeMillis())
                    line = reader.readLine()
                }
            }
            val exited = runCatching { process.waitFor(opts.timeoutSec, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)
            if (exited) process.exitValue() else { process.destroyForcibly(); -1 }
        } catch (e: Exception) {
            append("✗ 执行异常: ${e.message}")
            process.destroyForcibly(); -1
        } finally {
            running.set(false)
            hb.interrupt()
            activeProcesses.remove(id, process)
        }
        // 被取消：进程已被强杀（上面读取到 EOF/异常返回），抛出以让 handleRequest 收敛为「已取消」。
        if (id in cancelledIds) throw kotlinx.coroutines.CancellationException("已取消")
        return exit
    }

    /**
     * 执行完整编译流程：环境准备 → 项目补全 → 预热缓存 → Gradle assembleDebug。
     */
    suspend fun compileProject(
        ctx: Context,
        id: String,
        bc: BuildContext,
        activeProcesses: ConcurrentHashMap<String, Process>,
        cancelledIds: MutableSet<String>
    ): CompileResult {
        val projectDir = bc.projectDir
        val rel = bc.rel
        val ws = bc.ws
        val append = bc::append

        // 构建期间临时持唤醒锁，避免编译中途被系统休眠打断；结束即释放（见 KeepAliveService 按需持锁）。
        KeepAliveService.acquireBuildLocks(ctx)
        try {
        bc.timer.beginStep("准备编译环境")
        BuildEnvironmentSetup.ensureCompilerRootfs(ctx, id, append, activeProcesses, cancelledIds)
        bc.timer.endStep("rootfs + JDK + aapt2")

        if (!File(projectDir, "gradlew").isFile) {
            append("→ 未找到 gradlew，自动创建项目模板...")
            BuildProjectScaffolder.scaffoldProject(projectDir, ctx)
            if (!File(projectDir, "gradlew").isFile) {
                append("✗ 自动创建项目失败")
                return CompileResult(false, message = "自动创建项目失败")
            }
            append("✓ 项目模板已创建")
        }

        if (!File(projectDir, "gradle/wrapper/gradle-wrapper.jar").let { it.isFile && it.length() > 0 }) {
            append("→ 缺少 gradle-wrapper.jar，从内置资源补齐...")
            if (BuildProjectScaffolder.ensureWrapperJar(projectDir, ctx)) append("✓ gradle-wrapper.jar 已补齐")
            else append("⚠ gradle-wrapper.jar 补齐失败")
        }

        runCatching {
            val gradlewDest = File(projectDir, "gradlew")
            ctx.assets.open("scripts/gradlew").use { input ->
                gradlewDest.outputStream().use { output -> input.copyTo(output) }
            }
            gradlewDest.setExecutable(true)
        }.onFailure { AIDevLogger.e("BuildBridge", "刷新 gradlew 失败", it) }
        runCatching { BuildProjectScaffolder.writeSettingsGradle(projectDir) }
            .onFailure { AIDevLogger.e("BuildBridge", "刷新 settings.gradle.kts 失败", it) }
        runCatching { TerminalShellAssets.installGradleUserHomeInit(File(PathConfig.aidevHome(ctx), "gradle-cache")) }
            .onFailure { AIDevLogger.e("BuildBridge", "安装 GRADLE_USER_HOME init.d 失败", it) }

        runCatching { preflightCheck(projectDir, append) }
            .onFailure { AIDevLogger.e("BuildBridge", "预构建体检失败(非致命)", it) }

        val memMb = runCatching { SystemMetricsCollector().getMemoryInfo().memAvailable / 1024 }.getOrNull()
        val pre = BuildPreflight.checkPreconditions(projectDir, memMb)
        pre.warnings.forEach(append)
        if (pre.hardErrors.isNotEmpty()) {
            pre.hardErrors.forEach(append)
            return CompileResult(false, message = "构建前护栏拦截：${pre.hardErrors.first().removePrefix("✖ ")}")
        }

        val compilerRootfs = PathConfig.compilerRootfs(ctx).absolutePath
        val bind = ProotLauncher.ProotBind(ws.absolutePath, "/workspace")
        val aapt2Override = BuildEnvironmentSetup.ensureX86Aapt2(ctx)
        val aapt2Arg = aapt2Override?.let { " -Pandroid.aapt2FromMavenOverride=$it" } ?: ""
        append(if (aapt2Override != null) "→ aapt2(x86_64/qemu) 就绪: $aapt2Override" else "⚠ aapt2 部署失败")

        val ubMarker = File(PathConfig.aidevHome(ctx), "gradle-cache/caches/modules-2/files-2.1/androidx.compose.material/material-icons-extended")
        if (!ubMarker.isDirectory) {
            if (isOnline()) {
                append("→ 预热依赖缓存（首次在线构建）...")
                runStreaming(
                    ctx, id,
                    "cd /workspace/$rel && ./gradlew dependencies --no-daemon$aapt2Arg",
                    ProotLauncher.Options(
                        rootfs = compilerRootfs,
                        cwd = "/workspace/$rel",
                        binds = listOf(bind),
                        env = mapOf(
                            "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                            "GRADLE_USER_HOME" to "/host-home/gradle-cache",
                            "JAVA_HOME" to "/host-home/jdk17",
                            "PATH" to "/host-home/jdk17/bin:/host-home/dev-env/bin:/system/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                        ),
                        timeoutSec = 600,
                        redirectErrorStream = true
                    ),
                    append,
                    heartbeat = "预热依赖缓存",
                    activeProcesses = activeProcesses,
                    cancelledIds = cancelledIds
                )
                append("→ 依赖缓存预热完成（断网可离线构建）")
            } else {
                append("⚠ 依赖缓存缺失且当前离线；若构建报缺包，请联网后运行 aidev-precache 再构建。")
            }
        }

        if (aapt2Override != null) {
            append("→ 探测 aapt2 version ...")
            runStreaming(
                ctx, id,
                "$aapt2Override version 2>&1; echo \"AAPT2_PROBE_EXIT=\$?\"",
                ProotLauncher.Options(
                    rootfs = PathConfig.compilerRootfs(ctx).absolutePath,
                    cwd = "/root",
                    binds = listOf(ProotLauncher.ProotBind(ws.absolutePath, "/workspace")),
                    env = mapOf("ANDROID_SDK_ROOT" to "/host-home/android-sdk"),
                    timeoutSec = 120,
                    redirectErrorStream = true
                ),
                append,
                heartbeat = "探测 aapt2",
                activeProcesses = activeProcesses,
                cancelledIds = cancelledIds
            )
        }

        bc.currentPhase = Phase.COMPILE
        val buildDir = File(projectDir, "app/build")
        val buildType = if (buildDir.isDirectory && buildDir.listFiles()?.isNotEmpty() == true) "增量编译" else "全量编译"
        bc.timer.beginStep("编译 ($buildType)")
        append("→ 进入编译（$buildType）: cd /workspace/$rel && ./gradlew assembleDebug")
        val exit = runStreaming(
            ctx, id,
            "cd /workspace/$rel && chmod +x gradlew && ./gradlew assembleDebug --no-daemon$aapt2Arg",
            ProotLauncher.Options(
                rootfs = compilerRootfs,
                cwd = "/workspace/$rel",
                binds = listOf(bind),
                env = mapOf(
                    "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache",
                    "JAVA_HOME" to "/host-home/jdk17",
                    "PATH" to "/host-home/jdk17/bin:/host-home/dev-env/bin:/system/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                ),
                timeoutSec = 900,
                redirectErrorStream = true
            ),
            append,
            heartbeat = "编译中（首次会下载 Gradle 分发与依赖）",
            activeProcesses = activeProcesses,
            cancelledIds = cancelledIds
        )

        val buildFailed = exit != 0 || bc.log.contains("BUILD FAILED")
        if (buildFailed) {
            bc.timer.endStep("编译失败 (exit=$exit)")
            append("✗ 构建失败 (exit=$exit)")
            val hints = BuildDiagnostics.diagnoseBuildErrors(bc.log.toString())
            hints.forEach { append("💡 $it") }
            return CompileResult(false, exitCode = exit, message = "构建失败 (exit=$exit)")
        }
        bc.timer.endStep("编译成功")
        return CompileResult(true, exitCode = exit)
        } finally {
            KeepAliveService.releaseBuildLocks()
        }
    }

    internal fun isOnline(): Boolean = try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName("maven.aliyun.com"), 443), 1500)
        socket.close()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * 预构建体检：编译前扫描 app/build.gradle.kts，提示几类必失败的错误写法。
     * 全部尽力而为，失败不阻断构建；提示以中文写入构建日志，小白可读。
     */
    internal fun preflightCheck(projectDir: File, append: (String) -> Unit) {
        val gradleFile = File(projectDir, "app/build.gradle.kts")
        if (!gradleFile.isFile) return
        val original = runCatching { gradleFile.readText() }.getOrNull() ?: return
        val rootGradle = runCatching { File(projectDir, "build.gradle.kts").readText() }.getOrDefault("")
        // 仅体检 + 提示，绝不自动改写用户工程文件（人类自行决定是否修复）。
        val result = BuildPreflight.inspect(original, rootGradle)
        result.messages.forEach(append)
        // 源码预检：import 引用 + Manifest 组件声明
        val sourceMessages = runCatching { BuildPreflight.inspectSourceCode(projectDir) }.getOrDefault(emptyList())
        sourceMessages.forEach(append)
    }
}
