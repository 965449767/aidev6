# 当前任务：Phase H — 自我进化闭环「验证与待办」

> 🎯 北极星：`宇宙A(OpenCode 写码)` → `宇宙B(编译)` → `Shizuku 静默安装` → `自动拉起` → `logcat→MCP 抓崩溃` → `回流宇宙A 自动改码` → 再构建……
> Phase F（闭环贯通+可见）、Phase G（反向驱动宇宙A：文件契约 + 自治开关 + 常驻守护）均已编码完成并通过单测/构建。
> Phase H：把"能在本环境验证的"全跑一遍（单测/构建/闭环契约模拟），并把"必须真机"的列为待办清单。

> ✅ **2026-07-12 北极星达成（真机全闭环）**：崩溃(04:03:36) → 抓取真实堆栈(v41) → 宇宙A opencode(hy3-free) 自动改码移除 throw → fix_applied + 重建请求 → 宇宙B 自动重建 → 拉起(04:12:06)无新崩溃。
> v42 增：改码模型选择器 + 改码对话实时可见（免费模型按 IP 限额、耗尽静默空返回，故人工看对话切模型）。守护 `aidev-self-evolution` 自动对齐 `/host-home` 路径，直接 `--once` 即可。SSE 富文本对话为后续待办（docs/self-evolution-loop.md §9）。

## 轨道 1 — 本环境可验证项（执行中）
- [x] F01 端到端排查闭环链路，列出所有断点（只读调研，产出问题清单）
      范围：submitBuildRequest → BuildBridgeService → 宇宙B 编译 → aidev-install 静默安装 → 自动拉起 → CrashReportBridgeService → 回流宇宙A

### F01 断点清单（2026-07-11）

链路已确认真实连通：`ServerPanel.submitBuildRequest` 写 `req-<id>.json` → `BuildBridgeService.poll()`（500ms 轮询）→ 宇宙B `ProotLauncher` 跑 `./gradlew assembleDebug` → Shizuku `pm install` + `am start` → 固定 delay(8s) 写 `.aidev-crash-bridge/req` → `CrashReportBridgeService` 抓 logcat → `.aidev-mcp/latest.json` → OpenCode `aidev-crash-report` 读取。骨架完整，但有以下断点：

**可见性（阻断用户感知，最高优先）**
- B1 ServerPanel 的 `lastBuildResult`/`lastCrashSummary`/`taskCount` 均为 `remember { }` 无 key → 提交后 UI 永不刷新（ServerPanel.kt:198/203/56）。
- B2 `submitBuildRequest` 静默写 json，无 toast/无状态，且不进入 Agent 任务流 → 排队/编译/安装/拉起全程不可见（ServerPanel.kt:261）。
- B4 编译日志写到 `.aidev-build-bridge/logs/build-<id>.log`，UI 从不读取；失败仅显示「构建失败(exit=N)」，看不到 gradle 报错。

**生命周期 / 可靠性**
- B3 BuildBridge/CrashReportBridge 仅在 `SessionManager` 启动终端会话时 `start()`（SessionManager.kt:93-94）→ 未进终端或会话被回收时，请求无人轮询、无限挂起。
- B6 bridges 是进程内协程（非前台 Service），App 被 HyperOS 杀后闭环中断；长构建（最长 900s）期间尤其危险，需确认 KeepAliveService 覆盖。
- B8 编译前置依赖多（宇宙B rootfs ready + JDK17 + gradlew + workspace 有项目），首次 `install-compiler`（最长 600s）静默进行，UI 无进度；workspace 无项目直接失败。

**时序 / 健壮性**
- B5 崩溃回流靠固定 `delay(8000)` 只抓一次（BuildBridgeService.kt:138）→ App 启动慢或 8s 后崩溃则漏抓。
- B9 `parseCrash` 用「Exception/Error」关键字兜底易误报，`raw.takeLast(2000)` 可能截断关键栈（CrashReportBridgeService.kt:88）。**✅ 已解（v41/2026-07-12）**：改用 `-b crash` 缓冲区 + 按 `Process:<pkg>` 定位崩溃块 + 去自污染，真机验证抓到真实堆栈。详见 error-journal「D4 崩溃回流」。

**架构一致性**
- B7 两套「任务」并存且互不相干：新 Agent 任务系统（AgentTaskRunner，跑宿主 sh）与 BuildBridge（跑宇宙B PRoot），ServerPanel 里是两个独立列表 → F04 需统一视图。

结论：F02/F03 的核心正是消灭 B1/B2/B4（把构建请求接入可见的任务流 + 状态机 + 日志），并顺带处理 B3（提交时确保 bridge 在跑）。
- [x] F02 `submitBuildRequest` 接入可见状态反馈（复用 AgentTaskRecord / 任务系统），点了要「看得见在转」
      实现：新增 `agent/BuildRequestTracker.kt` —— 提交时确保 BuildBridge/CrashReportBridge 已启动（解决 B3）、写 req-<id>.json、
      立即插入 RUNNING 任务记录，并后台轮询 `logs/build-<id>.log`（实时日志）+ `result-<id>.json`（最终结果）持续刷新 UI（解决 B1/B2/B4）。
- [x] F03 构建请求状态机：排队 / 编译中 / 安装中 / 已拉起 / 失败，在 ServerPanel 展示进度与日志
      实现：BuildRequestTracker 从构建日志推导 4 阶段（准备宇宙B → 编译 → 安装 → 拉起），映射为 AgentTaskStepResult，
      复用既有 AgentTaskRow 渲染每步状态 + 实时日志 + exit；20 分钟超时兜底。可后续细化阶段标记正则。

## 轨道 2 — 闭环可观测（信任基础）
- [x] F04 宇宙B 构建日志 + 安装结果 + 崩溃回流，统一进「服务器中心」任务流视图
      实现：BuildRequestTracker 提交后轮询 `logs/build-<id>.log` + `result-<id>.json` 刷新任务流（B1/B2/B4）；
      拉起成功后 `watchCrashReport` 监听 `.aidev-mcp/crash-*.json`，回流为独立任务记录「崩溃回流 <pkg>」（F04 核心）；
      ServerPanel 的「最近崩溃回流」随 tracker.latestCrash 实时刷新（原静态 remember 已改为 LaunchedEffect 轮询）。
