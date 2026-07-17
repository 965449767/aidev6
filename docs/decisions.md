# Decision Log

Use this file to record stable project decisions.

## 2026-07-17 - 重构为「人类驱动」开发闭环：移除 AI 自动写码 / 自我进化

### Context

原架构把 AIDev 设计成「自我进化闭环」：OpenCode（宇宙 A）读崩溃/构建失败回流 → 自动改码 → 触发重建。
这与「终端在无 AI Agent 时也必须完整可用、OpenCode 仅作为人类驱动的写代码工具」的原则冲突，
且 `BuildBridgeService.preflightCheck` 会静默改写用户 `app/build.gradle.kts`、`aidev-build`（宇宙 A 本地 `./gradlew`）
与统一入口 `aidev-build-request`（宇宙 B 编译）双入口并存易混淆。

### Decision

- **移除 AI 自动写码闭环**：删除 `BuildRequestTracker` / `DeployRequestTracker` / `CrashReportBridgeService`、
  `OpenCodeMonitorService`、`LoopTrace`；删除 `SELF_EVOLUTION_*` 偏好与 `consumeLegacyCrashes`。
- **构建统一到 `aidev-build-request`**：删除 `aidev-build`（宇宙 A 本地 `./gradlew`）；构建失败仅把完整日志落到
  `logs/<project>/last-build-failure.log` 供人类排查，不再写 `self-evolution/build-failure` 回流、不再自动改写工程文件。
- **建项目统一到 `create-compose-project`**：对齐宿主版本（AGP 9.0.1 / Kotlin 2.0.21 / Gradle 9.1.0 / compileSdk·targetSdk 36）；
  `aidev-create-android-project` 退化为其兼容封装。
- **OpenCode 接入已被整体推翻**：后续（2026-07-17 第二阶段）`OpenCodeEngine`/`AIEngine`/`OpenCodeActionReceiver`/`OpenCodeMonitorService` 与全部 OpenCode 部署代码、资产、文档均被彻底删除，宿主不再含任何 AI 写码代理集成。
- **UI 按钮即终端快捷方式**：「编译」按钮在终端会话写入 `aidev-build-request --project …`，过程对人类可见。

### Consequences

- 终端在 OpenCode 未安装/离线时仍完整可用；人类 100% 掌控改码与构建。
- `aidev-build-request` 成为唯一构建入口，心智模型统一；`self-evolution-loop.md` 标记退役。
- 回归风险低：均为删除/收敛，核心桥接（Build/Deploy/Notify/Install 四桥）与 PRoot 集成未动；
  `compileDebugKotlin` / `testDebugUnitTest` / `app/src/test/sh/run.sh` / `harness_check.sh` 全绿。

## 2026-07-14 - 桥接通信升级：TCP loopback Socket 主用 + 文件轮询兜底

### Context

原 5 个桥（Notify/Shizuku/Build/Deploy/Crash）均由 `BridgeService` 以 500ms 文件轮询驱动，
PRoot 侧写请求文件、宿主轮询读取。长任务（Build/Deploy）对延迟不敏感，但交互型桥（Shizuku exec、
Notify）有 500ms~3s 的感知延迟；且轮询在空闲时仍周期性唤醒。

### Decision

- 通信升级为 **TCP loopback（127.0.0.1:14096）Socket 主用 + 文件轮询灾备**，二者长期并存。
- 选用 TCP loopback 而非 Unix Domain Socket（抽象命名空间）：PRoot 侧 bash 客户端可用
  `nc` / bash `/dev/tcp` 零依赖连接，避免 socat/python3/UDS 工具依赖与 SELinux 复杂度；仍属本机局部通信。
- 统一信封 `BridgeFrame{b,i,p}`（4 字节长度头 + JSON），传输抽象 `BridgeTransport`
  （`TcpBridgeTransport` 生产/测试共用），路由 `BridgeRegistry`，核心编解码/路由/收发在 JVM 单测覆盖
  （因 `LocalSocket` 仅真机可用）。
