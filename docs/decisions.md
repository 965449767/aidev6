# Decision Log

Use this file to record stable project decisions.

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