- [x] F05 崩溃自动回流端到端验证：构建含 crash 的测试包 → 触发 → 崩溃报告自动出现在闭环视图
      实现：新增 `BuildRequestTrackerTest`（合成 crash-*.json 验证 FAILED 记录含堆栈；空 stack 验证 SUCCEEDED「未捕获到崩溃」）；
      并为此修复测试环境：`app/build.gradle.kts` 加 `testOptions.unitTests.isIncludeAndroidResources=true` 与 `testImplementation("org.json:json:20231013")`，
      BuildRequestTracker.mainHandler 改为延迟初始化 + null-safe `postToMain`（单测不构造主线程 Looper）。testDebugUnitTest ✅ / assembleDebug ✅。

## 轨道 3 — 收敛长尾
- [x] F06 冻结 pending_optional 长尾（除非影响主闭环），在 decisions.md 记录冻结判据
      判据见 `decisions.md`「Phase F pending_optional 冻结清单」。核心：B6/B8/B9 等属可靠性长尾，默认冻结；
      仅当「阻断一次真实闭环跑通 / 用户实测报错」才解冻。

### pending_optional（已冻结，2026-07-11）
- B6 bridges 改前台 Service / 确认 KeepAliveService 覆盖长构建（HyperOS 杀进程风险）。—— 冻结：当前进程内协程在终端会话内可用，闭环骨架已通；前台化是可靠性增强，非阻断。
- B8 install-compiler 首次静默无进度。—— 冻结：属「首次环境搭建」一次性的长任务，非主闭环（改码→构建→反馈）路径，用户可在终端看见。
- B9 parseCrash 误报/截断健壮性。—— 冻结：当前关键字兜底够用；优化属长尾，待实测误报率高再解。
- 阶段标记正则细化（F03 备注）。—— 冻结：当前 4 阶段推导已覆盖主路径，精细化不阻断可见性。
- 多构建请求并发管理（同一项目排队 / 不同项目并行）。—— 冻结：当前单 tracker 实例 + 每请求独立线程已够用，待真实并发需求。
- 「查看崩溃报告」按钮仍走 onExecuteCommand（与任务流并存）。—— 冻结：保留冗余入口无害，收敛成本 > 收益。

### 未解冻条件（触发后再开工）
- 用户实测一次完整闭环（改码→提交→安装拉起→崩溃回流）出现任一断点 → 对应项解冻。
- 后台长构建（>5min）实测被 HyperOS 中断 → 解冻 B6。
- crash 报告实测误报率高 → 解冻 B9。

## 已完成（本轮）
- [x] Agent 任务系统补全：AgentPlanEngine 分步执行（失败即停/取消）、实时增量日志、步骤持久化；修复 exit=$$ 与死代码
- [x] ServerPanel 入口修复：新增底部上滑手势打开「服务器中心」+ 第三指示点
- [x] F01 断点清单（见上）
- [x] F02+F03 构建请求可见化：BuildRequestTracker 接入任务流 + 4 阶段状态机 + 实时日志（testDebugUnitTest ✅ / assembleDebug ✅）

---

# （历史）当前任务：双 PRoot rootfs + AI 自我进化闭环（重构）

> ⚠️ 长线重构任务。完整计划与阶段清单见 **`docs/refactor-dual-rootfs-plan.md`**，harness 状态见 `.harness/session-state.json`。
> 各阶段顺序：P0(地基) → P1(双宇宙+workspace) → P2(编译+文件桥) → P3(安装+拉起) → P4(logcat→MCP) → P5(UI) → P6(验证)。

---

# （历史）当前任务：Phase E — UI Layout Audit & Fixes

## 已完成
参见之前的 `current-task.md`，涵盖 Phase A~D（Features、Stub Filling、Cleanup、State Splitting）。

## Phase E — UI 布局审计与修复（2026-06-30）

### 背景
UI 全面审计发现 **19 项问题**，从 P0（功能/合规）到 P3（代码质量）。以下逐一修复。

### P0 — 功能与合规（严重）

- [x] E01 — WindowInsets 处理（ShellActivity + 所有 Page）
  - 结论：ShellActivity 已有 `setDecorFitsSystemWindows(false)` + IME 监听；AppNavHost 有 `windowInsetsPadding(WindowInsets.systemBars)`。各 Page 在外层 Scaffold 保护下无遮挡问题。关闭。

- [x] E02 — PreviewManager 触摸目标 < 48dp
  - 审计：关闭按钮已 48dp + Material Icon ✅；TabChip 48dp ✅；箭头改用 Material Icons（KeyboardArrowLeft/Right）+ 48dp IconButton ✅
- [x] E10 — PreviewManager Unicode → Material Icons（ArrowLeft/ArrowRight + 48dp touch target）

- [x] E03 — FilesPanel 空/加载/错误状态
  - 审计：空目录 "这里还是空的" ✅；加载态 CircularProgressIndicator ✅；错误红色提示 + 重试按钮 ✅（文件管理器重构时一并修复）

- [x] E04 — FilesPanel PathBar 水平滚动
  - 审计：`horizontalScroll(rememberScrollState())` 已在 PathBar Row ✅

- [x] E05 — TerminalPanel 键盘按键 26dp 过小
  - 审计：实际已是 36dp（大于 26dp），KEYBOARD_HEIGHT = 88dp ✅

- [x] E06 — TerminalPanel 背景硬编码 Color.Black
  - 审计：全文件无 `Color.Black`，均已使用 `MaterialTheme.colorScheme` ✅

- [x] E07 — 5 个自定义 LazyColumn 对话框缺滚动保护
  - 审计：所有对话框均包裹在 `AlertDialog`/`ModalBottomSheet` 中，LazyColumn 有 `heightIn(max=...)` 限制 ✅

### P1 — 一致性与体系

- [x] E08 — 统一水平 padding 为 16dp
  - 方案：统一为 16dp，AppActionRow/AppSectionHeader 的 18dp→16dp
  - 结论：AppActionRow/AppSectionHeader 已是 16dp ✅；SettingsPanel/TerminalPanel/ProjectDetailPanel/BrowserHost/KnowledgeBasePanel/ProjectScaffoldPanel/BackupRestoreDialog 等 15 处统一到 16dp ✅

- [x] E09 — 定义 Typography 主题
  - 方案：定义 `AITypography` 替换所有硬编码 fontSize
  - 新建 `Type.kt`，替换 ~160 处 fontSize 为 MaterialTheme.typography，覆盖 19 个文件 ✅

### P2 — 代码质量

- [x] E10 — PreviewManager Unicode → Material Icons
  - 方案：`◀`→`Icons.AutoMirrored.Filled.ArrowBack`、`▶`→`Icons.AutoMirrored.Filled.ArrowForward`、`✕`→`Icons.Default.Close` ✅