- 各桥 `dispatch`：Notify/Shizuku 同步返回结果；Build/Deploy/Crash 仅落盘 `req-<id>.json` + 返回
  `"accepted"`，复用既有 `poll→handleRequest→cancel`，零改动重逻辑。
- PRoot 客户端 `aidev-bridge`：python3 发帧，失败自动回退文件 drop；`aidev-shizuku.sh`/`aidev-build-request.sh` 已优先走 socket。
- 全局回滚开关 `BRIDGE_SOCKET_ENABLED`（默认 true），关=false 即纯文件轮询（等价升级前行为）。

### Consequences

- 交互型桥请求从「500ms~3s 轮询延迟」降至即时推送；空闲不再周期性唤醒。
- 每阶段独立 commit、可回滚；文件通道永久保留作灾备。
- 已知限制（预存，非本次引入）：`ShizukuBridgeService.isCommandAllowed` 字符白名单允许 `rm` 等
  危险命令（仅按字符集判断），Socket/文件通道行为一致；后续可收紧前缀白名单，但需评估是否影响合法命令。
- 改动：`BridgeFrame.kt`/`BridgeTransport.kt`/`BridgeSocketServer.kt`/`BridgeRegistry.kt`、
  `BridgeService.kt`（bridgeName/dispatch/注册）、`Constants`/`PreferencesManager`（开关）、
  5 个 `*BridgeService`（bridgeName + dispatch）、`aidev-bridge.sh`、`aidev-shizuku.sh`/`aidev-build-request.sh`、
  `UbuntuBootstrapScripts.kt`（部署 aidev-bridge）。
- 实机验证点：① `aidev-bridge status` 应为 ONLINE；② `aidev-bridge send notify '{"title":"t","message":"m"}'`
  通知秒出；③ `aidev-shizuku exec 'input tap 100 100'` 秒级返回；④ `BRIDGE_SOCKET_ENABLED=false` 回退文件仍正常。

## 2026-06-14 - Use ShellActivity as the single terminal entry

### Context

The project previously had both `MainActivity` and `EmbeddedTerminalPage` terminal paths.

### Decision

`ShellActivity` is the launcher and the embedded terminal is the active terminal entry.

### Consequences

- New terminal navigation should route through `AppNav.openTerminal` or `ShellHost.openTerminal`.
- `MainActivity` was removed; `ShellActivity` is the sole entry point.

## 2026-06-14 - Use `.aidev-rootfs-ready` as Ubuntu readiness marker

### Context

Partial rootfs extraction can leave `etc/os-release` present even when Ubuntu is not usable.

### Decision

Ubuntu is ready only when `home/ubuntu-rootfs/.aidev-rootfs-ready` exists.

### Consequences

- Status cards and bootstrap checks should use the marker.
- Half-extracted rootfs should not be treated as installed.

## 2026-06-14 - Initialize Standard Project Harness

### Context

Future agent sessions need durable project context, validation rules, and handoff state.

### Decision

Create a Standard Harness with `AGENTS.md`, `docs`, `.harness`, `skills`, and `scripts`.

### Consequences

- Future sessions should start by reading harness state.
- Completion reports must include concise progress percentage.

## 2026-06-14 - Limit ROM adaptation to Xiaomi HyperOS

### Context

The active target device is Xiaomi 14 Pro on HyperOS 3.0 / Android 16.0.

### Decision

ROM-specific rules should focus on Xiaomi HyperOS unless the user requests additional vendors.

### Consequences

- Do not spend implementation effort on Huawei, OPPO, vivo, or other ROMs by default.
- HyperOS keep-alive, battery, notification, and permission behavior is the primary compatibility target.

## 2026-06-14 - Git workflow established

### Context

The project directory is now a Git repository (branch `main`). Backup and rollback are handled through Git.

### Decision

- Commits are allowed automatically when a phase completes and validation passes.
- `git tag`, `git reset`, `git clean`, `git push`, and remote configuration still require explicit user approval.
- The initial snapshot was created after the project reached a stable documentation baseline.

### Consequences

