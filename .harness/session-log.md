# Session Log

## 2026-06-14 - Harness Initialization

### Summary

Started `0.13.0` Standard Harness initialization.

### Files Created or Updated

- `AGENTS.md`
- `current-task.md`
- `docs/architecture.md`
- `docs/verification.md`
- `docs/coding-guidelines.md`
- `docs/decisions.md`
- `docs/error-journal.md`
- `.harness/session-state.json`

### Validation

Passed:

```bash
bash scripts/harness_check.sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
```

## 2026-06-14 - Git Initialization

### Summary

User approved Git initialization.

### Commands Run

```bash
git init
git branch -m main
git config user.name "AIDev Harness"
git config user.email "aidev-harness@example.local"
git status --short
```

### Files Created or Updated

- `.gitignore`
- `AGENTS.md`
- `docs/git-workflow.md`
- `docs/decisions.md`
- `current-task.md`
- `.harness/session-state.json`
- `.harness/session-log.md`

### Validation

Passed:

```bash
bash scripts/harness_check.sh
git status --short
git log --oneline -1
```

### Commit

```text
9a1b229 chore: initial aidev terminal snapshot
```

### Progress Report

```text
已完成：Git 初始化
本次完成：创建 main 分支初始快照
总体进度：100%
剩余：0 阶段
验证：通过
下一步：商量 0.13.2 工程结构整理
```

## 2026-06-27 - B/C 轮重构 + 测试全部完成

### Summary

完成 EmbeddedFilesPage 状态集中化、逻辑类提取、单元测试、清理。

### 新增文件

| 文件 | 行数 | 说明 |
|------|------|------|
| `FilePageState.kt` | 18 | 状态数据类（18 字段） |
| `MultiSelectHandler.kt` | 91 | 多选逻辑 |
| `NavigationHandler.kt` | 52 | 导航逻辑 |
| `FileUtils.kt` | 61 | 纯函数工具集 |
| `MultiSelectHandlerTest.kt` | ~240 | 15 测试（含 TestFilePageHost fake） |
| `NavigationHandlerTest.kt` | ~160 | 10 测试（含 TestFilePageHost fake） |
| `FileUtilsTest.kt` | ~100 | 14 测试 |

### 修改文件

| 文件 | 变化 |
|------|------|
| `EmbeddedFilesPage.kt` | -300 行（~1657 → ~1360），方法委托到新 Handler |
| `FilePageHost.kt` | +2 方法，-2 死方法 |

### Validation

```bash
./gradlew :app:testDebugUnitTest --no-daemon    # 78/78 PASS
./gradlew :app:assembleDebug --no-daemon         # BUILD SUCCESSFUL
```

### 统计

- EmbeddedFilesPage: ~1657 → ~1360 行（-300）
- 新测试: 39（总计 78）
- 构建: APK 11.9MB

## 2026-06-28 — Android 开发链全量审计 + 全部修复

### Summary

从 `aidev-error-why` → `aidev-opencode` → `aidev-build` → `aidev-logcat` → `aidev-apk-info` → QEMU 构建 → 单元测试逐层审计，
发现并修复 P0-P4 全部 13 个问题。

### 审计发现

```
层级          缺陷数   典型问题
aidev-error-why   1   colordiff 未安装
aidev-opencode    2   未复制到 PRoot、TUI 修改状态不回写
aidev-build       1   未复制到 PRoot
aidev-apk-info    1   apkanalyzer 路径错误
TUI 进程管理      2   scope 残留、WakeLock 并发
Gradle/NDK        2   NDK r28 损坏、缺失链接
aidev-logcat      1   未复制到 PRoot
preferences       2   批量写缺 commit()、空值崩溃
Rust             1   cargo-apk 缺失
```

### 修复

| 优先级 | 文件/脚本 | 修改 |
|---|---|---|
| P0 | aidev-error-why、ensure_ubuntu_helpers | 安装 colordiff |
| P0 | UbuntuBootstrapScripts.kt | `ensure_ubuntu_helpers()` 添加 3 个脚本复制 |
| P0 | aidev-build | TUI 修改状态机修正 + 消息保留 |
| P0 | EmbeddedFilesPage.kt、EmbeddedSettingsPage.kt | scope.cancel() → children.forEach cancel |
| P0 | aidev-apk-info | apkanalyzer 软链接 android-sdk-lib → system/bin |
| P0 | NDK | 降级 r27，shell 测试验证路径 |
| P1 | aidev-opencode | `ensure_ubuntu_helpers()` 添加复制 |
| P1 | aidev-build | 状态机 IDLE→RUNNING→parse→complete |
| P2 | tools_check.sh | NDK/Gradle cache 警告 |
| P3 | PreferencesManager.kt | 批量写加 commit() |
| P3 | PreferencesManager.kt | `null ==` 值安全 + 文件路径验证 |
| P4 | 新建 test_shell/ | `aidev-test-shell` 框架 + 6 脚本 47 测试 |