- [x] E11 — SshBookmarksSheet modifier 作用域修复
  - 方案：modifier 从内部 Column 移到 ModalBottomSheet 根节点 ✅

- [x] E12 — NotificationHistoryDialog 末尾多余分割线
  - 结论：已有 `if (index < items.lastIndex) HorizontalDivider()`，无问题，关闭 ✅

- [x] E13 — ServerPanel 非响应式状态
  - 方案：4 处加 `remember` 避免重复计算 ✅

- [x] E14 — PreviewManager 子组件未使用 modifier 清理
  - 方案：6 个子组件移除 unused `modifier: Modifier = Modifier` 参数 ✅

### P3 — 小问题

- [x] E15 — SettingsMenuConfigurator 未使用 context 参数
  - 结论：所有 context 参数均有实际用途，无问题，关闭 ✅

- [x] E16 — TerminalPanel 键盘阈值单位统一（px vs dp）
  - 方案：`72 * density` → `with(LocalDensity.current) { 72.dp.toPx() }` ✅

- [x] E17 — BackupRestoreDialog 错误反馈缺失
  - 方案：`catch (_: Exception) { }` → 设置 errorMessage ✅

## 文件管理器重构（2026-06-30 完成框架搭建）

### 新增文件（6 个）
| 文件 | 说明 |
|------|------|
| `BrowserHost.kt` | 顶层外壳：模式 Tab（📁文件/📦项目） + 双栏布局 |
| `FileRow.kt` | 文件行：两行布局 + 左滑/右滑/长按手势 + 类型图标 |
| `PathBar.kt` | 路径栏：面包屑 + 左滑后退 + 点击段跳转 |
| `ProjectListPanel.kt` | 项目列表：扫描 Workspace，卡片展示，搜索过滤 |
| `ProjectDetailPanel.kt` | 项目详情：信息卡 + 操作按钮 + APK 列表 |
| `SwipeActions.kt` | 可复用左右滑动手势封装 |

### 修改文件（4 个）
| 文件 | 改动 |
|------|------|
| `FilesPanel.kt` | 精简重写：去掉工具栏/搜索栏/多选栏；补充 E03 加载/错误状态 |
| `FileBrowserState.kt` | 添加导航历史栈 + 项目管理状态（projectMode/projectList/selectedProject） |
| `ProjectDetector.kt` | 添加 `isAndroidProject()`、`findAndroidProjects()`、`getProjectMeta()` |
| `AppNavHost.kt` | `FilesPanel` → `BrowserHost` |

### 已验证
```bash
./gradlew :app:compileDebugKotlin --no-daemon       # BUILD SUCCESSFUL
./gradlew :app:assembleDebug --no-daemon             # BUILD SUCCESSFUL
```

### 待实现细节（可选）
- 文件行右滑预览内容精细化
- 文件行左滑"复制到对侧"自动触发（≥80dp）
- 文件列表/网格视图双指缩放切换

## 已知问题（记录于 docs/error-journal.md）
- LinearProgressIndicator(float) deprecated（BackupRestoreDialog.kt:91）
- Compose UI 测试需 Android 运行时 / Robolectric
- GitStateTest 缺 @OptIn、DialogTypeTest 多余检查、extractNativeLibs 警告

## 全面审计修复（2026-07-01）

### 背景
Phase E 完成后，用 5 个并行 agent 对全项目进行 性能/协程/资源/空安全/Compose 状态 审计，发现 **6 个 P0（崩溃/泄漏）+ 9 个 P1（Bug/性能）+ 5 个 P2（代码质量）**问题。

### Batch 1 — P0 崩溃/泄漏（✓ 编译通过 + APK 构建成功）

- [x] B1 — PreviewManager WebView 泄漏：`DisposableEffect` + `removeAllViews()` + `destroy()`
- [x] B2 — SystemMonitorDialog 每次重组都调用 `startPolling()`：→ `LaunchedEffect(Unit)` 包裹
- [x] B3 — SystemMetricsCollector `line!!` NPE 风险：→ 安全调用 + `.use {}` 关闭流
- [x] B4 — SftpService `as ChannelSftp` 类型转换崩溃：→ `as?` + 提前返回错误

### Batch 2 — P1 资源/性能/异常（✓ 编译通过 + APK 构建成功）

- [x] B5 — ProjectTreeView `buildFlatList()` 每次重组都读文件系统：→ `remember(root, expanded, activePath)`
- [x] B6 — SFtpPanel `remember {}` 在 LazyColumn 中缺 key：→ 添加 `key = { it.id }`
- [x] P1-1 — GitState 8 个方法不检查 exitCode → 统一设置 `errorMessage`
- [x] P1-2~5 — 资源泄漏修复（BackupRestoreDialog / OpenCodeMonitorService ×2 / OpenCodeActionReceiver → `.use {}` 闭包）
- [x] P1-6~9 — LazyColumn 12 处 `items()` 缺 `key` 参数（ProjectScaffoldPanel / TerminalSearchPanel / NotificationHistoryPanel / KnowledgeBasePanel ×3 / ContainerManagerPanel / GitPanel ×3 / SFtpPanel ×2）
- [x] P1-10 — AIDevCommandDispatcher 2 处 `exec()` 流未消费

### Batch 3 — P2 代码质量（✓ 编译通过 + APK 构建成功）

- [x] P2-1 — ShizukuLogcat `line?.let` → `line!!.let`（while 循环后 guaranteed non-null）
- [x] P2-2 — SystemMetricsCollector `line?.trim()` → `val l = line ?: continue; l.trim()`
- [x] P2-3 — CompletionEngine 合并连续 `inputProxy?.` 为单一 `?.let` 块
- [x] P2-4 — SessionManager `current?.session` 保留（var 不可 smart-cast）

---

## 2026-07-01 — Terminal 标签切换冻结修复

### 问题
从 Terminal 切换到 Files 再切回，5~10 秒后 App 被 HyperOS 杀进程（无 FATAL EXCEPTION/ANR）。

### 根因
`AndroidView(factory = { coreView })` 在 NavHost 路由切换时销毁重建 → `TerminalView.onDetachedFromWindow()` / `onAttachedToWindow()` → EGL 原生表面创建阻塞主线程 → HyperOS 静默杀进程。

### 修复（2 文件）
- **`AppNavHost.kt`**：TerminalPanel 移出 NavHost，作为外层 `Box` 永久 overlay
- **`TerminalPanel.kt`**：`visible` 参数控制；`View.INVISIBLE` 隐藏而不 detach；`DisposableEffect` 不再依赖 page；`LaunchedEffect(visible)` 处理 tab 选中初始化