- `docs/git-workflow.md` defines backup and rollback rules.
- If Git becomes unavailable, record that limitation instead of pretending Git validation passed.
- Destructive Git operations always require explicit user approval.

## 2026-06-17 - Introduce AIDevBottomSheet as custom BottomSheet base

### Context

The project does not include AndroidX Material dependency, so standard `BottomSheetDialog` is unavailable. `EmbeddedSettingsPage` previously used inline `MenuItem` data class and `AlertDialog` for menus.

### Decision

Create a pure custom `AIDevBottomSheet` using `Dialog` + `LinearLayout` + `ScrollView`, with drag indicator, title bar, divider, and swipe-to-dismiss. Build `MenuBottomSheet` on top of it with `MenuBottomSheet.MenuItem` nested data class.

### Consequences

- All menu popups in `EmbeddedSettingsPage` now route through `MenuBottomSheet` for consistent UX.
- `MenuItem` is now a nested class of `MenuBottomSheet`; call sites must use `MenuBottomSheet.MenuItem`.
- `BackupRestorePage` was removed due to prior file corruption; backup/restore menu items temporarily toast "开发中" until the page is reimplemented.

## 2026-06-22 - Opt out of Edge-to-Edge enforcement (temporary)

### Context

`targetSdk = 36` (Android 16) 触发了系统 Edge-to-Edge 强制执行。`ShellActivity` 未适配 window insets，导致 APP bar 重叠系统状态栏。

### Decision

短期：在 `AppTheme` 中添加 `android:windowOptOutEdgeToEdgeEnforcement = true`，让系统恢复旧版布局行为，`statusBarColor` 生效。

### Consequences

- 问题立即修复，不影响现有功能
- 长期来看仍需正式迁移到 Edge-to-Edge，计划在 `0.14.x` 实施

## 2026-06-22 - Planned Edge-to-Edge migration (future)

### Context

`windowOptOutEdgeToEdgeEnforcement` 是临时方案。Android 15+ 逐步淘汰旧式状态栏行为，未来 SDK 版本可能移除该标志。

### Decision

在 `0.14.x` 中实施正式迁移，步骤：引入 `androidx.activity:activity` 依赖 → 在 `ShellActivity` 中调用 `enableEdgeToEdge()` → 用 `ViewCompat.setOnApplyWindowInsetsListener` 处理 navHost insets → 移除 opt-out 标志。

### Consequences

- 迁移前需要验证虚拟键盘、底部导航栏、全屏模式的行为
- 终端页面（`EmbeddedShellPages.kt`）需额外适配 keyboard insets

## 2026-07-10 - Shell entry script is sole auto-bootstrap mechanism

### Context

Three separate paths triggered `aidev-auto-bootstrap` at session startup: the shell entry script, `maybeAutoBootstrapUbuntu()` in `SessionManager.kt`, and `TerminalCommandBus.post("aidev-auto-bootstrap")` in `ShellActivity.kt`. The latter two caused terminal noise by writing commands into a running PRoot process.

### Decision

Remove `maybeAutoBootstrapUbuntu()` and `TerminalCommandBus.post("aidev-auto-bootstrap")`. The shell entry script (`.aidev_shell_entry`, generated by `TerminalShellAssets.writeShellEntry()`) is the sole mechanism for automatic PRoot entry.

### Consequences

- New sessions start with a clean shell; only the entry script triggers auto-enter.
- No garbled text or "No such file" errors from duplicate triggers.
- The entry script's guards (`[ -f core ] && [ -f ready ]`) are sufficient because `ensure()` completes before session creation.

## 2026-07-10 — Pure-bash PATH and function overrides for PRoot

### Context

Android's bionic-linked `/system/bin/` binaries fail inside glibc-based PRoot rootfs. `sed`, `tr`, `grep` from `/system/bin/` cause "required file not found" errors when used in `.bashrc`.

### Decision

Use pure-bash string manipulation in `.bashrc` for:
- Stripping `/system/*` from `PATH` (while-read loop with case/parameter expansion)
- Redirecting `/system/bin/sh` → `/bin/sh` in function bodies (while-read loop with case)

### Consequences