### Shell 测试框架

```
test_shell/
  run_tests.sh             # 入口：shunit2 + 错误诊断 + 退出码
  tests/
    test_aidev_apk_info.sh       # 8 tests
    test_aidev_build.sh          # 8 tests
    test_aidev_error_why.sh      # 8 tests
    test_aidev_logcat.sh         # 7 tests
    test_aidev_opencode.sh       # 8 tests
    test_tools_check.sh          # 8 tests
```

### Validation

```bash
./gradlew :app:testShellScripts --no-daemon       # 47/47 PASS
./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --no-daemon        # 78/78 PASS
./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

### 统计

- 审计文件: 20+
- 修复提交: `cf076dc` + `4d5c41f`
- Shell 测试: 47（6 套），全部通过
- Kotlin 单元测试: 78，全部通过
- 构建: assembleDebug 成功，APK 输出正常

### 下一步

用户安装 APK 验证生产环境功能：`aidev-build`、`aidev-error-why`、`aidev-opencode`、`aidev-logcat`、`aidev-apk-info`

---

## 2026-06-28 — UX 优化 5 个阶段全部完成

### Summary

按 P0→P4 五个阶段，逐阶段改完 → 编译 → 等待用户确认，全部编译通过。

### 改动总览

| Phase | 文件 | 改动 |
|---|---|---|
| 1 | SshBookmarksPage.kt | 删除 SSH 连接前弹出确认弹窗 |
| 1 | ContainerManagerPage.kt | APT 缓存清理 + 临时文件清理前弹出确认 |
| 2 | SystemMonitorPage.kt | 移除 scrollTo(0,0) 滚动复位；添加暂停/恢复按钮；`catch(_)` → `Log.w`；添加 GB/s 网络速度 |
| 3 | NetworkDiagnosticsPage.kt | `HttpsURLConnection` → `HttpURLConnection`（支持 http/https）；端口检查结果 Toast → Dialog；DNS 正则放宽 |
| 4 | EmbeddedFilesPage.kt | 大目录截断提示（"共 N 项，仅显示前 300 项"） |
| 4 | KnowledgeBasePage.kt | 搜索添加 300ms 防抖 |
| 5 | SecurityAuditPage.kt | 添加"重新扫描"按钮 |
| 5 | SshBookmarksPage.kt | 端口范围校验（1-65535）；添加连接状态指示（✓ 可达 / ✗ 不可达，socket 2s 超时） |

### Validation

```bash
./gradlew :app:compileDebugKotlin --no-daemon    # BUILD SUCCESSFUL × 5
./gradlew :app:testDebugUnitTest --no-daemon      # 78/78 PASS
./gradlew :app:assembleDebug --no-daemon           # BUILD SUCCESSFUL
```

---

## 2026-06-30 — TabChip 手势修复 + 锁定功能 + Crash 诊断 + 重组优化

### Summary

完成 TabChip 手势系统修复（左标签下滑删除崩溃）、新增上滑锁定/解锁、Crash 诊断系统 v2、根因分析、重组优化。

### 新增文件

| 文件 | 说明 |
|------|------|
| `CrashMonitor.kt` | Shizuku 实时 logcat 监控（流式 + 轮询 + 守护进程），写日志到 `/sdcard/AIDev/crash-monitor.log` |

### 修改文件

| 文件 | 变化 |
|------|------|
| `TerminalPanel.kt` | TabChip 三种手势合并为单一 `pointerInput` + `awaitEachGesture`；动作通过 `LaunchedEffect(pendingAction)` 延迟执行避免移除中崩溃；添加锁定图标；`offset/alpha` → `graphicsLayer` 避免每帧重组 |
| `TerminalModels.kt` | `EmbeddedTermSession` 添加 `locked: Boolean` 字段 |
| `SessionManager.kt` | 添加 `toggleSessionLock()`；`closeSessionItem` 加锁守卫；persist/restore `locked` |
| `ShellActivity.kt` | 移除 `Thread.setDefaultUncaughtExceptionHandler`；添加 `checkForCrash()` 启动时检测 + `showCrashReport()`；启动 `CrashMonitor` |

### Crash 诊断发现

崩溃发生在**左标签删除（≤3个标签）**时：
- **无 FATAL EXCEPTION、ANR、SIGSEGV、OOM** — 无任何 Java 崩溃日志
- `MiuiPerfServiceClient` 报告 "Slow main thread"（100-300ms 阻塞）在崩溃前持续出现
- `ActivityTaskManager` 输出 "Force removing ActivityRecord: app died, no saved state" — **HyperOS 静默杀进程**
- 标记性：`Thread.setDefaultUncaughtExceptionHandler` **无法捕获**（不是 Java 异常），Shizuku logcat 可以事后读取

### 根因

`.offset {} + .alpha()` 中读取 `swipeOffset`（`mutableFloatStateOf`）时，**`alpha()` 在 Compose 阶段读取 State 触发每帧重组**。手势时触摸事件每 ~8ms 更新一次，重组+布局+绘制累计主线程 ~300ms 阻塞，被 HyperOS 性能监控检测后直接杀进程。

**修复**：替换为 `.graphicsLayer { translationY = ...; alpha = ... }` — `graphicsLayer` 中的 State 读取属于**绘制阶段**，只触发绘制失效，不触发重组+重布局。

### Validation

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:testDebugUnitTest --no-daemon        # BUILD SUCCESSFUL (129 tests)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon           # BUILD SUCCESSFUL
```

