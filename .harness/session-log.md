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

## 2026-07-13 — Phase 7 部署可靠性 + 终端构建请求返回值（#135/#137/#139 → 提交 494a327）

### Summary

实测「服务器中心」安装/拉起与终端 `aidev-build-request` 暴露的真实缺陷，不在原 98 项审计内，但直接影响交付可用性，已全部修复并提交。

### 关键改动

- #135 `DeployBridgeService.kt:193`：部署脚本 `deployScript` 由宿主绝对路径改为 PRoot 视图路径 `/host-home/dev-env/bin/aidev-deploy`（PRoot 内仅 `/host-home` 被绑定，宿主路径不可见 → 此前必报"文件不存在"）。
- #137 `aidev-deploy.sh`：`pm list packages` 二次校验改为**非致命**（仅提示），最终以"能否启动"为准（启动成功即证明已安装），消除误报失败；安装重试 3→2、校验/启动 sleep 2s→1s 提速。
- #137 `DeployBridgeService.kt` `ensureDeployScripts`：以 bundled assets 为准，MD5 不一致则覆盖脚本并刷新 `.md5`，避免脚本更新后 `.md5` 过期导致 `validateDeployScript` 误拦。
- #137 `aidev-shizuku.sh`：请求 `REQ_ID` 加 `$RANDOM` 防同 PID 同秒碰撞。
- #139 `aidev-build-request.sh`：提交请求后阻塞等待 `result-<id>.json`（≤900s）；成功打印 `构建成功`+APK（exit 0），失败 cat 完整构建日志 `/sdcard/AIDev/logs/<project>/build.log`（exit 1），超时 exit 2。
- #139 `TerminalShellAssets.kt:274`：`aidev-build-request` 函数改走 `${AIDEV_ROOTFS}/usr/local/bin`（同 aidev-shizuku），由 `copyAssetScripts` 每次终端会话刷新，确保新脚本生效。

### 验证

- 构建 #135/#137/#139 均 BUILD SUCCESSFUL；APK 复制到 /sdcard/AIDev/。
- #139 构建曾因删除 ChatPanel.kt 残留 dex 缓存失败，清理 `app/build/intermediates/global_synthetics_project` 与 `dex` 后恢复。
- 真机复测（部署 + aidev-build-request 返回值）用户侧进行中。

### 状态变更

- current-task.md：新增 Phase 7 记录（P7-01~P7-05）。
- session-state.json：phase 上下文更新；completed_phases 补安全审计 98 项与 Phase 7。
- 提交 494a327（6 文件），main 领先 origin/main 4 提交，未推送。

### 下一步

- Phase 6 测试补齐（P6-01~P6-04）尚未实现。
- 推送前 gradle-wrapper.properties 须改回 https://（当前本地 file://）。

## 2026-07-13 — Phase 6 测试补齐（P6-01~P6-04，42 新用例）

### Summary

按 current-task.md 执行 Phase 6 测试补齐。P6-01/02/03 用本地 JUnit + Mockito + 反射（无需 Robolectric，避免 spawn Termux 进程）；P6-04 ShellActivity 因本环境无法拉取 Robolectric 传递依赖，改用 instrumented 测试。

### 新增测试

- `app/src/test/java/com/aidev/six/chat/OpenCodeClientTest.kt`（P6-01, 12 用例）：反射测试 private `parseMessage`/`parseEvent`，覆盖正常事件流/不完整 chunk/错误字段/data 兜底/权限 v1+v2/追问/完整消息替换/空 JSON 兜底。
- `app/src/test/java/com/aidev/six/DeployBridgeServiceTest.kt`（P6-02, 16 用例）：shEscape/isValidApkPath/isValidPkg/toProotPath/parseDeployJson/validateDeployScript(writeResult 结果 JSON)。
- `app/src/test/java/com/aidev/six/BuildBridgeServiceTest.kt`（P6-02, 7 用例）：resolveProjectDir/derivePackage/finish 结果 JSON 出口。
- `app/src/test/java/com/aidev/six/terminal/SessionManagerTest.kt`（P6-03, 7 用例）：switchSession/closeSession 索引重排/toggleLock/cachedCompletionPwd；反射播种 `_sessions` 用 mock TerminalSession，避免真正 spawn shell。
- `app/src/androidTest/java/com/aidev/six/ShellActivityTest.kt`（P6-04, 2 用例）：onCreate→RESUMED→onDestroy 不抛异常；switchTo 标签页边界保护。需真机 `connectedAndroidTest`。