- `.bashrc` works in minimal rootfs where `sed`/`tr`/`grep` may be missing or broken.
- Slightly more verbose but fully portable pure-bash code.
- Both `.bashrc` sections in all three universes (agent, compiler, fix_bashrc) use the same pattern.

## 2026-06-17 - Replace AlertDialog menus with MenuBottomSheet in EmbeddedSettingsPage

### Context

All secondary menus in `EmbeddedSettingsPage` were using `AlertDialog.Builder.setItems()`, which is an older Android pattern and does not support item descriptions.

### Decision

Introduce `MenuBottomSheet` (a custom bottom-sheet Dialog using the project's design tokens) and `MenuItem` data class, and migrate all menu methods (`appearanceMenu`, `terminalMenu`, `devMenu`, `aiServerMenu`, `permissionMenu`, `advancedMenu`, `backupRestoreMenu`) to use it.

### Consequences

- `AlertDialog` is still retained for content dialogs (`detail()`, `sliderDialog()`, `themePresetDialog()`, `devCheckAndRepair()`, etc.) because they need custom views or single-choice items.
- `MenuBottomSheet` is self-contained and reusable for other pages if needed.
- Each menu item now has a title and an optional description, improving UX.
- Future bottom-sheet enhancements should be made in `MenuBottomSheet.kt` to keep `EmbeddedSettingsPage` focused on business logic.

## 2026-07-13 - 三黑盒解耦 + 部署经 DeployBridgeService 接入面板

### Context

构建黑盒原先把"编译→安装→拉起"揉在一起，且用 fire-and-forget 导致误报成功（见 error-journal 2026-07-11 安装/拉起误报）。用户要求「服务器中心」面板有独立按钮控制安装/拉起，底层由部署黑盒提供，且保证与构建一致的**服务一致性**（单一真源 `agent-tasks.json`）。

### Decision

- 构建黑盒只产出 APK（`apk_path`）+ 包名（`pkg`），部署完全交由独立的 `aidev-deploy` 黑盒（黑盒2）。
- 「服务器中心 → 宇宙 B」新增「部署到设备」区：「安装并拉起」「仅安装」两个按钮，写入 `home/.aidev-deploy-bridge/req-<id>.json`。
- 新增 `DeployBridgeService`（与 `BuildBridgeService` 同构的 `BridgeService` 子类）：轮询该目录，在 PRoot（agent rootfs）内跑 `aidev-deploy`，解析其标准出口 JSON（`installed`/`launched`/`activity`/`error`），把「安装/拉起」两步进度作为单一真源写 `agent-tasks.json`；面板轮询即看到一致结果。
- 部署脚本（`aidev-deploy`/`aidev-install`/`aidev-shizuku`/`aidev-verify-run`）在 `DeployBridgeService.onStart()` 时从 assets 落地到 `dev-env/bin`，headless 可用（不依赖开终端）。
- 构建结果 `result-<id>.json` 新增 `pkg` 字段（`aapt2 dump badging` 解析产物包名），部署按钮直接取用，不再靠猜。
- 任务取消逻辑接入 `deploy` 标签（`DeployBridgeService.cancel`）。

### Consequences

- 面板「提交构建请求」只编译出 APK，**不再谎报安装/拉起**（旧记录缓存会自然被新记录覆盖）。
- 部署状态与构建状态同样经 `agent-tasks.json` 回流，闭环编排一致（黑盒1/2/3 三黑盒循环）。
- 改动文件：`DeployBridgeService.kt`（新增）、`DeployRequestTracker.kt`（新增）、`BuildBridgeService.kt`（产物解析 pkg + result 带 project/pkg）、`ServerPanel.kt`（部署区 + lastBuildArtifact）、`SessionManager.kt`（启动 DeployBridgeService）。
- 落地版本：**v121**（versionName 1.0.0-b121）。

## 2026-07-14 — LeakCanary 2.x 仅自动监视 + mavenCentral 兜底；不依赖 LeakAssertions

### Context

P1-6 要在 debug 构建引入 LeakCanary 做内存泄漏看护，并加仪表化测试断言无泄漏。评估 `com.squareup.leakcanary:leakcanary-android:2.12`。

### Decision

- 采用 `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")`：2.x 经 `AndroidManifest` 声明的 `ContentProvider` 在 debug 构建**自动安装**并自动监视 Activity/Fragment/ViewModel 泄漏，无需显式初始化。
- **不调用 `LeakAssertions.assertNoLeaks()`**：2.x 发布物中 `leakcanary-android` 仅为 ~4KB stub AAR（空 `classes.jar`），`leakAssertions` 类不在该（或 `leakcanary-android-core`）发布物中；运行时仅经反射探测其存在。仪表化测试依赖 LeakCanary 自动监视，而非显式断言。
- `settings.gradle.kts` 的 `dependencyResolutionManagement` 在 aliyun `central` 之前加入 `mavenCentral()` 作中央仓库兜底（aliyun 镜像偶发返回损坏/缺失构件时回退）。

### Consequences

- debug APK 自动获得泄漏检测；release 构建不受影响（仅 debugImplementation）。
- 仪表化测试 `ShellActivityTest` 仅做生命周期骨架（启动/销毁 + Tab 可见性），需真机/模拟器 `connectedAndroidTest` 运行。
- 改动文件：`app/build.gradle.kts`（leakcanary dep）、`settings.gradle.kts`（mavenCentral 兜底）、`app/src/androidTest/.../ShellActivityTest.kt`（新增）。
- 落地版本：**b168**。

## 2026-07-13 — 固定开发流程：模板依赖基线 + 可视化预览 + 构建前护栏 + 宇宙B预热

### Context

ServerPanel UI 重构（b144）后复盘，暴露两类问题：① 内置开发"离线缺依赖"偶发失败（material-icons-extended 拉不到）；
② 限制不透明（Material 版本低、某些 API 不可用、敏感权限受限）。用户要求形成**固定开发流程**，让在 AIDev 里开发的 App
"动手前看清 UI/结构/限制，构建前保证依赖齐备、失败早报"，新建与已有项目同等受护。

### Decision

- **单一真相源 `ScaffoldBaseline.kt`**（模板栈）：AGP 8.7.0 / Kotlin 2.0.20 / Compose BOM 2024.12.01（material3 1.3.1）/
  compileSdk 35 / minSdk 26 / 默认含 `material-icons-extended`。`/usr/local/bin/create-compose-project` 与
  `ProjectScaffoldState.generateScript()` 必须与之对齐。
- **宿主 BOM 不动**（仍 2024.12.01，与模板同款），保宇宙 B 稳定锚点；宿主 UI 用 `Button` 而非 `FilledButton`（material3 1.3.1 无）。
- **可视化开发前预览**（`ProjectScaffoldPanel` 三步流）：表单 → 可视化预览（UI 模拟图 + 项目结构树 + 能力&权限清单）→ 脚本预览；
  能力清单与 `docs/compose-capabilities.md` 同源。
- **构建前护栏** `BuildPreflight.checkPreconditions`：Manifest 含 HARD_BLOCKER 权限 → **硬拦截**直接报错（不浪费编译时间）；
  离线且基线依赖未预缓存 → 软提示先 `aidev-precache`。接入 `BuildBridgeService` 编译前。
- **宇宙B预热** `aidev-precache`：自动探测并同步到宇宙 B 的 gradle 缓存（`filesDir/home/gradle-cache`，对应宇宙 B 内
  `/host-home/gradle-cache`），断网也能离线构建。支持 `--gradle-home <DIR>` / `--universe-b`。
- **已有/导入项目非破坏性**：`BuildPreflight` 扩展（源码/Manifest/资源预检）+ 宇宙 B「项目体检」UI，仅检测/报告/预缓存，不自动改写。
- 流程文档：`docs/dev-workflow.md`；能力边界：`docs/compose-capabilities.md`。

### Consequences

- 宿主 `:app:assembleDebug` 连续通过 b145–b150；shell 测试 65 全过（含 create-compose-project）；单元测试 158 仅 3 个预存失败、无新增。
- 改动文件：`ScaffoldBaseline.kt`（新增）、`ProjectScaffoldState.kt`、`ProjectScaffoldPanel.kt`、`BuildPreflight.kt`、
  `BuildBridgeService.kt`、`ServerPanel.kt`、`create-compose-project`、`aidev-precache`、`docs/dev-workflow.md`、`docs/compose-capabilities.md`。
- 落地版本：**b150**（versionName 1.0.0-b150）。

---

## 2026-07-11 - 自我进化闭环：Phase F 冻结判据 + Phase G 反向驱动宇宙 A

> 原记录见根 `decisions.md`（已并入本文件）。

### Phase F — pending_optional 冻结判据

Phase F 目标：把「自我进化闭环」（`宇宙A 写码` → `宇宙B 编译` → `Shizuku 安装` → `自动拉起` → `logcat 抓崩溃` → `喂回宇宙A`）端到端贯通、可观测、收敛长尾。

- **冻结总判据**：一个改动若不能让「写码 → 自动构建运行 → 自动反馈」更快 / 更可靠 / 更可见，则默认冻结（不做）。仅当满足以下「解冻条件」才重新开工：
  1. 用户实测一次完整闭环出现断点 → 对应项解冻。
  2. 后台长任务（编译 >5min / install-compiler >10min）被 HyperOS 实测中断 → 解可靠性项。
  3. 实测误报率 / 漏报率高到干扰判断 → 解健壮性项。
- **已冻结项**：B6（bridges 改前台 Service）、B8（install-compiler 首次静默无进度）、B9（parseCrash 误报/截断健壮性）、F03-细化（阶段标记正则精细化）、并发（多构建请求排队/并行）、冗余入口（「查看崩溃报告」按钮保留）。
- **已交付**：F01 断点清单；F02+F03 BuildRequestTracker（可见状态机 + 实时日志，解决 B1/B2/B3/B4）；F04 崩溃回流统一进任务流视图；F05 崩溃回流单测验证；测试环境修正（includeAndroidResources + org.json + 延迟 mainHandler）。

### Phase G — 反向驱动宇宙 A

- 宇宙 A 与 App 解耦协作，**只用共享工作区文件契约**（`home/workspace/.aidev-loop/crash-<id>.json` + `req-<id>.json`），不引入 IPC/网络。
- 「自治开关」放在 App 内：开启后崩溃自动触发下一轮构建（`requestRebuild`），但改码仍由宇宙 A 完成；防失控靠 `fix_applied` 收敛 + `MAX_AUTO_ITERATIONS=10` 上限。
- 未在 App 内做「无限自动循环」的更激进形态：是否全自动由宇宙 A 侧（OpenCode / `aidev-self-evolution` 脚本）决定，App 只负责把契约文件摆好并响应自治开关。

---

## 2026-07-17 - 批准目标架构方向（Domain 化 + AIEngine 抽象）

### Context

外部架构评审（`/storage/emulated/0/建议.md`）指出 6 条架构层风险（Service 蔓延 / Bridge 成总线 / Bootstrap 过重 / 闭环无状态机 / 无事件总线 / OpenCode 硬编码）。经代码事实核对（`docs/ARCHITECTURE_REVIEW.md`），其中 5 条与现状不符（Bridge 已三层分离、已有状态枚举、已有 `TerminalCommandBus` + SSE 流、真正 `Service` 仅 3 个），**仅「OpenCode 硬编码、无 Provider 抽象」成立**。

### Decision

- 人类批准目标架构方向：按业务领域（Domain）内聚演进 —— `UI / IDE / AI / Runtime / Bridge / Build / Automation / Core`（蓝图见 `docs/target-architecture.md`）。
- 护栏：未来新增代码按 Domain 归属，**不立即大改既有结构**；禁止新增顶层全局 `*Service`；Bridge 保持 Transport/Protocol 分离；闭环走显式 FSM；AI Provider 经 `AIEngine` 抽象接入，OpenCode 为首个实现（契约已定，实现留作后续单独获批子任务，本期不改 `Constants.kt` 硬编码）。
- 评审主张「现在不重构」，故本期只建护栏（文档），具体迁移待具体功能任务顺势执行，每个子任务单独走 EXECUTION.md（≤5 文件、核心模块批准、行为锁）。

### Consequences

- 新增/修订文档：`docs/target-architecture.md`（蓝图 + AIEngine 契约）、`docs/ARCHITECTURE_REVIEW.md`（断言 vs 事实）、`rules/core/PROJECT.md`（DOMAIN OWNERSHIP 护栏）、`rules/core/ARCHITECTURE.md`（APPROVED ARCHITECTURE DIRECTION）。
- 无代码改动，无回归风险；纯文档护栏，授予后续子任务按护栏落地的执行权。

---

## 2026-07-17 - 彻底清除全部 OpenCode / AI 代理集成（全量精炼，选项 B）

### Context

「人类驱动重构」第一阶段仅收敛 OpenCode 为「人类触发中止」，但保留了 `OpenCodeEngine`/`AIEngine` 抽象、OpenCode 部署代码、资产脚本、`config/opencode/` 命令与 `knowledge_base.json` 中 opencode/agent 条目。用户最终判定：目标姿态是「人类作为唯一开发主体」，宿主不应保留任何 AI 写码代理的程序级耦合（端口绑定、SSE 监听、通知按钮、命令部署），且 `agent/` 包虽保留但仅作人类任务执行基础设施（改名留待下次单独任务）。

### Decision

- **代码层全删**：`monitor/OpenCodeEngine.kt`、`monitor/AIEngine.kt`、`OpenCodeActionReceiver.kt`、`monitor/BatteryMonitor.kt`（孤儿）、`config/opencode/` 整目录；`Constants.kt` 删除 `OPENCODE_BASE_URL`/`SELF_EVOLUTION_MODELS`/`SELF_EVOLUTION_DEFAULT_MODEL`。
- **部署层停发**：`TerminalShellAssets.kt` 移除 `deployOpenCodeCommands`/`deployWorkspaceAgents` 调用与 `agentScripts` 中 3 个安装器；`UbuntuBootstrapScriptDefs.kt` 移除 `aidev-agent-context`/`aidev-agent-context-file`/`aidev-opencode`/`aidev-agent-summary`/`aidev-agent-log`/`aidev-agent-tail`/`opencode-check`/`setup-opencode` 脚本定义（保留 `aidev-current-project`/`list-listen-ports`/`task-list`/`task-run` 人类工具）；`CompletionEngine` 去 opencode/agent 补全；`DevEnvironmentChecker` 去 OpenCode 检测。
- **资产与根脚本全删**：`opencode-check.sh`/`setup-opencode.sh`/`install-aitool.sh` 资产、`scripts/aidev-self-evolution`/`scripts/verify-self-evolution.sh`、`app/src/test/sh/opencode-check_test.sh`/`setup-opencode_test.sh`；`knowledge_base.json` 去 opencode/agent 条目。
- **文档收敛**：`docs/target-architecture.md`（改「AIEngine 已落地」为「已彻底清除」）、`docs/app-architecture.md`（第 3 节改「无 OpenCode 耦合」）、`rules/core/PROJECT.md`（去 OpenCode/AI 描述、`AI` Domain 标已移除）、本文件追加本决策。

### Consequences

- 宿主运行时对 AI 写码工具零耦合：无 4096 端口绑定、无 SSE 监听、无通知中止按钮、无命令部署；终端在无任何 AI Agent 时完整可用。
- `agent/` 包（`AgentTaskRunner` 等）作为纯人类任务执行基础设施保留，已重命名为 `task/` 包（`TaskRunner`/`TaskStore`/`PlanEngine`/`BuildProgress`/`ProjectTaskLock`），类与标识符去 Agent 味，无功能改动。
- 验证全绿：`compileDebugKotlin` / `testDebugUnitTest` / `app/src/test/sh/run.sh`（65→63，删 2 个 opencode 测试）/ `scripts/harness_check.sh` / `assembleDebug`。
