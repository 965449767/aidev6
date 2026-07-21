package com.aidev.six.terminal

import android.content.Context
import com.aidev.six.PathConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AIDev 专属内置命令目录（帮助中心数据源）。
 *
 * 仅收录 AIDev 自带命令（aidev-* / task-* / sys* / list-* / android-sh 及其包装 pmx·amx·getpropx·logcatx /
 * rootfs 管理 ubuntu·compiler 等），不含通用 Linux / Git / Shell 命令（ls·cd·grep·git·./gradlew …）。
 *
 * 数据来源双轨：
 * 1. 本登记表提供「详细功能 + 用法」（编译期维护，最准确）；
 * 2. [scanInstalledCommands] 运行时扫描 [PathConfig.devEnvBin] 实际部署的脚本，
 *    仅返回「已部署且在本表登记」的命令，保证帮助里列的都是真实可用命令。
 */
internal object CommandCatalog {

    data class CommandInfo(
        val name: String,
        val category: String,
        val description: String,
        val usage: String,
    )

    // dev-env/bin 内的非命令文件（调度器本身 / 仅作转发的 marker 桩）需排除
    private val EXCLUDED_NAMES = setOf(
        "aidev-ubuntu-core",
    )

    // marker 桩（内容为转发说明，实际由 .aidevrc 函数承接）——虽可经别名调用，
    // 但 dev-env/bin 中同名文件不是真正脚本，扫描时跳过其「文件存在」判定；
    // 这些命令仍经登记表显式收录（见下方 REGISTRY），故不会被漏掉。
    private val MARKER_STUBS = setOf(
        "ubuntu", "install-ubuntu", "aidev-auto-bootstrap", "aidev-doctor",
    )