### 状态
- `compileDebugKotlin` + `assembleDebug` → BUILD SUCCESSFUL ✅
- APK 安装成功，用户实测 Tab 切换不再卡死 ✅

## 2026-07-01 — Terminal 交互优化 + 菜单重构

### 变更清单
| # | 改动 | 文件 |
|---|------|------|
| 1 | 命令补全 `completionSnapshot` 改为 `State` 类型，IME 活跃时实时刷新 | `EmbeddedShellPages.kt` |
| 2 | 字号调节：长按标签 → 拖拽 → 松手关闭（取代 AlertDialog+Slider） | `TerminalPanel.kt` |
| 3 | 新增 `dragAccumulator` 累加器，慢拖不丢帧 | `TerminalPanel.kt` |
| 4 | 每跳 1sp 触发震动反馈（`decorView.performHapticFeedback`） | `TerminalPanel.kt` |
| 5 | 菜单：平铺 AlertDialog → ModalBottomSheet + 分组折叠 | `TerminalPanel.kt` |
| 6 | 删除 6 项冗余：清屏/Ctrl+C/Ctrl+D/端口检查/通知历史/备份 | `TerminalPanel.kt` |
| 7 | 新增 4 项：主题折叠列表/清回滚/重置终端/容器管理 | `TerminalPanel.kt` |

### 已知残留
- `通知 · 历史记录` 移出终端菜单后无可访问入口，需在 SettingsPanel 补充
- `LinearProgressIndicator(float)` deprecated（`BackupRestoreDialog.kt:91`）
- Compose UI 测试需 Android 运行时 / Robolectric
- GitStateTest 缺 @OptIn、DialogTypeTest 多余检查、extractNativeLibs 警告

### 已验证
- `compileDebugKotlin` ✅
- `testDebugUnitTest` ✅
- `assembleDebug` ✅

## 2026-07-01 — 文件管理器交互优化 + 回收站 + 隐藏文件设置

### 多选交互细化
- 点击未选中文件（多选模式）→ 从 anchor 范围选择到该文件
- 点击已选中文件（多选，非唯一）→ 仅取消该文件
- 点击已选中文件（多选，唯一）→ 退出多选模式
- anchor 固定为第一个选中项
- 右滑（原入口）、点击（未选中）、长按（未选中）均走 `handleFileSelect`

### 焦点切换清除多选
- `activeLeft` setter 新增焦点检测：焦点变化且多选活跃 → 清除选择

### 回收站
- `.trash` 目录（或父目录为 `.trash`）文件 → 永久删除（不移入回收站）
- `useTrash` 设置（PreferencesManager，默认 true）控制删除是否入回收站
- 对话框根据设置显示不同提示语

### 文件管理器设置
- PreferencesManager: `useTrash`, `fileSortMode`, `fileShowHidden`, `fileConfirmDelete`, `fileTrashRetention`
- Constants.kt: PrefKeys 常量
- SettingsMenuConfigurator.kt: `fileManagerMenu`
- FileBrowserState: init 从 prefs 加载 `sortMode` / `showHiddenFiles`；`refreshFromPrefs()` 方法；toggle 设置时同步更新运行时状态
- FilesPanel.kt: `showHiddenFiles` 过滤隐藏文件（`.` 开头的文件）
- SettingsPanel.kt: 文件管理器设置折叠区域 + SortMode/TrashRetention 对话框
- SettingsUiState.kt: `SortMode` / `TrashRetention` dialog sealed class

### 其他
- 触感反馈（pane 焦点切换）
- PathBar 焦点切换时即时更新
- StatusBar 简化（移除冗余路径）
- 项目模式自动关闭多选栏

### 已验证
- `compileDebugKotlin` ✅（BUILD SUCCESSFUL）
- `testDebugUnitTest` ✅（78 tests PASSED）
- `assembleDebug` ✅（BUILD SUCCESSFUL）
- Shell tests ✅（54 passed, 0 failed）

## 2026-07-01 — 性能优化 & Bug 修复 Batch

### 问题 1 — HTML 预览滑动穿透（P0 修复）
- **根因**：PreviewPanel 外层 `Box` 无触摸拦截，触摸穿透到下方 LazyColumn
- **修复**：`BrowserHost.kt:99` 添加 `clickable(interactionSource, indication=null)` 消费触摸事件
- **类似组件审计**：TreeDrawer ✅、SearchOverlay ✅ 均已有拦截；无其他类似问题

### 问题 2 — 非文本文件预览卡死（P0 修复）
- **根因**：`PreviewManager.kt` 对非图片/HTML 文件直接 `file.readText()`，二进制文件（.apk .zip .so）导致 OOM 或乱码
- **修复**：`LaunchedEffect` 添加 `file.length() > 5MB → 文件过大` + `!isLikelyText(file) → 二进制文件` 检查

### 问题 3 — 文件浏览器首次打开卡顿（P1 优化）
- **瓶颈 A**：`FileRow.kt:76` 每个目录调用 `file.listFiles()?.size`（30 目录 = 30 I/O）→ 改用 `state.childCount()` 预计算
- **瓶颈 B**：`loadPane()` 两次 `listFiles()` 调用 → 合并为一次
- **瓶颈 C**：`updatePathBar()` 内部第三次 `listFiles()` → 添加 `files` 参数复用已有列表
- **新增**：`_childCounts` 映射表 + `childCount()` 方法，在 `loadPane()` 中用轻量 `file.list()?.size` 预填充

### 问题 4 — 粘贴功能恢复（P1 修复）
- **根因**：`pasteClipboard()` / `hasClipboardItems()` 已实现但无 UI 入口
- **修复**：`FileRow.kt` ActionSheet + `BrowserHost.kt` MultiSelectBar 添加"粘贴"按钮（仅在 `hasClipboardItems()` 时显示）

### 问题 5 — 清空回收站无入口
- **修复**：`SettingsMenuConfigurator.kt` 文件管理器菜单添加"清空回收站"条目

### 修改文件清单
| 文件 | 改动类型 |
|------|---------|
| `BrowserHost.kt` | PreviewPanel 触摸拦截 + MultiSelectBar 粘贴按钮 |
| `PreviewManager.kt` | 二进制/大文件预览保护 |
| `FileBrowserState.kt` | 重复 listFiles 合并 + 子目录计数缓存 |
| `FileRow.kt` | childCount 替代 listFiles + 粘贴 ActionSheet 项 |
| `SettingsMenuConfigurator.kt` | 清空回收站菜单项 |

