package com.aidev.six.bridge

import android.content.Context
import com.aidev.six.AIDevCommandDispatcher
import com.aidev.six.AIDevLogger
import com.aidev.six.PathConfig
import com.aidev.six.task.TaskDefinition
import com.aidev.six.task.TaskRecord
import com.aidev.six.task.TaskStatus
import com.aidev.six.task.TaskStepResult
import com.aidev.six.task.TaskStore
import com.aidev.six.terminal.ProotLauncher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 部署桥（黑盒2 落地）。
 *
 * 「服务器中心」面板的「安装 / 拉起」按钮，以及终端的 `aidev-autoinstall --launch` 命令，
 * 都走同一套标准入口/出口，保证服务一致性：
 *
 *   标准入口: 写入 home/.aidev-deploy-bridge/req-<id>.json
 *     { "id", "apk": "<宿主绝对路径>", "pkg": "<包名>", "launch": true/false }
 *   本服务轮询该目录，在【Ubuntu rootfs】内执行 `aidev-autoinstall --json [--launch ...] ...`，
 *   解析其标准出口 JSON（{installed,launched,activity,error}），把进度/结果作为单一真源写入
 *   task-records.json（AF 面板轮询即看到一致过程），并写回 result-<id>.json。
 */
object DeployBridgeService : BridgeService("DeployBridge") {

    private const val BRIDGE_DIR = ".aidev-deploy-bridge"

    @Volatile private var requestDir: File? = null
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val cancelledIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun cancel(id: String) {
        cancelledIds.add(id)
        activeProcesses.remove(id)?.let { runCatching { it.destroyForcibly() } }
        val reqDir = requestDir ?: return
        runCatching {
            reqDir.listFiles { _, n -> n.startsWith("req-$id.json") }?.forEach { it.delete() }
            if (!File(reqDir, "result-$id.json").isFile) {
                File(reqDir, "result-$id.json").writeText(
                    JSONObject().apply {
                        put("id", id); put("success", false); put("message", "已取消")
                        put("time", System.currentTimeMillis())
                    }.toString(2)
                )
            }
        }
    }

    override fun onStart(homeDir: File) {
        requestDir = File(homeDir, BRIDGE_DIR).also {
            it.mkdirs()
            it.listFiles { f -> f.name.endsWith(".json.processing") }?.forEach { orphan ->
                runCatching { orphan.delete() }
            }
        }
        // 安装脚本由 assets 落地到 dev-env/bin（PRoot 内 /host-home/dev-env/bin），
        // 保证不打开终端也能 headless 调用（与 BuildBridgeService 自带 gradlew 同理）。
        runCatching { ensureDeployScripts(homeDir) }
            .onFailure { AIDevLogger.e("DeployBridge", "ensureDeployScripts failed", it) }
    }

    override fun poll(): Boolean {
        val reqDir = requestDir ?: return false
        var hadWork = false
        reqDir.listFiles()?.filter {
            it.name.endsWith(".json") && !it.name.endsWith(".processing") && !it.name.startsWith("result-")
        }?.forEach { file ->
            val claimed = claimFile(reqDir, file) ?: return@forEach
            hadWork = true
            scope?.launch { handleRequest(claimed) }
        }
        return hadWork
    }

    override val bridgeName: String get() = "deploy"

    /**
     * Socket 通道入口：payload 承载原 req JSON（含 id/apk/pkg/launch）。落盘 `req-<id>.json`，
     * 交给既有 poll→handleRequest→cancel 流程，立即返回 "accepted" 确认帧。零改动既有重逻辑。
     */
    override fun dispatch(frame: BridgeFrame): BridgeFrame? {
        val dir = requestDir
        if (dir == null) {
            AIDevLogger.w("DeployBridge", "dispatch: 桥未启动")
            return BridgeFrame("deploy", frame.id, "ERROR: 桥未就绪")
        }
        runCatching { File(dir, "req-${frame.id}.json").writeText(frame.payload) }
            .onFailure { AIDevLogger.w("DeployBridge", "dispatch 入队失败", it) }
        return BridgeFrame("deploy", frame.id, "accepted")
    }