---

## 2026-06-30 — TabChip 手势修复 v2：snapshotFlow + key(session.id)

### 问题

上一轮修复（`LaunchedEffect(pendingAction)` + `graphicsLayer`）引入新 bug：

1. **剩 1-2 个标签时下滑删除无响应** — `LaunchedEffect(pendingAction)` 中 `pendingAction` 被置回 `null` 时 key 变化，协程被 cancel/restart，特定时序下 `action()` 不执行
2. **重启后删恢复的标签闪退** — 同上机制导致主线程阻塞 → HyperOS 静默杀进程

### 修复

| 文件 | 改动 |
|------|------|
| `TerminalPanel.kt:249-257` | `LaunchedEffect(pendingAction)` → `LaunchedEffect(Unit)` + `snapshotFlow { pendingAction }.collect` — **协程不 cancel/recreate**，单次启动稳定消费 pending action |
| `TerminalPanel.kt:213` | `key(session.id)` 包裹 TabChip — 防止 Compose 位置复用导致继承被删标签的 remember state |
| `TerminalPanel.kt:309-310` | `graphicsLayer` → `offset {} + alpha()` 还原（graphicsLayer 不是问题但无必要） |
| `SessionManager.kt` | `closeSessionItem` 加 `val wasCurrent = current == item` 局部变量，避免 `remove` 后比较 |

### 根因

`LaunchedEffect(key)` 当 key 变化时会 cancel 旧协程 + launch 新协程。而 `pendingAction = null` 在 effect 内部改变了自己的 key，导致：
- 协程被 cancel 时 `action()` 可能中断 → close 不执行
- 若 `action()` 阻塞了主线程 → HyperOS 杀进程

`snapshotFlow` 在同一协程内流式观察状态变化，**不 cancel/recreate**，完全避免了这个问题。

### Validation

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:testDebugUnitTest --no-daemon        # BUILD SUCCESSFUL (129 tests)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

### 待验证

- 安装 APK 测试：右到左删除 → 1-2 标签仍可删除 → 杀掉重启 → 删除恢复的标签不闪退

### 后期计划

- 安装 APK 验证左标签删除崩溃是否已修复
- 继续 Phase E（E01-E17 UI 布局审计）

## 2026-07-01 - Comprehensive Audit Fixes (Phase E Post-Audit)

### Summary

Completed a comprehensive codebase audit (5 parallel agents: performance, coroutines, resources, null-safety, Compose state) finding 6 P0 (crash/leak), 9 P1 (bug/perf), 5 P2 (code-quality) issues.

**Batch 1 (P0 crash/leak fixes):**
- B1: PreviewManager WebView leak → `DisposableEffect` + `destroy()`
- B2: SystemMonitorDialog polling on every recomposition → `LaunchedEffect(Unit)`
- B3: SystemMetricsCollector `line!!` NPE → safe-cast + `?.let`; stream leaks → `.use {}`
- B4: SftpService `as ChannelSftp` without safe-cast → `as?` + early return