### 验证

- `./gradlew :app:testDebugUnitTest`：158 用例，仅 3 个**预存失败**（BuildDiagnosticsTest / BuildPreflightSourceTest / BuildRequestTrackerTest，与改动无关，按 AGENTS 不修）残留；新增 42 用例全过。
- instrumented 测试仅编译验证（`compileAndroidTestKotlin` BUILD SUCCESSFUL），真机运行待用户侧。

### 偏差（重要）

- 用户选择「加 Robolectric 写单元测试」，但本环境 Aliyun `central` 镜像对 Robolectric 传递依赖（shadows-framework/icu4j/android-all）报 DNS/连接重置，无法拉取。已回退 Robolectric 依赖，P6-02/03 改为纯 JUnit+反射（等价覆盖），P6-04 改为 instrumented 测试。
- `app/build.gradle.kts` 新增 `testInstrumentationRunner` 与 `androidTestImplementation(androidx.test.ext:junit / runner)`。

---

## 2026-07-13（续）— 新任务：AIDev 固定开发流程

### 起因
- ServerPanel UI 重构（b144）完成后，用户复盘：之前的改动暴露"单调平铺/缺层次"与"离线缺依赖"两类问题。
- 用户要求以 PM 视角重新设计服务器中心 UI（已完成卡片化+三级层级重构，b144 通过）。
- 进一步追问根因：为何 Material 版本低（BOM 2024.12.01→material3 1.3.1，无 FilledButton）、为何离线拉不到 material-icons-extended、限制是否在内置环境存在。
- 结论：限制非硬性封锁，是离线优先 + 基线不全的偶发状况。用户要求形成"固定开发流程"，让在 AIDev 里开发的 App 提前感知 UI/结构/限制，不再突然"用不了/被限制"。

### 设计决策（经多轮澄清）
- 不升 AIDev 宿主 BOM（保 宇宙 B 稳定）；用 `Button` 而非 `FilledButton`。
- 模板栈对齐到 2024.12.01 + 补 material-icons-extended；能力文档描述**模板栈**（非宿主栈）。
- 可视化开发前计划：静态模板 Mockup + 项目结构树 + 能力&权限清单（确认三块都要）。
- 已有/导入项目：检测+报告+预缓存 + **可选**对齐（用户确认才改写），不自动改写。
- 范围：完整版（G1-G10）。

### 执行中
- ① 单一版本/依赖清单(ScaffoldBaseline) + 对齐 create-compose-project 与 generateScript（进行中）。

### 完成情况（同日，宿主 b150）
- ① ✅ `ScaffoldBaseline.kt`（模板栈单一真相源）+ `ProjectScaffoldState.generateScript()` 与 `/usr/local/bin/create-compose-project` 对齐到 2024.12.01 + material-icons-extended；宿主 BOM 不动。
- ② ✅ `aidev-precache`（基线 + 任意项目路径），离线自检通过；基线依赖已预缓存进 `~/.gradle`。
- ③ ✅ `ProjectScaffoldPanel` 重构为三步流：表单 → 可视化预览（UI 模拟图 + 项目结构树 + 能力&权限清单）→ 脚本预览；三块均可滚动。
- ④ ✅ `BuildPreflight.checkPreconditions`：HARD_BLOCKER 权限硬拦截 + 离线缺基线软提示；接入 `BuildBridgeService` 构建前。
- ⑤ ✅ ServerPanel「新建项目（脚手架 + 开发前可视化预览）」一等入口触发脚手架对话框；新增 `docs/dev-workflow.md` 串起全流程。
- ⑥ ✅ `docs/compose-capabilities.md`：模板栈可用/不可用 API + 受限权限（与能力清单同源）。
- ⑦ ✅ `BuildPreflight` 扩展（源码/Manifest/资源预检）+ 宇宙 B「项目体检（旧项目兼容性检查）」UI，仅报告不自动改写。
- 验证：宿主 `:app:assembleDebug` 连续通过 b145–b150；未引入新测试失败（3 个预存失败保持）。
- 备注：推送前需将 `gradle-wrapper.properties` 的 `file://` 改回 `https://`。