### 验证
- `compileDebugKotlin` ✅
- `testDebugUnitTest` ✅（78 tests）
- `assembleDebug` ✅
- Shell tests ✅（54 passed）

---

## Phase G — 反向驱动宇宙 A（文件契约，2026-07-11 完成）

### 问题
Phase F 只做到「崩溃回流显示在任务流」——最后一步「改码」仍要人肉接手。且崩溃数据写在 App 沙箱 `home/.aidev-mcp/`，
OpenCode（宇宙 A）在 `home/workspace` 看不到，两者无法协作。

### 解：共享工作区文件契约（不依赖 IPC/网络）
- G01 `BuildRequestTracker.publishCrashRecord` 拉起抓到崩溃后，**同时写** `home/workspace/.aidev-loop/crash-<ts>.json`
      （含 `type/crashed/fix_applied/project/stack`），宇宙 A 可读。
- G02 `AgentTaskRecord` 加 `note` 字段（OpenCode 回填修复说明），`AgentTaskStore` 序列化向后兼容（第 14 字段，缺则默认 ""）。
- G03 `BuildRequestTracker.requestRebuild(...)` 暴露给宇宙 A：改完码触发下一轮「宇宙B 编译→安装→拉起→抓崩溃」。
- G04 写 `docs/self-evolution-loop.md`：三文件契约（崩溃回流 / 重建触发 / 源码位置）+ 宇宙 A 行为建议 + 防失控。
- G05 写 `scripts/aidev-self-evolution`：宇宙 A 自动驾驶参考实现（监听 `.aidev-loop` → 调 OpenCode 改码 → 写 req 触发重建；`--max-iter` 防失控、空闲重试）。

### 修改文件
| 文件 | 改动 |
|------|------|
| `agent/BuildRequestTracker.kt` | writeLoopCrash 写共享工作区 + requestRebuild + 去掉 mainHandler 依赖 |
| `agent/AgentTaskStore.kt` | AgentTaskRecord.note 字段 + 序列化第 14 位（向后兼容） |
| `docs/self-evolution-loop.md` | 新增：文件契约文档 |
| `scripts/aidev-self-evolution` | 新增：宇宙 A 自动驾驶参考脚本 |
| `app/src/test/.../BuildRequestTrackerTest.kt` | 新增：工作区写出 + requestRebuild 单测 |

### 验证
- `testDebugUnitTest` ✅（新增 3 用例：工作区崩溃文件 / requestRebuild 写 req / note 向后兼容）
- `assembleDebug` ✅

### 边界
- 未在 App 内做「自动无限循环」：是否全自动由宇宙 A 侧（脚本/OpenCode）决定，App 只负责把契约文件摆好。
- 真机端到端需设备 + Shizuku 实测（本环境仅文件契约 + 单测覆盖）。

---

## 自治开关（Phase G 追加，2026-07-11）

在「服务器中心」增加「自我进化自治模式」开关，用户一键开启后闭环自转。

### 实现
- A01 `Constants.PrefKeys.SELF_EVOLUTION_AUTONOMOUS` + `PreferencesManager.selfEvolutionAutonomous`（默认关）。
- A02 `BuildRequestTracker`：`submit(autonomous=)` 透传到 `watchCrashReport`；自治模式下崩溃 `fix_applied=false` 时
      自动 `requestRebuild` 触发下一轮，靠 `fix_applied=true` 或 `MAX_AUTO_ITERATIONS=10` 收敛（防失控）。`watchCrashReport`/`publishCrashRecord` 改 `internal` 便于单测。
- A03 `ServerPanel` 加 Switch（读取/写入偏好），提交构建请求时传 `autonomous = autonomousOn.value`。
- A04 单测：`autonomousWatchTriggersRebuildOnUnfixedCrash` / `autonomousWatchStopsWhenCrashFixed` / `manualWatchDoesNotAutoRebuild`。
- A05 文档：`docs/self-evolution-loop.md` 第 6 节 + `decisions.md` Phase G 决策。

### 边界
- App 只"自动再构建"，改码仍由宇宙 A（OpenCode / aidev-self-evolution）完成；开关让"崩溃→重建"自动转，配合常驻 OpenCode 即无人值守闭环。

---

## 守护进程（Phase G 追加·续，2026-07-11）

把 `aidev-self-evolution` 从"一次跑一轮"升级为常驻守护，补齐"自动改码"那一半。

### 改动（scripts/aidev-self-evolution）
- 加 `--daemon` / `--stop` / `status`：nohup 后台常驻 + pid 文件 + 日志；`daemonize` 后 `exit 0` 避免父进程残留。
- 主循环由"一次"改为常驻：每 5s 扫描 `.aidev-loop/crash-*.json`，对 `crashed=true & fix_applied=false` 的逐个调 `opencode run --attach <url>` 改码。
- 改码后把崩溃文件 `fix_applied` 置 true（防重复修）+ 写 `req-<id>.json` 触发下一轮；OpenCode 失败则跳过等重试。
- 参数：`OPENCODE_URL`（默认 4096）、`OPENCODE_CMD`（可覆盖调用方式）、`AIDEV_WORKSPACE`、`--max-iter`、`--once` 调试。
- 修复早期实现的两个 bug：包名/项目名提取 sed 错误（导致 req json 字段错乱）；`status` 子命令未匹配。

### 验证
- 用 fake-OpenCode 跑通：守护扫描→调改码→标记 fix_applied→写重建请求；`--daemon` 启停、`status` 正常；pid 进程 stop 后确已退出。
- 文档 `docs/self-evolution-loop.md` 第 7、8 节补全守护用法与双触发说明。

---

## Phase G — 反向驱动宇宙 A（文件契约 + 自治 + 守护，2026-07-11 完成并推送）

### 交付
- G01 崩溃回流写共享工作区 `home/workspace/.aidev-loop/crash-<id>.json`
- G02 `AgentTaskRecord.note` 字段（OpenCode 回填修复说明）
- G03 `BuildRequestTracker.requestRebuild()`
- G04 `docs/self-evolution-loop.md` 文件契约
- G05 `scripts/aidev-self-evolution` 参考实现
- A01–A05 自治开关：`Constants`/`PreferencesManager` 偏好 + `ServerPanel` Switch + `BuildRequestTracker` 自治循环（`fix_applied` 收敛 + `MAX_AUTO_ITERATIONS=10`）
- 守护进程：`aidev-self-evolution --daemon/--stop/status` 常驻自动改码（fake-OpenCode 验证全链路）