**Batch 2 (P1 resource/performance/perf fixes):**
- B5: ProjectTreeView `buildFlatList()` I/O on every recomposition → `remember(root, expanded, activePath)`
- B6: SFtpPanel `remember {}` in LazyColumn without key → added `key = { it.id }`
- Resource leaks: BackupRestoreDialog, OpenCodeMonitorService (×2), OpenCodeActionReceiver → `.use {}`
- LazyColumn keys: 12 `items()` calls across 6 files added `key` parameter
- GitState error surfacing: 8 methods now set `errorMessage` on non-zero exit
- AIDevCommandDispatcher: 2 exec() streams now consumed

### Validation

```bash
./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

## 2026-07-01 - Batch 3 (P2 Code Quality Fixes)

### Summary

Completed P2 code-quality cleanup:
- ShizukuLogcat `line?.let` → `line!!.let` (while-loop guaranteed non-null)
- SystemMetricsCollector `line?.trim()?.split` → local `val l = line ?: continue` then `l.trim().split()`
- CompletionEngine consolidated consecutive `inputProxy?.` calls into single `?.let` block

### Validation

```bash
./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

## 2026-07-01 — Terminal 标签切换冻结修复（AndroidView 生命周期根因）

### 问题

从 Terminal 切换到 Files 再切回 Terminal，5~10 秒后 App 卡死被 HyperOS 杀进程。

### 追踪过程

1. **初版 IO 修复**（ShellAssets cache + `Dispatchers.IO`）→ 无效，仍然卡死
2. **深度静态分析**发现根因：`AndroidView(factory = { coreView })` 位于 NavHost composable 路由内，标签切换时 Compose 销毁/重建 AndroidView → `TerminalView.onDetachedFromWindow()` / `onAttachedToWindow()` → EGL/OpenGL 原生表面创建阻塞主线程 → HyperOS 检测到主线程慢后静默杀进程

### 修复

**两处改动，4 个概念步骤：**

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | `AppNavHost.kt` | TerminalPanel 从 NavHost composable 路由移出到外层 `Box` 作为永久 overlay，路由内用空 `Box(Modifier.fillMaxSize())` 占位 |
| 2 | `TerminalPanel.kt` | 添加 `visible: Boolean` 参数 |
| 3 | `TerminalPanel.kt` | `DisposableEffect(page, lifecycle)` → `DisposableEffect(lifecycle)`，避免 page 变化时重新绑定生命周期 |
| 4 | `TerminalPanel.kt` | `AndroidView(update = { it.visibility = ... })` 用 `View.INVISIBLE` 而非移除 composition；`Modifier.graphicsLayer { alpha = 0f }` 隐藏 Compose chrome；`LaunchedEffect(visible)` 处理标签重新选中时的初始化 |

### 原理

- `View.INVISIBLE` 不触发 `onDetachedFromWindow()`，Native 表面保持存活
- `AndroidView` 不离开 composition，不触发 `DisposableEffect` dispose → factory 不再被调用
- Tab 切换时只更新 `visible` 布尔值和 `view.visibility`

### Validation

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

用户实际安装验证：Tab 切换不再卡死 ✅

## 2026-07-12 — ServerPanel 全面重设计（b94-b99）

### Summary

ServerPanel（服务中心）从单 Column 709 行重写为 3 Tab 导航结构，同时修复 9 项 Bug，新增确认弹窗和日志滚动。

### Changes

| 步骤 | 内容 |
|------|------|
| b94 | Tab 导航框架：`TabRow` + `Crossfade`，3 Tab（状态/任务/进化） |
| b95 | Tab 1 状态：系统状态 2×2 网格 + 快速诊断 + 安装工具 |
| b96 | Tab 2 任务：快速任务 + 构建项目选择器 + 提交构建 + 任务记录列表 + 崩溃报告卡片 |
| b97 | Tab 3 进化：闭环状态 + 自治模式 + 改码模型选择器 + 对话日志滚动 |
| b98 | Bug 修复 B1-B9：状态轮询/确认弹窗/异常保护/JSON安全/重试注入/autonomousOn同步 |
| b99 | 构建验证 + 安装真机 |