### 补充：把预缓存也搬进宇宙 B（同日后）
- 根因：之前 `aidev-precache` 只预热宿主 `~/.gradle`，但宇宙 B 构建用的是它自己的 `GRADLE_USER_HOME=/host-home/gradle-cache`（宿主真实路径 `filesDir/home/gradle-cache`），断网时宇宙 B 仍缺包。
- 修复：`aidev-precache` 现支持 `--gradle-home <DIR>` 指定落点 + 自动探测并**同步**到宇宙 B 缓存目录（单次下载到宿主，再 cp 到宇宙 B，避免双重拉取）；离线自检覆盖两个落点。
- 验证：用 `--gradle-home /tmp/ubtest` 模拟，material-icons-extended 正确落进该目录；默认运行仍预热宿主并自检通过。真机上 `aidev-precache` 会自动把货也备进宇宙 B，断网可离线构建。

### 收尾三步（同日后）
1. **固化文档**：验证结论写入 `docs/decisions.md`（固定开发流程决策）+ `docs/error-journal.md`（material-icons-extended 离线缺包 / aidev-precache 只预热宿主 / testShellScripts 任务不存在）；AGENTS.md Testing 段改正为 `bash app/src/test/sh/run.sh`（原 `testShellScripts` 任务不存在）。
2. **回归验证**：shell 测试 `bash app/src/test/sh/run.sh` → 65 passed, 0 failed；单元测试 158 个、仅 3 个预存失败（无新增）；宿主 assembleDebug b150/b151 通过。无新增破坏。
3. **自动预热宇宙 B**：原想在「新建项目」终端命令后追加 `aidev-precache`，但该脚本不在宇宙 B rootfs、`onExecuteCommand` 走 Ubuntu 终端会 command not found，故 revert。改为在 `BuildBridgeService` 编译前检测 `filesDir/home/gradle-cache` 基线标记，缺且联网时自动在宇宙 B 内跑 `./gradlew dependencies` 预热其缓存。宿主 b151 编译通过。至此：首次在线构建后宇宙 B 断网可离线编译。

### Backlog 优化（同日后，连续推进）
- **OPT-06 编译错误中文建议（dev-workflow 补强，b152）**：`BuildDiagnostics.diagnoseBuildErrors` 早已在构建失败时调用（BuildBridgeService:371），主体已实现。补了一条针对性建议：依赖解析失败且离线/缺缓存（或 `material-icons-extended` 缺失）→ 提示 `aidev-precache` / `aidev-precache --universe-b` 预热。纯加法、低风险。
- **OPT-09 增量编译提示（b153）**：ServerPanel 构建卡片根据 `app/build` 是否存在，显示「默认走增量编译（更快）；若改了依赖/gradle/SDK 版本建议先 clean」或「首次构建走全量（会下载 Gradle 分发与依赖，较慢）」。宿主 b153 编译通过。
- 评估剩余 OPT：OPT-07(scaffold Compose 模板)/08(源码预检) 已在 dev-workflow 顺手完成；OPT-10(最近构建提示) 已有 InfoNote；OPT-06 主体已有。真正未做且需谨慎的：OPT-01(JDK 多镜像)/02(AgentTaskStore 缓存)/03(BuildProgress 结构化)/04(Shizuku 重试)/05(动态崩溃等待)/11(BridgeService 退避)/12(产物缓存)/13(Gradle 分发预置)——多涉及构建/部署核心或需真机验证。

### OPT-02 AgentTaskStore 缓存（同日后，b154）
- 核查发现 OPT-02 核心早已实现：`AgentTaskStore` 已有 `ConcurrentHashMap` 内存缓存 + 2000ms 延迟写盘调度器 + `flush()`。
- 打磨：原实现每次写都 `scheduler.schedule` 排新定时任务，连续更新会排队多个冗余落盘；改为**真正防抖**（新增 `pendingWrites` 记录待写任务，排新前先 `cancel` 上一个），减少重复 IO。纯内部优化、低风险。宿主 b154 编译通过。
- 结论：OPT-02 已完成（含防抖打磨）。

---

## 2026-07-14 — 依 rules/ 工程宪法的稳定性与专业度加固（P0/P1/P2 全完成）