    private val REGISTRY: List<CommandInfo> = listOf(
        // ── 构建 / 部署 ───────────────────────────────────────────────
        CommandInfo(
            "aidev-build-request", "构建/部署",
            "请求宿主在终端环境中构建项目，是唯一构建入口。",
            "aidev-build-request [--project <名称|/workspace/路径>] [--no-install] [--no-launch] [--launch-package <pkg>]\n" +
                "  例：aidev-build-request --project MyApp\n" +
                "      aidev-build-request --project /workspace/MyApp --no-launch",
        ),
        CommandInfo(
            "aidev-build-log", "构建/部署",
            "读取本地构建日志（宿主实时落盘 /sdcard/AIDev/logs/<项目>/build.log，终端可直接读）。",
            "aidev-build-log <project> [--tail N]\n" +
                "  aidev-build-log latest|list          列出最近构建日志\n" +
                "  例：aidev-build-log DebugTest\n" +
                "      aidev-build-log DebugTest --tail 100",
        ),
        CommandInfo(
            "aidev-deploy", "构建/部署",
            "部署黑盒：把 APK 装到真机并（可选）启动，封装 Shizuku 安装/启动/权限。",
            "aidev-deploy --apk <apk路径> --pkg <包名> [--launch|--no-launch]",
        ),
        CommandInfo(
            "aidev-install", "构建/部署",
            "安装 APK，支持静默（Shizuku）/标准（系统安装器）两种模式。",
            "aidev-install [options] <apk_path>\n" +
                "  例：aidev-install app-debug.apk",
        ),
        CommandInfo(
            "aidev-verify-run", "构建/部署",
            "运行验证黑盒：窗口内监控目标包是否崩溃 / ANR，返回确定性结论。",
            "aidev-verify-run <pkg> [--launch]",
        ),
        CommandInfo(
            "aidev-apk-info", "构建/部署",
            "解析 APK 文件信息并以中文显示（包名/版本/权限等）。",
            "aidev-apk-info <apk_path>",
        ),

        // ── 诊断 / 设备 ───────────────────────────────────────────────
        CommandInfo(
            "aidev-logcat", "诊断/设备",
            "经 Shizuku 获取 Android 应用日志（含 AIDev 自身）。",
            "aidev-logcat                                  # AIDev Terminal 日志\n" +
                "aidev-logcat com.example.app               # 指定应用\n" +
                "aidev-logcat --follow com.example.app      # 持续监听\n" +
                "aidev-logcat --level ERROR --lines 500",
        ),
        CommandInfo(
            "aidev-shizuku", "诊断/设备",
            "通过 Shizuku 执行 Android 系统命令（exec/input/wifi 等）。",
            "aidev-shizuku exec '<command>'                # 执行 shell\n" +
                "aidev-shizuku status                       # 桥接状态\n" +
                "aidev-shizuku input tap 100 100            # 模拟点击",
        ),
        CommandInfo(
            "aidev-dumpsys", "诊断/设备",
            "dumpsys 快捷命令，查看内存/窗口/电池/网络等系统状态。",
            "aidev-dumpsys [meminfo|activity|window|battery|diskstats|<subsystem>]",
        ),
        CommandInfo(
            "aidev-anr", "诊断/设备",
            "读取 ANR traces（系统无响应记录）。",
            "aidev-anr [list|latest|<name>|summary|clear]",
        ),
        CommandInfo(
            "aidev-tombstone", "诊断/设备",
            "读取原生崩溃 tombstone（native crash）。",
            "aidev-tombstone [list|latest|<n>|clear]",
        ),
        CommandInfo(
            "aidev-crash-why", "诊断/设备",
            "分析运行时崩溃日志，给出可能原因。",
            "aidev-crash-why [--anr|--logcat <文件>]",
        ),

        // ── 项目 / 任务 ───────────────────────────────────────────────
        CommandInfo(
            "aidev-current-project", "项目/任务",
            "输出当前项目上下文：pwd、git 分支、package.json scripts、项目类型探测。",
            "aidev-current-project",
        ),
        CommandInfo(
            "list-listen-ports", "项目/任务",
            "列出当前监听的 TCP 端口（ss/netstat/回退 /proc/net）。",
            "list-listen-ports",
        ),
        CommandInfo(
            "task-list", "项目/任务",
            "列出后台任务及其运行状态（来自 \$AIDEV_HOME/tasks/*.meta）。",
            "task-list",
        ),
        CommandInfo(
            "task-run", "项目/任务",
            "在后台启动一个命名任务并管理其生命周期（与终端生命周期无关）。",
            "task-run <name> <command>\n" +
                "  例：task-run build 'npm run build'",
        ),

        // ── 代码 / 脚手架 ─────────────────────────────────────────────
        CommandInfo(
            "aidev-gen", "代码/脚手架",
            "生成 Android 组件骨架代码（Activity/Fragment/ViewModel），自动探测包名。",
            "aidev-gen activity|fragment|viewmodel <名称> [选项]",
        ),
        CommandInfo(
            "aidev-index", "代码/脚手架",
            "从 Android 项目构建可搜索索引，秒级定位类/资源/方法。",
            "aidev-index [class|res|string|layout|function|refresh] <关键词>\n" +
                "  aidev-index                            # 刷新索引",
        ),
        CommandInfo(
            "aidev-error-why", "代码/脚手架",
            "搜索常见构建错误并显示解决方案（可管道接 build.log）。",
            "aidev-error-why [--lang en] [--all] [<关键词>]\n" +
                "  cat build.log | aidev-error-why",
        ),
        CommandInfo(
            "create-compose-project", "代码/脚手架",
            "从本地模板创建 Jetpack Compose Android 项目（离线优先）。",
            "create-compose-project [options] <ProjectName>\n" +
                "  例：create-compose-project -p com.demo MyApp",
        ),

        // ── 环境 / 工具 ───────────────────────────────────────────────
        CommandInfo(
            "aidev-clean", "环境/工具",
            "清理构建缓存（PRoot 内版）。",
            "aidev-clean [--all|--gradle|--builds|--dry-run]",
        ),
        CommandInfo(
            "aidev-precache", "环境/工具",
            "预缓存 AIDev 开发基线依赖，保障离线构建。",
            "aidev-precache [--project <路径>]",
        ),
        CommandInfo(
            "aidev-repo", "环境/工具",
            "AIDevRepo 离线仓库消费端（fail-safe），读取共享仓库目录。",
            "aidev-repo [list|install <name>]",
        ),
        CommandInfo(
            "aidev-doctor", "环境/工具",
            "环境诊断：检查 AIDev 开发环境是否就绪。",
            "aidev-doctor",
        ),
        CommandInfo(
            "setup-dev-env", "环境/工具",
            "初始化 / 修复开发环境（安装脚本、工具链、依赖）。",
            "setup-dev-env",
        ),
        CommandInfo(
            "aidev-bridge", "环境/工具",
            "AIDev 桥接客户端：经本机 TCP loopback 把请求帧推送给宿主 BridgeSocketServer，失败回退文件通道。",
            "aidev-bridge status                         # 桥接状态\n" +
                "aidev-bridge send notify '{\"title\":\"t\",\"message\":\"m\"}'",
        ),
        CommandInfo(
            "aidev-notify", "环境/工具",
            "经宿主桥接推送 Android 通知（Socket 主用，文件通道兜底）。",
            "aidev-notify \"标题\" \"内容\"",
        ),

        // ── 系统控制 ──────────────────────────────────────────────────
        CommandInfo(
            "sysnotify", "系统控制",
            "发送 Android 通知。",
            "sysnotify [--priority high|default] [--ongoing] <标题> <内容>",
        ),
        CommandInfo(
            "screencap", "系统控制",
            "截图到指定路径。",
            "screencap <输出路径>",
        ),
        CommandInfo(
            "volume", "系统控制",
            "控制媒体/铃声/闹钟/通话音量。",
            "volume [get|set <0-100>|mute|unmute]",
        ),
        CommandInfo(
            "brightness", "系统控制",
            "控制屏幕亮度（或切自动）。",
            "brightness [get|<0-100>|auto]",
        ),
        CommandInfo(
            "sysclip", "系统控制",
            "剪贴板读取 / 写入。",
            "sysclip get | sysclip set <文本>",
        ),
        CommandInfo(
            "aidev-proxy", "系统控制",
            "代理管理（tinyproxy 启停）。",
            "aidev-proxy [start|stop|restart|status]",
        ),

        // ── Android shell 辅助（android-sh 包装）──────────────────────
        CommandInfo(
            "android-sh", "Android 辅助",
            "在 Android 宿主 shell 中执行命令（PRoot 内包装）。",
            "android-sh '<command>'",
        ),
        CommandInfo(
            "pmx", "Android 辅助",
            "android-sh pm 的快捷包装（包管理）。",
            "pmx [list|install|uninstall|...]",
        ),
        CommandInfo(
            "amx", "Android 辅助",
            "android-sh am 的快捷包装（Activity 管理）。",
            "amx [start|force-stop|broadcast ...]",
        ),
        CommandInfo(
            "getpropx", "Android 辅助",
            "android-sh getprop 的快捷包装（读系统属性）。",
            "getpropx [<属性名>]",
        ),
        CommandInfo(
            "logcatx", "Android 辅助",
            "android-sh logcat 的快捷包装（原生 logcat）。",
            "logcatx [<过滤参数>]",
        ),

        // ── rootfs 管理 ───────────────────────────────────────────────
        CommandInfo(
            "ubuntu", "rootfs 管理",
            "进入 / 操作主 Ubuntu rootfs（PRoot）。",
            "ubuntu [command]",
        ),
        CommandInfo(
            "install-ubuntu", "rootfs 管理",
            "安装 / 修复主 Ubuntu rootfs。",
            "install-ubuntu",
        ),
    )