    private fun ensureDeployScripts(home: File) {
        // 部署脚本由 bundled assets 兜底落地到 dev-env/bin（PRoot 内 /host-home/dev-env/bin）。
        // 为使脚本修复（如新增重试）能自动生效，这里以 bundled 版本为准：
        //   脚本 MD5 与 bundled 一致 → 仅确保 .md5 存在；
        //   不一致（缺失/被旧副本覆盖/自演进投递旧版）→ 用 bundled 覆盖并刷新 .md5，
        //   保证 validateDeployScript 永不因「脚本更新但 .md5 过期」而误拦。
        // 共享 home 与 PRoot 内 AIDEV_HOME 一致（均为 /host-home），避免 Android 侧真实
        // files/home 与绑定视图不一致时校验/执行错位。
        val ctx = appCtx ?: return
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        val assets = mapOf(
            "aidev-autoinstall.sh" to "aidev-autoinstall",
            "aidev-shizuku.sh" to "aidev-shizuku",
            "aidev-verify-run.sh" to "aidev-verify-run"
        )
        assets.forEach { (asset, name) ->
            val dst = File(bin, name)
            val md5File = File(bin, "$name.md5")
            val assetMd5 = md5OfAsset(ctx, asset)
            if (assetMd5.isEmpty()) return@forEach
            val installedMd5 = if (dst.exists()) md5Of(dst) else ""
            if (dst.exists() && installedMd5 == assetMd5) {
                if (!md5File.exists()) md5File.writeText(assetMd5)
                return@forEach
            }
            runCatching {
                ctx.assets.open("scripts/$asset").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true, false)
                md5File.writeText(assetMd5)
                AIDevLogger.i("DeployBridge", "更新部署脚本 $name (md5=$assetMd5)")
            }.onFailure { AIDevLogger.e("DeployBridge", "deploy script $name failed", it) }
        }
    }

    private fun md5OfAsset(ctx: Context, asset: String): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            ctx.assets.open("scripts/$asset").use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    /**
     * 安装脚本校验（MD5 握手 + Fail-Fast）：
     * 1) 脚本与 .md5 校验文件必须同时存在；
     * 2) 脚本实际 MD5 必须与 .md5 内容一致（拒绝旧/损坏/被截断的副本）。
     * 校验路径与 PRoot 内 AIDEV_HOME（/host-home）一致，规避 Android 侧真实 files/home
     * 与绑定视图不一致的错位问题。
     * 返回 null 表示校验通过；否则返回应直接回报给闭环的错误信息。
     */
    private fun validateDeployScript(home: File): String? {
        val bin = File(home, "dev-env/bin")
        val script = File(bin, "aidev-autoinstall")
        val md5File = File(bin, "aidev-autoinstall.md5")
        if (!script.exists() || !md5File.exists()) {
            return "安装脚本或校验文件缺失"
        }
        val expect = runCatching { md5File.readText().trim().split("\\s+".toRegex())[0] }.getOrNull() ?: ""
        val actual = md5Of(script)
        if (expect.isEmpty() || actual.isEmpty() || expect != actual) {
            return "安装脚本 MD5 不匹配（期望=$expect 实际=$actual），拒绝执行旧/损坏脚本"
        }
        return null
    }

    private fun md5Of(file: File): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse { "" }
    }

    private suspend fun handleRequest(processingFile: File) {
        val content = runCatching { processingFile.readText() }
            .onFailure { AIDevLogger.e("DeployBridge", "read request failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val json = runCatching { JSONObject(content) }
            .onFailure { AIDevLogger.e("DeployBridge", "parse json failed", it) }
            .getOrNull() ?: run { processingFile.delete(); return }

        val ctx = appCtx ?: run { processingFile.delete(); return }
        val id = json.optString("id", processingFile.nameWithoutExtension)
        val apk = json.optString("apk", "")
        val pkg = json.optString("pkg", "")
        val launch = json.optBoolean("launch", true)

        val stateFile = File(PathConfig.tasksDir(ctx), "task-records.json")

        // MD5 握手 + Fail-Fast：脚本缺失/损坏时直接回报错误，避免闭环误判「已成功」
        val scriptErr = validateDeployScript(PathConfig.aidevHome(ctx))
        if (scriptErr != null) {
            processingFile.delete()
            val rec = TaskRecord(
                definition = TaskDefinition(
                    id = "deploy-$id", name = "部署 $pkg",
                    description = "aidev-autoinstall 安装${if (launch) "并拉起" else ""} ($pkg)",
                    command = "aidev-autoinstall --json${if (launch) " --launch $pkg" else ""} $apk",
                    workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
                    tags = listOf("deploy", "self-evolution")
                ),
                status = TaskStatus.FAILED, startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(), exitCode = 1,
                log = scriptErr.takeLast(6000),
                lastUpdatedAt = System.currentTimeMillis(),
                steps = listOf(TaskStepResult("准备", TaskStatus.FAILED, log = scriptErr))
            )
            runCatching { TaskStore.upsertTask(stateFile, rec, limit = 12) }
            writeResult(ctx, id, false, scriptErr, apk, pkg, false, false, null, scriptErr)
            notify(ctx, "AIDev 部署失败", scriptErr, "high")
            return
        }

        // 部署在 PRoot 内执行，宿主绝对路径（/data/user/0/.../files/home/...）在 PRoot 视图中不可见；
        // 必须用 PRoot 绑定视图路径 /host-home/...（与 AIDEV_HOME 一致）。
        val deployScript = "/host-home/dev-env/bin/aidev-autoinstall"
        val (prootApk, extraBinds) = toProotPath(apk, PathConfig.workspaceDir(ctx))
        val cmdFlags = buildString {
            append("--json")
            if (launch) append(" --launch '${shEscape(pkg)}'")
            append(" ")
        }
        val definition = TaskDefinition(
            id = "deploy-$id",
            name = "部署 $pkg",
            description = "aidev-autoinstall 安装${if (launch) "并拉起" else ""} ($pkg)",
            command = "$deployScript $cmdFlags'${shEscape(prootApk)}'",
            workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
            tags = listOf("deploy", "self-evolution")
        )
        val startedAt = System.currentTimeMillis()

        fun publish(status: TaskStatus, exitCode: Int, finishedAt: Long, steps: List<TaskStepResult>, log: String) {
            val record = TaskRecord(
                definition = definition,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitCode = exitCode,
                log = log.takeLast(6000),
                lastUpdatedAt = System.currentTimeMillis(),
                steps = steps
            )
            runCatching { TaskStore.upsertTask(stateFile, record, limit = 12) }
        }

        if (apk.isBlank() || pkg.isBlank()) {
            val log = "✗ 缺少 apk 或 pkg，无法部署"
            publish(TaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(TaskStepResult("安装", TaskStatus.FAILED, log = "缺少参数")), log)
            writeResult(ctx, id, false, "缺少 apk 或 pkg", apk, pkg, false, false, null, "缺少 apk/pkg")
            processingFile.delete()
            return
        }
        if (!isValidApkPath(apk)) {
            val log = "✗ apk 路径包含非法字符或非 .apk 文件: $apk"
            publish(TaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(TaskStepResult("安装", TaskStatus.FAILED, log = "apk 校验失败")), log)
            writeResult(ctx, id, false, "apk 路径校验失败", apk, pkg, false, false, null, log)
            processingFile.delete()
            return
        }
        if (!isValidPkg(pkg)) {
            val log = "✗ package name 格式非法: $pkg"
            publish(TaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(TaskStepResult("安装", TaskStatus.FAILED, log = "pkg 校验失败")), log)
            writeResult(ctx, id, false, "package name 格式非法", apk, pkg, false, false, null, log)
            processingFile.delete()
            return
        }

        // 初始进度（与构建按钮同款步骤呈现，保证面板一致）
        val initSteps = if (launch) listOf(
            TaskStepResult("安装", TaskStatus.RUNNING),
            TaskStepResult("拉起", TaskStatus.PENDING)
        ) else listOf(TaskStepResult("安装", TaskStatus.RUNNING))
        publish(TaskStatus.RUNNING, -1, 0L, initSteps, "已提交部署请求，等待执行…")
        notify(ctx, "AIDev 部署", "开始部署 $pkg", "default")

        val opts = ProotLauncher.Options(
            rootfs = PathConfig.rootfs(ctx).absolutePath,
            cwd = "/workspace",
            binds = listOf(ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")) + extraBinds,
            env = mapOf(
                "PATH" to "/host-home/dev-env/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "AIDEV_HOME" to "/host-home"
            ),
            timeoutSec = 300,
            redirectErrorStream = true
        )

        val cmd = "$deployScript $cmdFlags'${shEscape(prootApk)}'"
        val result = runCatching { ProotLauncher.run(ctx, cmd, opts) { activeProcesses[id] = it } }.getOrElse {
            val log = "✗ 部署执行异常: ${it.message}"
            publish(TaskStatus.FAILED, -1, System.currentTimeMillis(),
                listOf(TaskStepResult("安装", TaskStatus.FAILED, log = it.message ?: "异常")), log)
            writeResult(ctx, id, false, "部署执行异常", apk, pkg, false, false, null, it.message ?: "异常")
            processingFile.delete()
            return
        }
        activeProcesses.remove(id)

        if (id in cancelledIds) {
            val log = "⏹ 已取消"
            publish(TaskStatus.CANCELLED, -1, System.currentTimeMillis(), initSteps, log)
            writeResult(ctx, id, false, "已取消", apk, pkg, false, false, null, "已取消")
            processingFile.delete()
            return
        }

        val deploy = parseDeployJson(result.stdout)
        val installed = deploy?.optBoolean("installed") ?: false
        val launched = deploy?.optBoolean("launched") ?: false
        val activity = deploy?.optString("activity")?.takeIf { it != "null" && it.isNotBlank() }
        val error = deploy?.optString("error")?.takeIf { it != "null" && it.isNotBlank() }
        val success = installed && (if (launch) launched else true)

        val steps = if (launch) listOf(
            TaskStepResult("安装", if (installed) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                log = if (!installed) (error ?: "安装失败") else ""),
            TaskStepResult("拉起", if (launched) TaskStatus.SUCCEEDED else if (installed) TaskStatus.FAILED else TaskStatus.PENDING,
                log = if (installed && !launched) (error ?: "拉起失败") else "")
        ) else listOf(
            TaskStepResult("安装", if (installed) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                log = if (!installed) (error ?: "安装失败") else "")
        )

        val logText = buildString {
            append("aidev-autoinstall 输出:\n")
            append(result.stdout.takeLast(4000))
            if (error != null) append("\n错误: $error")
            append("\n安装: ${if (installed) "成功" else "失败"}   拉起: ${if (launched) "成功" else if (launch) "失败" else "未请求"}")
            if (activity != null) append("   组件: $activity")
        }

        val rawTail = result.stdout.takeLast(400)
        val message = when {
            success && launched -> "部署成功：已安装并拉起"
            success && !launch -> "部署成功：已安装"
            installed && !launched -> "已安装，但拉起失败：${error ?: ""}"
            deploy == null -> "部署失败：aidev-autoinstall 未输出结构化结果。原始输出：\n$rawTail"
            else -> "部署失败：${error ?: "未知原因"}。原始输出：\n$rawTail"
        }
        if (!success) AIDevLogger.i("DeployBridge", "request $id 失败 rawStdout=\n${result.stdout.takeLast(1500)}")

        val finalStatus = if (success) TaskStatus.SUCCEEDED else TaskStatus.FAILED
        publish(finalStatus, if (success) 0 else 1, System.currentTimeMillis(), steps, logText)
        writeResult(ctx, id, success, message, apk, pkg, installed, launched, activity, error)
        notify(ctx, if (success) "AIDev 部署完成" else "AIDev 部署失败", message, if (success) "default" else "high")
        AIDevLogger.i("DeployBridge", "request $id done success=$success msg=$message")
        processingFile.delete()
    }

    /** 对 shell 单引号字符串中的值进行转义：将 ' 替换为 '\'' 以便安全拼接。 */
    private fun shEscape(value: String): String = value.replace("'", "'\\''")

    /** 验证 apk 路径不含非法字符。 */
    private fun isValidApkPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (!path.endsWith(".apk", ignoreCase = true)) return false
        return path.none { c -> c in ";&|`\$\"'(){}[]<>#!*?~" }
    }

    /** 验证 package name 格式（字母/数字/下划线/点，不含 shell 元字符）。 */
    private fun isValidPkg(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' }
    }

    /** 宿主绝对路径 → PRoot 内可见路径 + 必要的额外 bind。 */
    private fun toProotPath(hostPath: String, ws: File): Pair<String, List<ProotLauncher.ProotBind>> {
        val wsAbs = ws.absolutePath
        if (hostPath.startsWith(wsAbs)) {
            return "/workspace" + hostPath.removePrefix(wsAbs).replace('\\', '/') to emptyList<ProotLauncher.ProotBind>()
        }
        if (hostPath.startsWith("/sdcard") || hostPath.startsWith("/storage") ||
            hostPath.startsWith("/host-home") || hostPath.startsWith("/system")
        ) {
            return hostPath to emptyList<ProotLauncher.ProotBind>()
        }
        val f = File(hostPath)
        val parent = f.parentFile?.absolutePath ?: return hostPath to emptyList<ProotLauncher.ProotBind>()
        return "/mnt/apk/${f.name}" to listOf(ProotLauncher.ProotBind(parent, "/mnt/apk"))
    }

    private fun parseDeployJson(stdout: String): JSONObject? {
        return stdout.lines().map { it.trim() }.filter { it.startsWith("{") }.firstNotNullOfOrNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    private fun writeResult(
        ctx: Context, id: String, success: Boolean, message: String,
        apk: String, pkg: String, installed: Boolean, launched: Boolean,
        activity: String?, error: String?
    ) {
        val o = JSONObject().apply {
            put("id", id)
            put("success", success)
            put("message", message)
            put("time", System.currentTimeMillis())
            put("apk", apk)
            put("pkg", pkg)
            put("installed", installed)
            put("launched", launched)
            put("activity", activity ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
        }
        runCatching { File(requestDir, "result-$id.json").writeText(o.toString(2)) }
    }

    private fun notify(ctx: Context, title: String, msg: String, priority: String) {
        runCatching { AIDevCommandDispatcher.notify(ctx, title, msg, priority, false, false) }
    }
}