### Validation

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon  # BUILD SUCCESSFUL
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon        # BUILD SUCCESSFUL
aidev-install app/build/outputs/apk/debug/app-debug.apk                                      # 静默安装成功
```

## 2026-07-12 — 自动化构建流程优化任务规划

### Summary

对完整自动化构建流程（BuildBridgeService + AgentTaskStore + CrashReportBridgeService + BridgeService + BuildProgress）进行端到端分析，识别出 14 项优化建议，全部记录到 `current-task.md`。

### 分析产出

| 优先级 | 编号 | 改进项 | 核心问题 |
|--------|------|--------|----------|
| P0 | OPT-01 | JDK 多镜像 fallback + 缓存校验 | 192MB 单镜像下载是单点故障 |
| P0 | OPT-02 | AgentTaskStore 内存缓存 + 延迟写盘 | 每 ≥800ms 全量读写 12×6KB |
| P0 | OPT-03 | BuildProgress 结构化状态 | 字符串匹配推导阶段脆弱易误判 |
| P1 | OPT-04 | Shizuku 安装重试 | 未就绪时直接跳过无重试 |
| P1 | OPT-05 | 动态崩溃等待 | 固定 8 秒太死板 |
| P1 | OPT-06 | 编译错误分类 + 中文建议 | exit=$exit 小白看不懂 |
| P1 | OPT-07 | scaffold Compose 模板 | 默认 View 项目不符预期 |
| P1 | OPT-08 | 源码预检 | 只检查 build.gradle.kts |
| P2 | OPT-09 | 增量编译提示 | 用户不知是否增量 |
| P2 | OPT-10 | 构建历史统计 | 只保留 12 条无统计 |
| P2 | OPT-11 | BridgeService 空闲退避 | 500ms 固定轮询浪费 CPU |
| P2 | OPT-12 | 构建产物缓存 | 源码未变仍完整编译 |
| P2 | OPT-13 | Gradle 分发包预置 | 首次下载 ~150MB 慢 |
| P2 | OPT-14 | 取消确认弹窗 | 误触直接强杀 |

### 状态变更

- `current-task.md`：重写为构建流程优化 14 项（b100 起）
- `session-state.json`：phase 改为 `build-pipeline-optimization`，frozen_tasks 冻结所有其他任务
- 所有其他任务（Phase I/ServerPanel/Tasks Tab audit/Phase H/Phase G）一律冻结

## 2026-07-13 — 构建/部署/验证三黑盒解耦 + 面板部署按钮（v117-v121）

### Summary

重构自我进化闭环：宇宙 B = 编译黑盒（只出 APK + pkg），部署/验证拆成独立设备侧黑盒；OpenCode 经三黑盒循环自驱「改码→构建→部署→验证」。废弃 aidev-self-evolution 后台守护。用户要求「服务器中心」面板提供独立的「安装/拉起」控制，底层由部署黑盒驱动且保证服务一致性（单一真源 agent-tasks.json）。

### 关键改动

- v120：删除 BuildBridgeService.installAndLaunch，构建黑盒不再谎报安装/拉起，result 只产出 APK（真存在）+ pkg。
- v121：新增 DeployBridgeService（同构 BuildBridgeService），轮询 `.aidev-deploy-bridge/req-<id>.json`，PRoot(agent rootfs) 内跑 aidev-deploy，解析标准出口，单一真源写 agent-tasks.json；DeployRequestTracker 写 req；ServerPanel「宇宙 B」新增「部署到设备」区（安装并拉起 / 仅安装）；SessionManager 启动 DeployBridgeService；部署脚本从 assets 落地 dev-env/bin（headless 可用）；BuildBridgeService 用 aapt2 解析 pkg，result 带 pkg/project。

### 状态变更

- current-task.md：黑盒2 补 v121 面板接入；闭环编排补面板按钮说明；待验证补 v121 步骤。
- docs/decisions.md：新增「三黑盒解耦 + 部署经 DeployBridgeService 接入面板」决策。
- docs/error-journal.md：新增 v120/v121 解耦+面板条目；新增 aidev-install exit non-zero 待修条目。
- docs/verification.md：新增 Phase I 面板部署黑盒验证（本环境可验 + 真机冻结）。
- session-state.json：phase → self-evolution-build-deploy-decouple；completed_phases 补 v117-v121。
- 编译安装成功：versionName 1.0.0-b121，APK /sdcard/AIDev/app-debug.apk。

### 下一步

修真机 aidev-deploy 安装失败（aidev-install exit non-zero）：排查 Shizuku 授权 / PRoot 内 aidev-install 路径依赖 / APK 路径设备侧可见性。
