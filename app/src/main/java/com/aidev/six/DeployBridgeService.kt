package com.aidev.six

import android.content.Context
import com.aidev.six.agent.AgentTaskDefinition
import com.aidev.six.agent.AgentTaskRecord
import com.aidev.six.agent.AgentTaskStatus
import com.aidev.six.agent.AgentTaskStepResult
import com.aidev.six.agent.AgentTaskStore
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
 * 「服务器中心」面板的「安装 / 拉起」按钮，以及宇宙 A（OpenCode）终端的 `aidev-deploy` 命令，
 * 都走同一套标准入口/出口，保证服务一致性：
 *
 *   标准入口: 写入 home/.aidev-deploy-bridge/req-<id>.json
 *     { "id", "apk": "<宿主绝对路径>", "pkg": "<包名>", "launch": true/false }
 *   本服务轮询该目录，在【宇宙 A（agent rootfs）】内执行 `aidev-deploy --apk ... --pkg ...`，
 *   解析其标准出口 JSON（{installed,launched,activity,error}），把进度/结果作为单一真源写入
 *   agent-tasks.json（AF 面板轮询即看到一致过程），并写回 result-<id>.json。
 *
 * 部署黑盒的实现（Shizuku 静默安装 / 启动 / 二次校验）完全由 aidev-deploy 负责，本服务不越界。
 */
object DeployBridgeService : BridgeService("DeployBridge") {

    private const val BRIDGE_DIR = ".aidev-deploy-bridge"

    private var requestDir: File? = null
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
        // 部署黑盒脚本由 assets 落地到 dev-env/bin（PRoot 内 /host-home/dev-env/bin），
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