    private val REGISTRY_BY_NAME = REGISTRY.associateBy { it.name }

    /**
     * 运行时扫描 dev-env/bin，返回「已部署且在本表登记」的 AIDev 专属命令。
     * 通用 Linux / Git / Shell 命令不在本表，自然被排除。
     */
    suspend fun scanInstalledCommands(ctx: Context): List<CommandInfo> = withContext(Dispatchers.IO) {
        val deployed = collectDeployedNames(ctx)
        filterRegistry(deployed)
    }

    /** 收集 dev-env/bin 中实际可执行的命令名（含 .privot 子目录）。 */
    private fun collectDeployedNames(ctx: Context): MutableSet<String> {
        val bin = PathConfig.devEnvBin(ctx)
        val deployed = mutableSetOf<String>()
        if (bin.isDirectory) {
            bin.listFiles()?.forEach { f ->
                val name = f.name
                if (name in EXCLUDED_NAMES) return@forEach
                if (f.isFile && f.canExecute()) deployed.add(name)
            }
            // .privot 子目录内的脚本也是真实命令
            File(bin, ".privot").listFiles()?.forEach { f ->
                if (f.isFile && f.canExecute()) deployed.add(f.name)
            }
        }
        // marker 桩虽在 dev-env/bin 但内容是转发说明；它们经 .aidevrc 别名可用，
        // 这里把 marker 也视为已部署，使登记表里的环境命令能正常显示。
        deployed.addAll(MARKER_STUBS)
        return deployed
    }

    /** 由「已部署命令名集合」过滤登记表，得到最终帮助列表（纯函数，便于测试）。 */
    internal fun filterRegistry(deployed: Set<String>): List<CommandInfo> =
        REGISTRY.filter { it.name in deployed }
            .sortedWith(compareBy({ it.category }, { it.name }))

    /** 仅用于测试：直接返回登记表（不依赖文件系统）。 */
    fun allRegistryCommands(): List<CommandInfo> = REGISTRY
}