### 起因
- 前序「专业审计」（current-task.md 末尾 ④⑤⑥⑦）与 `rules/` 工程宪法（AGENTS.md、ARCHITECTURE、ANDROID、EXECUTION、VERIFY 等）收敛出同一份计划：把 aidev6 从「单人维护的本地工具」提升到「生产级稳定交付」。
- 用户批准：全部 P0（稳定性）→ P1（专业度）→ P2（深化）一次性执行；允许引入成熟测试/调试依赖（AndroidX Test、LeakCanary、MockK，限 test/debug 作用域）。

### 关键决策与执行
- **P0-1 宿主全局崩溃护栏**：`CrashGuard` 全局 `UncaughtExceptionHandler` 写入 `home/.aidev-crash-bridge/req-<id>.json`（带宿主包名）后委派原 handler，自身不吞异常；`AIDevApp.onCreate` 安装并 `consumeLegacyCrashes`；`BuildRequestTracker.consumeLegacyCrashes` 启动后把无对应 `.aidev-loop` 的 `crash-*.json` 发布到 agent-tasks 闭环（自我修正）。`CrashReportBridgeService` 生成宿主自身崩溃后立即回流宿主包名，避免误报为 agent 崩溃。
- **P0-2 Shizuku 命令白名单 + 注入防护**：`isCommandAllowed` 收紧为前缀白名单 + 拒绝 shell 注入元字符（`;|&$\`()<>\\\n`），方法降为 `internal` 便于单测。即审计待续 ⑥。
- **P0-3 脚本 POSIX 核查**：37 个 `assets/scripts` 脚本全 `#!/bin/sh`，无真实 bashism（`dash -n` 全过），无需改动。
- **P1-4 Lint 基线**：`app/build.gradle.kts` 加 `lint { baseline = file("lint-baseline.xml"); abortOnError=true }` + 关闭翻译/Typography 噪声；生成 `app/lint-baseline.xml`（1753 行，已锁），新增问题即失败。
- **P1-5 CI lint 闸门**：`.github/workflows/ci.yml` 在既有 compile+test+shell+harness 之上新增 `lintDebug`（受 `ANDROID_SDK_ROOT` 门控）。即审计待续 ④。
- **P1-6 LeakCanary + 仪表化骨架**：`debugImplementation leakcanary-android:2.12`（2.x 经 ContentProvider 自动安装并自动监视）；`ShellActivityTest` 仪表化骨架（启动/销毁 + Tab 可见性）。`settings.gradle.kts` 在 aliyun central 前加 `mavenCentral()` 兜底。
- **P2-8 SafeCommandGuard 非交互白名单**：`AGENT_ALLOWED_BINARIES` + `extractExecutables`/`isAllowedExecutable`；agent 上下文阻止未授权可执行文件，交互场景保留危险模式拦截。
- **P2-9 BridgeService 声明幂等**：`BridgeServiceClaimTest` 覆盖 claim 改名 `.processing`、缺失幂等、轮询过滤。
- **P2-10 本地指标**：`LocalMetrics`（失败安全 JSONL 落 `home/.aidev-metrics/events.jsonl`）接入 `BuildRequestTracker.submit` 成功/失败路径。

### 验证
- `compileDebugKotlin` / `testDebugUnitTest`（158+ 单测，仅 3 个预存失败、无新增）/ `assembleDebug` 全过；`lintDebug` 通过（基线闸门生效）。
- `compileDebugAndroidTestKotlin` 通过（仪表化骨架编译 OK，需真机 `connectedAndroidTest` 运行）。
- APK **b168** 复制至 `/sdcard/AIDev/app-debug.apk`（未安装，遵守「禁止自动安装」）。

### 提交（未 push）
- `5d7709d` P0-1；`97505ba` P0-2；`9447895` P1；`83cab88` P2。共 17 文件（11 改 + 6 新增）。
- 用户指示不 push，待确认。

### 关键教训
- **LeakCanary 2.x 的 `leakcanary-android` 是 ~4KB stub**：`LeakAssertions.assertNoLeaks()` 在该发布物中不可用（仅运行时反射探测），故仪表化测试依赖自动监视而非显式断言；真实类在 `leakcanary-android-core`。两镜像（mavenCentral/aliyun）均返回 4240 字节同一 stub。
- 大文件拆分（审计 ⑦）、AGENTS.md 版本号同步、真正的 App 架构文档仍未做，留待后续。
