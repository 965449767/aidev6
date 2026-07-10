package com.aidev.six

import android.content.Context
import com.aidev.six.ShellResult
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 自我进化闭环的构建桥。
 *
 * OpenCode（宇宙 A）通过其工具 `aidev-build-request` 写入结构化请求文件：
 *   home/.aidev-build-bridge/req-<id>.json
 *   { "project": "<workspace 下相对路径或 /workspace/...>", "flavor": "debug",
 *     "autoInstall": true, "autoLaunch": true, "launchPackage": "<可选>" }
 *
 * 本服务轮询该目录，在【宇宙 B（compiler_rootfs）】内执行 `./gradlew assembleDebug`，
 * 编译产物经共享 workspace 零延迟映射到物理硬盘；成功后静默安装并拉起，
 * 最后写入 result-<id>.json 并通知宿主。
 */
object BuildBridgeService : BridgeService("BuildBridge") {

    private const val BRIDGE_DIR = ".aidev-build-bridge"

    private var requestDir: File? = null

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also {
            it.mkdirs()
            File(it, "logs").mkdirs()
        }
    }

    override fun poll() {
        val reqDir = requestDir ?: return
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            scope?.launch { handleRequest(claimed) }
        }
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("BuildBridge", "read request failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("BuildBridge", "parse json failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val ctx = appCtx ?: run { processingFile.delete(); return }
        val id = json.optString("id", processingFile.nameWithoutExtension)
        val project = json.optString("project", "MyAndroidProject").ifBlank { "MyAndroidProject" }
        val autoInstall = json.optBoolean("autoInstall", true)
        val autoLaunch = json.optBoolean("autoLaunch", true)

        val log = StringBuilder()
        val logFile = File(requestDir, "logs/build-$id.log")
        val append: (String) -> Unit = { line ->
            log.appendLine(line)
            runCatching { logFile.appendText("$line\n") }
        }

        append("=== BuildBridge 构建请求 $id (project=$project) ===")
        notify(ctx, "AIDev 构建", "开始编译 $project", priority = "default")

        try {
            // 1) 确保宇宙 B（编译器）就绪
            ensureCompilerRootfs(ctx, append)
            // 2) 解析项目路径（共享 workspace）
            val ws = PathConfig.workspaceDir(ctx)
            val projectDir = when {
                project.startsWith("/workspace/") -> File(ws, project.removePrefix("/workspace/").trimStart('/'))
                File(project).isAbsolute -> File(project)
                else -> File(ws, project.trimStart('/'))
            }
            val rel = projectDir.absolutePath.removePrefix(ws.absolutePath).trimStart('/')
            append("项目目录: ${projectDir.absolutePath}  (rel=/workspace/$rel)")

            if (!File(projectDir, "gradlew").isFile) {
                append("✗ 未找到 gradlew，请先用 aidev-create-android-project 创建项目")
                finish(ctx, id, false, "未找到 gradlew", log, processingFile)
                return
            }

            // 3) 在宇宙 B 内编译
            val compilerRootfs = PathConfig.compilerRootfs(ctx).absolutePath
            val bind = ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")
            append("→ 进入宇宙 B 编译: cd /workspace/$rel && ./gradlew assembleDebug")
            val process = ProotLauncher.start(
                ctx,
                "cd /workspace/$rel && chmod +x gradlew && ./gradlew assembleDebug --no-daemon",
                ProotLauncher.Options(
                    rootfs = compilerRootfs,
                    cwd = "/workspace/$rel",
                    binds = listOf(bind),
                    env = mapOf(
                        "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                        "GRADLE_USER_HOME" to "/host-home/gradle-cache"
                    ),
                    redirectErrorStream = true
                )
            )
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
            val exit = runCatching { process.waitFor(900, java.util.concurrent.TimeUnit.SECONDS) }
                .fold({ if (it) process.exitValue() else { process.destroyForcibly(); -1 } },
                    { process.destroyForcibly(); -1 })

            if (exit != 0) {
                append("✗ 构建失败 (exit=$exit)")
                finish(ctx, id, false, "构建失败 (exit=$exit)", log, processingFile)
                return
            }
            append("✓ 构建成功")

            // 4) 安装 + 拉起
            val apk = File(projectDir, "app/build/outputs/apk/debug/app-debug.apk")
            if (!apk.isFile) {
                append("✗ 未找到产物 APK: ${apk.absolutePath}")
                finish(ctx, id, true, "构建成功但缺少 APK", log, processingFile)
                return
            }
            if (autoInstall) {
                val pkg = installAndLaunch(ctx, apk, autoLaunch, append)
                finish(ctx, id, true, "构建成功并安装${if (autoLaunch && pkg != null) " (已拉起 $pkg)" else ""}", log, processingFile)
                // 自动触发崩溃回传：给刚启动的 App 几秒运行时间后抓取 logcat
                if (autoLaunch && pkg != null) {
                    scope?.launch {
                        kotlinx.coroutines.delay(8000)
                        runCatching {
                            val crDir = File(PathConfig.aidevHome(ctx), ".aidev-crash-bridge").apply { mkdirs() }
                            val cr = JSONObject().apply { put("package", pkg); put("lines", 1000) }
                            File(crDir, "req-${System.currentTimeMillis()}.json").writeText(cr.toString())
                        }
                    }
                }
            } else {
                finish(ctx, id, true, "构建成功", log, processingFile)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            append("⏹ 已取消")
            finish(ctx, id, false, "已取消", log, processingFile)
        } catch (e: Exception) {
            append("✗ ${e.message}")
            finish(ctx, id, false, e.message ?: "异常", log, processingFile)
        }
    }

    private fun ensureCompilerRootfs(ctx: Context, append: (String) -> Unit) {
        val home = PathConfig.aidevHome(ctx)
        val compilerRootfs = PathConfig.compilerRootfs(ctx)
        if (File(compilerRootfs, ".aidev-rootfs-ready").isFile &&
            File(compilerRootfs, "usr/bin/bash").isFile
        ) {
            append("宇宙 B 已就绪: ${compilerRootfs.absolutePath}")
            return
        }
        append("→ 准备宇宙 B（编译器 rootfs）...")
        val core = File(home, "dev-env/bin/aidev-ubuntu-core").absolutePath
        val agentRootfs = PathConfig.agentRootfs(ctx).absolutePath
        val res: ShellResult = ProotLauncher.run(
            ctx, "$core install-compiler --fast",
            ProotLauncher.Options(rootfs = agentRootfs, timeoutSec = 600)
        )
        append(res.stdout.takeIf { it.isNotBlank() } ?: res.stderr)
        if (!res.isSuccess) {
            append("⚠ 宇宙 B 初始化返回非零，编译可能失败")
        }
        // 确保宇宙 B 内已装 JDK 17（gradlew 编译必需，必须是 glibc 版，不能用宿主 bionic JDK）
        val wsBind = ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")
        val javaCheck = ProotLauncher.run(
            ctx,
            "command -v java >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq openjdk-17-jdk)",
            ProotLauncher.Options(
                rootfs = compilerRootfs.absolutePath,
                cwd = "/root",
                binds = listOf(wsBind),
                env = mapOf(
                    "ANDROID_SDK_ROOT" to "/host-home/android-sdk",
                    "GRADLE_USER_HOME" to "/host-home/gradle-cache"
                ),
                timeoutSec = 600,
                redirectErrorStream = true
            )
        )
        append("→ 检查/安装宇宙 B JDK17: ${if (javaCheck.isSuccess) "OK" else "返回非零(可重试)"}")
    }

    private fun installAndLaunch(ctx: Context, apk: File, autoLaunch: Boolean, append: (String) -> Unit): String? {
        val sd = File("/sdcard/AIDev").apply { mkdirs() }
        val dst = File(sd, apk.name)
        runCatching { apk.copyTo(dst, overwrite = true) }.onFailure {
            append("✗ 复制 APK 到 /sdcard 失败: ${it.message}")
            return null
        }
        val state = ShizukuLogcat.checkState(ctx)
        if (state !is ShizukuState.Ready) {
            append("⚠ Shizuku 未就绪（${state.javaClass.simpleName}），跳过安装。APK 已位于 ${dst.absolutePath}")
            return null
        }
        val tmp = "/data/local/tmp/aidev-install-${Math.abs(dst.name.hashCode())}.apk"
        ShizukuLogcat.executeFireAndForget(
            "cp '${dst.absolutePath}' '$tmp' && pm install -r -d '$tmp' ; rm -f '$tmp'"
        )
        append("→ 已通过 Shizuku 安装 ${dst.name}")
        val pkg = runCatching { ctx.packageManager.getPackageArchiveInfo(dst.absolutePath, 0)?.packageName }.getOrNull()
        if (autoLaunch && pkg != null) {
            ShizukuLogcat.executeFireAndForget(
                "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
            )
            append("→ 已拉起 $pkg")
        }
        return pkg
    }

    private fun finish(ctx: Context, id: String, success: Boolean, message: String, log: StringBuilder, reqFile: File) {
        val result = JSONObject().apply {
            put("id", id)
            put("success", success)
            put("message", message)
            put("time", System.currentTimeMillis())
        }
        runCatching {
            File(requestDir, "result-$id.json").writeText(result.toString(2))
        }
        reqFile.delete()
        notify(ctx, if (success) "AIDev 构建完成" else "AIDev 构建失败", message, priority = "high")
        AIDevLogger.i("BuildBridge", "request $id done success=$success msg=$message")
    }

    private fun notify(ctx: Context, title: String, msg: String, priority: String) {
        runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
    }
}