### 验证（本环境）
- `testDebugUnitTest` ✅（含 BuildRequestTracker 工作区写出 / requestRebuild / 自治三场景 / note 向后兼容）
- `assembleDebug` ✅
- 守护进程 fake-OpenCode 跑通：扫描→改码→标记 fix_applied→写重建请求→启停/status 正常

---

## Phase H — 验证与待办（2026-07-11 起）

### H 本环境可验（已完成）
- [x] H03 全量 `testDebugUnitTest` + `assembleDebug` 绿灯
- [x] H04 `scripts/verify-self-evolution.sh`：fake OpenCode 模拟完整闭环，验证文件契约
- [x] H05 `docs/verification.md` 补 Phase H 真机验证清单

### H 本环境可验（执行中：真机实测步骤落地）
- [x] H10 本文件：真机实测拆为 H10–H17 分步，标注本环境可验 / 需真机
- [ ] H11 写 `docs/real-device-runbook.md`（照抄版真机步骤手册）
- [ ] H12 用**真实 opencode serve**（本环境已有 v1.17.18）+ 守护 验证「崩溃→真实改码」闭环
- [ ] H13 跑 `scripts/harness_check.sh` 确认文档/结构完好

### H 需真机 / Shizuku（用户实测，冻结为待办，非漏做）

> 以下把 H14–H17 拆成 **A–G 七组、共 24 个原子任务**，每组可独立勾验；全部完成即闭环跑通。
> 冻结判据不变：默认冻结，仅当真实断点/误报/中断率实测偏高才解冻动工。

#### 组 A — 开发机侧产物准备（无需真机，可现在做）
- [x] A1 产物校验（2026-07-11）：APK 19MB / 包名 `com.aidev.six.dev` / 含 `lib/arm64-v8a/*.so` / `application-debuggable=true` / 权限齐全（Shizuku/IPC/安装/通知/电池免优化/开机）。
        - ⚠️ 注：`aidev-apk-info` 有 3 个解析 bug（见下方"已知工具缺陷"），**勿直接信其输出**，以 `aapt2 dump badging` 真值为准。
        - 产物路径：`app/build/outputs/apk/debug/app-debug.apk`（已存在，无需重 build）