    private fun ensureDeployScripts(home: File) {
        // 自举架构：部署脚本优先由 Agent（构建后）单向写入共享 home 的 dev-env/bin，
        // 并附 .md5 校验文件。宿主 A 只做「盲盒执行器」：校验存在 + MD5 一致后按绝对路径调起。
        // 这里仅作为首次兜底（当 Agent 尚未投递脚本时），且绝不覆盖已有的 Agent 投递副本。
        // 共享 home 与 PRoot 内 AIDEV_HOME 一致（均为 /host-home），避免 Android 侧真实
        // files/home 与绑定视图不一致时校验/执行错位。
        val ctx = appCtx ?: return
        val bin = File(home, "dev-env/bin").apply { mkdirs() }
        val assets = mapOf(
            "aidev-deploy.sh" to "aidev-deploy",
            "aidev-install.sh" to "aidev-install",
            "aidev-shizuku.sh" to "aidev-shizuku",
            "aidev-verify-run.sh" to "aidev-verify-run"
        )
        assets.forEach { (asset, name) ->
            val dst = File(bin, name)
            val md5File = File(bin, "$name.md5")
            if (dst.exists() && md5File.exists()) return@forEach // Agent 已投递 + 校验文件完整
            if (dst.exists() && !md5File.exists()) {
                // 脚本存在但校验文件缺失 → 补全 md5（不覆盖脚本本身）
                md5Of(dst).takeIf { it.isNotEmpty() }?.let { md5File.writeText(it) }
                AIDevLogger.i("DeployBridge", "补齐 $name.md5")
                return@forEach
            }
            runCatching {
                ctx.assets.open("scripts/$asset").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true, false)
                md5Of(dst).takeIf { it.isNotEmpty() }?.let { md5File.writeText(it) }
            }.onFailure { AIDevLogger.e("DeployBridge", "deploy script $name failed", it) }
        }
    }

    /**
     * 部署入口脚本校验（MD5 握手 + Fail-Fast）：
     * 1) 脚本与 .md5 校验文件必须同时存在；
     * 2) 脚本实际 MD5 必须与 .md5 内容一致（拒绝旧/损坏/被截断的副本）。
     * 校验路径与 PRoot 内 AIDEV_HOME（/host-home）一致，规避 Android 侧真实 files/home
     * 与绑定视图不一致的错位问题。
     * 返回 null 表示校验通过；否则返回应直接回报给闭环的错误信息。
     */
    private fun validateDeployScript(home: File): String? {
        val bin = File(home, "dev-env/bin")
        val script = File(bin, "aidev-deploy")
        val md5File = File(bin, "aidev-deploy.md5")
        if (!script.exists() || !md5File.exists()) {
            return "部署入口脚本或校验文件缺失（Agent 需在构建后投递 aidev-deploy + aidev-deploy.md5 到共享 home/bin）"
        }
        val expect = runCatching { md5File.readText().trim().split("\\s+".toRegex())[0] }.getOrNull() ?: ""
        val actual = md5Of(script)
        if (expect.isEmpty() || actual.isEmpty() || expect != actual) {
            return "部署脚本 MD5 不匹配（期望=$expect 实际=$actual），拒绝执行旧/损坏脚本"
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

        val stateFile = File(PathConfig.tasksDir(ctx), "agent-tasks.json")

        // MD5 握手 + Fail-Fast：脚本缺失/损坏时直接回报错误，避免闭环误判「已成功」
        val scriptErr = validateDeployScript(PathConfig.aidevHome(ctx))
        if (scriptErr != null) {
            processingFile.delete()
            val rec = AgentTaskRecord(
                definition = AgentTaskDefinition(
                    id = "deploy-$id", name = "部署 $pkg",
                    description = "aidev-deploy 安装${if (launch) "并拉起" else ""} ($pkg)",
                    command = "aidev-deploy --apk $apk --pkg $pkg${if (launch) " --launch" else " --no-launch"}",
                    workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
                    tags = listOf("deploy", "self-evolution")
                ),
                status = AgentTaskStatus.FAILED, startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(), exitCode = 1,
                log = scriptErr.takeLast(6000),
                lastUpdatedAt = System.currentTimeMillis(),
                steps = listOf(AgentTaskStepResult("准备", AgentTaskStatus.FAILED, log = scriptErr))
            )
            runCatching { AgentTaskStore.upsertTask(stateFile, rec, limit = 12) }
            writeResult(ctx, id, false, scriptErr, apk, pkg, false, false, null, scriptErr)
            notify(ctx, "AIDev 部署失败", scriptErr, "high")
            return
        }

        val deployScript = File(PathConfig.aidevHome(ctx), "dev-env/bin/aidev-deploy").absolutePath
        val definition = AgentTaskDefinition(
            id = "deploy-$id",
            name = "部署 $pkg",
            description = "aidev-deploy 安装${if (launch) "并拉起" else ""} ($pkg)",
            command = "$deployScript --apk $apk --pkg $pkg${if (launch) " --launch" else " --no-launch"}",
            workingDirectory = PathConfig.workspaceDir(ctx).absolutePath,
            tags = listOf("deploy", "self-evolution")
        )
        val startedAt = System.currentTimeMillis()

        fun publish(status: AgentTaskStatus, exitCode: Int, finishedAt: Long, steps: List<AgentTaskStepResult>, log: String) {
            val record = AgentTaskRecord(
                definition = definition,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                exitCode = exitCode,
                log = log.takeLast(6000),
                lastUpdatedAt = System.currentTimeMillis(),
                steps = steps
            )
            runCatching { AgentTaskStore.upsertTask(stateFile, record, limit = 12) }
        }

        if (apk.isBlank() || pkg.isBlank()) {
            val log = "✗ 缺少 apk 或 pkg，无法部署"
            publish(AgentTaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(AgentTaskStepResult("安装", AgentTaskStatus.FAILED, log = "缺少参数")), log)
            writeResult(ctx, id, false, "缺少 apk 或 pkg", apk, pkg, false, false, null, "缺少 apk/pkg")
            processingFile.delete()
            return
        }
        if (!isValidApkPath(apk)) {
            val log = "✗ apk 路径包含非法字符或非 .apk 文件: $apk"
            publish(AgentTaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(AgentTaskStepResult("安装", AgentTaskStatus.FAILED, log = "apk 校验失败")), log)
            writeResult(ctx, id, false, "apk 路径校验失败", apk, pkg, false, false, null, log)
            processingFile.delete()
            return
        }
        if (!isValidPkg(pkg)) {
            val log = "✗ package name 格式非法: $pkg"
            publish(AgentTaskStatus.FAILED, 1, System.currentTimeMillis(),
                listOf(AgentTaskStepResult("安装", AgentTaskStatus.FAILED, log = "pkg 校验失败")), log)
            writeResult(ctx, id, false, "package name 格式非法", apk, pkg, false, false, null, log)
            processingFile.delete()
            return
        }

        // 初始进度（与构建按钮同款步骤呈现，保证面板一致）
        val initSteps = if (launch) listOf(
            AgentTaskStepResult("安装", AgentTaskStatus.RUNNING),
            AgentTaskStepResult("拉起", AgentTaskStatus.PENDING)
        ) else listOf(AgentTaskStepResult("安装", AgentTaskStatus.RUNNING))
        publish(AgentTaskStatus.RUNNING, -1, 0L, initSteps, "已提交部署请求，等待执行…")
        notify(ctx, "AIDev 部署", "开始部署 $pkg", "default")

        val (prootApk, extraBinds) = toProotPath(apk, PathConfig.workspaceDir(ctx))
        val cmd = "$deployScript --apk '${shEscape(prootApk)}' --pkg '${shEscape(pkg)}' ${if (launch) "--launch" else "--no-launch"}"
        val opts = ProotLauncher.Options(
            rootfs = PathConfig.agentRootfs(ctx).absolutePath,
            cwd = "/workspace",
            binds = listOf(ProotLauncher.ProotBind(PathConfig.workspaceDir(ctx).absolutePath, "/workspace")) + extraBinds,
            env = mapOf(
                "PATH" to "/host-home/dev-env/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "AIDEV_HOME" to "/host-home"
            ),
            timeoutSec = 300,
            redirectErrorStream = true
        )

        val result = runCatching { ProotLauncher.run(ctx, cmd, opts) }.getOrElse {
            val log = "✗ 部署执行异常: ${it.message}"
            publish(AgentTaskStatus.FAILED, -1, System.currentTimeMillis(),
                listOf(AgentTaskStepResult("安装", AgentTaskStatus.FAILED, log = it.message ?: "异常")), log)
            writeResult(ctx, id, false, "部署执行异常", apk, pkg, false, false, null, it.message ?: "异常")
            processingFile.delete()
            return
        }
        activeProcesses.remove(id)

        if (id in cancelledIds) {
            val log = "⏹ 已取消"
            publish(AgentTaskStatus.CANCELLED, -1, System.currentTimeMillis(), initSteps, log)
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
            AgentTaskStepResult("安装", if (installed) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED,
                log = if (!installed) (error ?: "安装失败") else ""),
            AgentTaskStepResult("拉起", if (launched) AgentTaskStatus.SUCCEEDED else if (installed) AgentTaskStatus.FAILED else AgentTaskStatus.PENDING,
                log = if (installed && !launched) (error ?: "拉起失败") else "")
        ) else listOf(
            AgentTaskStepResult("安装", if (installed) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED,
                log = if (!installed) (error ?: "安装失败") else "")
        )

        val logText = buildString {
            append("aidev-deploy 输出:\n")
            append(result.stdout.takeLast(4000))
            if (error != null) append("\n错误: $error")
            append("\n安装: ${if (installed) "成功" else "失败"}   拉起: ${if (launched) "成功" else if (launch) "失败" else "未请求"}")
            if (activity != null) append("   组件: $activity")
        }

        val message = when {
            success && launched -> "部署成功：已安装并拉起"
            success && !launch -> "部署成功：已安装"
            installed && !launched -> "已安装，但拉起失败"
            else -> "部署失败：${error ?: "未知原因"}"
        }

        val finalStatus = if (success) AgentTaskStatus.SUCCEEDED else AgentTaskStatus.FAILED
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