- [x] A2 APK 就位（2026-07-11）：本机同设备无需"推送"，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`（19MB）
- [x] A3 宇宙B 项目复核（2026-07-11）：`~/workspace/MyAndroidProject/gradlew` 存在 + `mipmap/ic_launcher*` 资源就位；
        `./gradlew assembleDebug -Paapt2FromMavenOverride=/Android/build-tools/34.0.0/aapt2` → **BUILD SUCCESSFUL**（45s，包名 com.aidev.six.dev 可覆盖安装）

#### 组 B — 推包 + Shizuku 静默安装（需真机 + 桥接）
- [x] B1 桥接通道确认（2026-07-11）：`aidev-shizuku status` / `aidev-install --status` 均显示"桥接通道正常"
- [x] B2 推送 APK：`aidev-install` 内部复制到 `/sdcard/aidev-install-tmp.apk` 完成
- [x] B3 Shizuku 静默安装（2026-07-11）：`aidev-install --silent` → 升级到 **versionCode=14 / `1.0.0-b14-7524e87-dirty`**
- [x] B4 安装校验（2026-07-11）：`dumpsys package com.aidev.six.dev` → versionCode=14，升级成功

#### 组 C — 首次启动与保活（真机）
- [ ] C1 启动 App；若 Shizuku 授权弹窗则授予（aidev6 包）
- [ ] C2 App 加电池免优化：设置→应用→aidev6→ battery → Unrestricted
- [ ] C3 宇宙A 宿主进程（OpenCode）同样加电池免优化，避免被 HyperOS 杀
- [ ] C4 确认手机端 Shizuku 服务运行且 aidev6 已获授权（C1 成功即证）

#### 组 D — 人工触发一次闭环（验证 4 阶段可见性）
- [ ] D1 底部上滑进「服务器中心」，确认入口与第三指示点正常
- [ ] D2 点"提交构建请求"，任务流出现 RUNNING：观察 4 阶段（准备宇宙B→编译→安装→拉起）实时日志与进度
- [ ] D3 确认 APK 被覆盖安装并自动拉起（手机跳到 aidev6 / 新进程；看 `am start` 生效）
- [ ] D4 若崩溃：确认「崩溃回流 <pkg>」任务记录出现 + `home/workspace/.aidev-loop/crash-*.json` 写出（契约 G01 生效）
- [ ] D5 任一阶段 failed → 回 H15 根因清单（目录嵌套/图标/ aapt2 覆盖）逐项排查，修完重跑 D2

#### 组 E — 宇宙A 常驻（开发机/宿主终端）
- [~] E1 宿主 OpenCode 服务：`opencode serve --port 4096 &`（v1.17.18，pid 15127，端口监听正常）。
        ⚠️ 这只是"宇宙A 宿主"就位，**不等于验证通过**；且需与 aidev6 沙箱工作区路径一致才生效（见下方"重大修正"）。
- [~] E2 端口监听确认：`curl :4096` 返回 HTML。同上，仅宿主侧就绪。
- [ ] E3 起守护：`aidev-self-evolution --daemon`（**未启动**；先停了——需先确认 aidev6 环境就绪 + D 组手动闭环跑通，且守护会自动改码需你确认）
- [ ] E4 `aidev-self-evolution status` 确认运行中（当前：未运行）

#### ⚠️ 2026-07-11 重大修正（实机验证前置未满足）
- 设备存在多个 aidev 包：`com.aidev.six.dev`（新 aidev6，无环境）、`com.aidev.five.dev`（前台运行、已带 ubuntu-rootfs）、
  `com.aidev.terminal(.dev)`、`com.aidev.opencode`、`com.aidev.four.dev`。
- 新 aidev6(`com.aidev.six.dev`) 沙箱**未初始化**：无 ubuntu-rootfs / JDK / gradlew / workspace 项目（即 F01 的 B8 "install-compiler 首次静默"尚未发生）。
- 之前我在**本 agent PRoot(`/root/workspace`)** 编的项目，aidev6 沙箱里没有 → 宇宙B 编译会失败在"准备宇宙B"。
- 结论：A2/B1–B4（装 App）已完成，但**闭环验证（D/F 组）尚未开始**，须先让 aidev6 初始化环境并在其沙箱内建好项目。
- 待澄清/执行：① 用户打开 aidev6 让其初始化环境（可能需下载 rootfs / install-compiler，耗时较长）；
  ② 在 aidev6 沙箱 workspace 内创建/放置 MyAndroidProject；③ 再走 D 组手动闭环。
- 宿主 opencode serve 仍保留作 宇宙A；但 `AIDEV_WORKSPACE` 须指向 aidev6 实际共享工作区（未必是 `/root/workspace`），否则契约对不上。
- **新增阻塞点（已查代码确认）**：
  1. **Android SDK 无自动安装**：`BuildBridgeService` 硬编码 `ANDROID_SDK_ROOT=/host-home/android-sdk`，无下载逻辑；全新 aidev6 该目录大概率为空 → 编译必败。
     **已定方案 (b) sdkmanager 安装**（用户 2026-07-11 选定）：命令已写入 `docs/aidev6-init-runbook.md` 步骤 3（cmdline-tools + `platforms;android-36` + `build-tools;36.1.0` + `platform-tools`，清华镜像）。备选 (a) 复制 five.dev / (c) 改指向 `/Android`。
  2. **宇宙A OpenCode 拓扑错位**：自进化的 宇宙A 必须跑在 aidev6 沙箱 `home/ubuntu-rootfs` 内、读写同一 `home/workspace`；外置 agent 环境的 `opencode serve` 无效（已停掉我误起的那个）。
  3. 宇宙B 编译器与项目模板在首次"提交构建请求"时由 `ensureCompilerRootfs` + `scaffoldProject` **自动**补齐（含正确 mipmap 图标，H15 bug 在 App 内脚手架已修），但首次最长 ~20 分钟静默（长尾 B8）。
- **操作手册**：`docs/aidev6-init-runbook.md`（手机端初始化分步 + 风险 + 与 A–G 对应）。本环境仅能查机制，无法代做设备侧初始化。

#### 🐞 实机暴露并修复的 bug（2026-07-11）
- **症状**：aidev6 终端跑 `setup-dev-env` 报 `proot 不存在：/data/app/.../com.aidev.six.dev-.../lib/arm64/libproot.so`。
- **根因**：`libproot.so`/`libproot_loader.so` 仅作为 jniLibs 原生库，指望系统按 `extractNativeLibs=true` 解包到 `nativeLibraryDir` 后当可执行文件被 shell 调起；但 Android 16 上未解包（且原生库默认不可执行），脚本找不到。
- **修复**：与 `libtalloc`/`libandroid-shmem` 同机制——把 `libproot.so`/`libproot_loader.so`/`libproot_loader32.so` 作为 assets 打包，`installProotSupportLibraries` 启动时拷到 `home/proot-lib` 并 `setExecutable(true)`；`AIDEV_PROOT`/`AIDEV_PROOT_LOADER`/`PROOT_LOADER` 及 `ProotLauncher`/`SessionManager` 全部改用 `home/proot-lib`。
- **验证**：`assembleDebug` BUILD SUCCESSFUL（versionCode→15），已静默重装到设备；待用户重开 aidev6 进终端重试 `setup-dev-env` 确认 proot 就位。
 - 二次修复（2026-07-11）：exec 时 `Permission denied` —— proot 库已就位但缺 +x 位（Kotlin `setExecutable` 在 Android 静默失效）；
   本机 `files/home` 经 five.dev logcat 证明确可执行二进制（SELinux 对 app_data_file 的 exec 为 granted），故非 noexec/SELinux 问题。
   修法：引导脚本 exec proot 前加 `chmod 755 $AIDEV_PROOT $AIDEV_PROOT_LOADER` 兜底；重装 v16 验证。
 - 三次修复（2026-07-11，外部 AI 建议 code_cache）：v17 实机仍 `Permission denied` —— 本机对 code_cache 同样 W^X 拒绝 exec，方案作废。
 - **四次修复（2026-07-11，已定论并实机验证解包位置）**：唯一可靠可执行区 = **`nativeLibraryDir`**（APK `lib/<abi>/lib*.so` 由系统解包，label=apk_data_file 允许 exec）。
   proot DT_NEEDED = libtalloc.so.2 + libandroid-shmem.so + libc.so。libandroid-shmem.so 正常解包；libtalloc.so.2 版本化 soname 不解包 → 以 `libtalloc.so` 放 jniLibs，运行时在 `filesDir/home/proot-lib` 建符号链接 `libtalloc.so.2 -> nativeLibraryDir/libtalloc.so` 并入 LD_LIBRARY_PATH。
   已改：jniLibs 加 libtalloc.so+libandroid-shmem.so、删 assets/proot-libs；PathConfig.nativeLibDir(可执行)+prootLibDir(符号链接);TerminalShellAssets(建符号链接+rc LD_LIBRARY_PATH)、UbuntuBootstrapScripts(参数改 nativeDir+extraLibs、去 chmod)、ProotLauncher、SessionManager、DevEnvironmentChecker 同步。
   验证：`assembleDebug` SUCCESSFUL（**versionCode 18**），Kotlin 编译+63 shell 测试过；unzip 确认 5 库全在 lib/arm64-v8a；Shizuku 静默装 v18，实机 `ls nativeLibraryDir` 确认 5 库均 `-rwxr-xr-x`。APK → `/sdcard/AIDev/aidev6-v18-proot-nativelib.apk`。
   **✅ 实机终验通过**：重启 aidev6 跑 `ubuntu` 成功进宇宙A（`aidev[A]:/root#`）。PRoot 阻塞点彻底解除，D/F 组闭环验证可继续。
 - ⚠️ 构建须加 `-Pandroid.aapt2FromMavenOverride=/host-home/android-sdk/build-tools/34.0.0/aapt2`（36.1.0 的 aapt2 在 qemu 下损坏）。
 - 详见 `docs/error-journal.md`（已补）。

#### 组 F — 自治闭环验证（真机 + 宇宙A 联动）
- [ ] F1 手机「服务器中心」开"自我进化自治模式"开关（PreferencesManager.selfEvolutionAutonomous）
- [ ] F2 在 `home/workspace/MyAndroidProject` 留必崩 bug（如 `Application.onCreate` 抛异常 / `Main.kt:12` 空指针）
- [ ] F3 触发一轮：点提交 或 等守护/开关自动触发；观察 崩→守护改码(fix_applied=true)→App 自动重建→拉起→再抓
- [ ] F4 观察收敛：直到 `fix_applied=true` 后不再崩溃，或达 `MAX_AUTO_ITERATIONS=10` 上限停（防失控）
- [ ] F5 日志对照：守护 log（`~/.aidev-self-evolution.log`）与 App 任务流一致；无重复空转重编

#### 组 G — 收尾与回写
- [x] G1 跑 `scripts/harness_check.sh` 确认文档/结构完好（H13 落地，2026-07-11 Harness check passed）
- [x] G2 写 `docs/real-device-runbook.md`：把 A–F 实测细节固化成照抄手册（H11 落地，2026-07-11 已修正 aapt2 路径 + aidev-apk-info bug 警示 + A–G 对照）
- [ ] G3 回写 current-task.md：把 H14–H17 / A–G 标记完成；若实测出现断点则对应解冻并记 error-journal

#### 历史 H 条目映射（保留可追溯）
- [x] H15 宇宙B 构建根因已定位并修复（2026-07-11）：目录嵌套 + 缺 launcher 图标 + aapt2 覆盖；开发机 `./gradlew assembleDebug` BUILD SUCCESSFUL，包名 `com.aidev.six.dev`。⏳ 待真机 D2 复核 4 阶段。
- [ ] H14 = 组 B + 组 C（装 App + 进服务器中心）
- [ ] H16 = 组 E（OpenCode serve + 守护）
- [ ] H17 = 组 F（自治开关 + 造崩 + 收敛观察）

### 已知工具缺陷（待修，非 aidev6 仓库内）
- `aidev-create-android-project`：① 输出目录多套一级子目录（`<out>/<name>/` 而非 `<out>/`）；
   ② 生成模板缺 `mipmap/ic_launcher` 自适应图标资源，导致 `processDebugResources` aapt2 链接失败。
   影响：每次新建项目都需手动纠目录 + 补图标。下次重建项目前需先修此脚本或手动补。
- `aidev-apk-info`：3 个解析 bug，真机步骤 A1 校验**不可直接信其输出**，需以 `aapt2 dump badging` 为准：
   ① 包名误解析为 `platformBuildVersionName`（`16`）而非 `name='com.aidev.six.dev'`；
   ② Native ABI 显示空（实际 APK 含 `lib/arm64-v8a/*.so`）；
   ③ Debuggable 误报「否」（实际 `application-debuggable` 存在=true）。

### 判据
以上 H14–H17 待办"默认冻结"，仅当用户实测完整闭环出现断点、或某项误报/中断率实测偏高时才解冻动工（见 `decisions.md`）。

### 真机实测 6 步（大白话，详见 docs/real-device-runbook.md）
1. 装 App 到手机（Shizuku 静默装 debug APK）
2. 在「服务器中心」点一次"提交构建请求"，确认宇宙B 能编译安装拉起
3. 宇宙A 终端：`opencode serve --port 4096 &`
4. 宇宙A 终端：`aidev-self-evolution --daemon`（然后 `status` 看运行中）
5. 手机「服务器中心」开"自我进化自治模式"开关
6. 在 `home/workspace/MyAndroidProject` 留个必崩 bug → 点提交 → 看它自己 崩→改码→重建 直到不崩
坑：编译慢别急；把 App 和 OpenCode 加电池免优化；Shizuku 断了重连；守护没反应看 `~/.aidev-self-evolution.log`。

---

## Phase H 续 — 真机宇宙B 编译打通 + 安装/拉起纠错（2026-07-11，本轮）

### 成果
- **宇宙B `assembleDebug` 在真机跑通到出 APK**：突破点是 aapt2 跨架构运行。开发机可用的 aapt2 其实是「QEMU_LD_PREFIX + 显式 x86_64 loader 跑 aapt2.real」的包装脚本；把整套 x86_64 sysroot（`aapt2.real` + 9 个 glibc 库）+ `qemu-x86_64-static` 打进 APK，`BuildBridgeService.ensureX86Aapt2()` 部署并生成同款包装，override 指向它。`processDebugResources` 通过。
- **修安装/拉起「假成功」**：`installAndLaunch` 由 `executeFireAndForget` 改 `suspend`+`executeCommand`，检查 exit/`Failure`/`Error` 如实上报；`finish` 文案区分「已安装/未完成」并始终导出 `last-build.log`。
- **修测试项目与宿主同包名（本末倒置）**：脚手架 + 磁盘上 `MyAndroidProject` 的 `com.aidev.six.dev` 全改为 `com.example.myandroidproject`，装测试 App 不再顶掉 aidev6。

### 版本轨迹
- v30 便携 JDK17 → v31 gradlew classpath 启动 → v32 仓库健壮化 → v33 gradle init.d → v34 aapt2 `-P` override → v35 proot `-q`（错，回退）→ v36 aapt2 包装+探针（定位缺 x86_64 loader）→ v37 x86_64 sysroot（编译打通）→ v38 安装结果校验 → **v39 独立包名修复**（当前，`1.0.0-b39`，已装并运行）。

### 关键教训（详见 docs/error-journal.md）
1. Google 的 aapt2 是动态 x86_64，需自带 x86_64 loader+glibc；proot 禁用全局 `-q`（会把原生 arm64 也套 qemu）；aapt2 override 必须走 `-P` 而非 `System.setProperty`。
2. install/launch 不可 fire-and-forget，必须拿 exit code，否则全程假成功。
3. 测试项目绝不能与宿主同包名；修 bug 要治根因（改包名），别在错误前提上加「自动卸载重装」这类会自毁的智能重试。

### 修改文件（本轮）
| 文件 | 改动 |
|------|------|
| `BuildBridgeService.kt` | `ensureX86Aapt2()` 替代 `ensureQemu/writeAapt2Wrapper/detectAapt2Override`；`installAndLaunch` 改 suspend+结果校验；`finish` 始终导出日志；脚手架包名改 `com.example.myandroidproject` |
| `assets/tools/x86_64/` | 新增 `aapt2.real` + `lib/`（9 个 glibc） |
| `assets/tools/qemu-x86_64-static` | AArch64 静态 qemu |
| `/root/workspace/MyAndroidProject` | build.gradle.kts + MainActivity 改独立包名 |

### 待续（真机）
- D 组手动闭环：v39 已装，下一步在「服务器中心」提交构建，确认 4 阶段（准备→编译→安装→拉起）+ 探针 `AAPT2_PROBE_EXIT=0` + 真实安装/拉起 `com.example.myandroidproject`。
- 之后 F 组自治闭环（造崩 → 守护改码 → 重建收敛）。
