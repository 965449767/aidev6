# b194 — 极简终端壳重构实施方案

## 目标
按两条原则收敛 DevCenter：
1. 写代码只在终端 TUI 里的 OpenCode 完成，安卓侧不接管代码生产。
2. **凡是终端脚本/命令能完成的功能，不做安卓 UI**。

结果：安卓 App 退化为「终端宿主 + 设置 + 后台胶水」，所有开发功能（构建/评审/调试/设备/知识库/任务书/索引）都在终端由 `aidev-*` 脚本或 OpenCode 对话完成。

## 构建链路（关键，避免误删）
- 构建在 **B 宇宙**（`BuildBridgeService`，跑在安卓宿主进程）执行：轮询 `home/.aidev-build-bridge/req-<id>.json` → 在 `compiler_rootfs` 内 `./gradlew assembleDebug` → 产出 APK → 经 `copy-apk.gradle` 复制到 `/sdcard/AIDev` → 写 `result-<id>.json`。
- OpenCode 在 **A 宇宙**只跑 `aidev-build-request` 发起请求。
- **`BuildBridgeService` 必须保留**（它是构建引擎，属后台胶水，非 UI，不违反原则2）。

## 删除清单（源码，非 UI 的保留）

### UI 页面 / 包（main）
- `ui/pages/ServerPanel.kt`（含 总览/代码评审/AI/构建进化/调试/设备 全部子页）
- `ui/pages/DashboardPage.kt`
- `ui/pages/GitReviewPage.kt`
- `ui/pages/PromptBuilderPage.kt`
- `ui/pages/DebugCenterPage.kt`
- `ui/pages/AdbExplorerPage.kt`
- `ui/pages/KnowledgeBasePanel.kt`
- `ui/commands/DevCommands.kt`
- `context/ContextManager.kt` + `context/CodeIndexer.kt`（被 `aidev-index` 脚本覆盖）
- `git/GitDiffParser.kt` + `git/GitRepoDetector.kt`（仅 GitReview/Dashboard 使用）
- `data/KnowledgeBaseRepository.kt`（仅 KB 面板/Dashboard 使用）

### 测试（test）
- `test/.../context/CodeIndexerTest.kt`
- `test/.../git/GitDiffParserTest.kt`

> 保留（误删会断构建/终端）：`ProjectCommands.kt` / `ProjectToolsHelper.kt` / `files/ProjectManager.kt`（被终端补全使用）；`AgentTaskStore` / `BuildRequestTracker`（被 BuildBridgeService 使用）；`data/` 下其余被保留文件依赖的项。

## 保留（后台胶水，非 UI，原则2 不要求删）
`BuildBridgeService`、`CrashReportBridge`、`KeepAliveService`、Shizuku 集成、`SessionManager`/`TerminalSessionManager`、`PathConfig`、`PreferencesManager`、`TerminalCommandBus`、`DialogHost`/`LocalDialogManager`、`TerminalPanel`、`EmbeddedTerminalPage`、`SettingsPanel`、`EdgeSwipePanel`（简化后）、`AppNavHost`（简化后）、`ShellActivity`（简化后）。

通用组件（`ActionCard`/`StatusCard`/`EmptyState`/`SectionCard`/`ListRow`/`InfoNote`/`AppSectionHeader` 等）：保留；删除后无人引用的仅产生告警，不影响编译，实现时可按需清理。

## 修改清单

### 1) `ui/components/EdgeSwipePanel.kt`（整文件重写）
- `enum class PanelType { SETTINGS }`（删除 KNOWLEDGE/SERVER）。
- `EdgeSwipePanel(...)` 签名去掉 `knowledgeContent`/`serverContent`，保留 `settingsContent` + `content`。
- `transitionSpec` 仅保留 `PanelType.SETTINGS -> slideInHorizontally{-it} togetherWith slideOutHorizontally{-it}`。
- `when(panel)` 仅保留 SETTINGS 的 `Surface`（左侧滑入）。
- 手势区仅保留左侧 `EdgeGestureZone(targetPanel = SETTINGS)`；删除右侧(KNOWLEDGE)与底部(SERVER)手势区及 `BottomEdgeGestureZone`。
- `PanelIndicatorDots` 仅保留一个 SETTINGS 点。

### 2) `navigation/AppNavHost.kt`（重写）
- 删除 import：`KnowledgeBasePanel`、`ServerPanel`、`TAB_KNOWLEDGE`、`TAB_SERVER`。保留 `TAB_TERMINAL`、`TAB_SETTINGS`、`SettingsPanel`、`TerminalPanel`、`EmbeddedTerminalPage`、`EdgeSwipePanel`、`PanelIndicatorDots`、`PanelType`。
- 主体改为：
  - `Column(Modifier.fillMaxSize().windowInsetsPadding(systemBars).imePadding())` 内 `Box(weight(1f))` 放 `TerminalPanel(page = terminalPage)`。
  - `if (!imeActive) PanelIndicatorDots(currentPanel = if (settingsOpen) SETTINGS else null)`。
- `EdgeSwipePanel` 仅传 `settingsContent = { SettingsPanel(Modifier.fillMaxSize()) }` 与 `content = { TerminalPanel(page = terminalPage) }`；`currentPanel` 由 `currentTab == TAB_SETTINGS` 驱动；`onPanelOpen/onPanelClose` 同步 `onTabSelected`。
- 移除原 `AnimatedContent` 的 `when(tab)` 多标签切换（TAB_KNOWLEDGE/TAB_SERVER/TAB_SETTINGS 分支）。
- 保留 `onExecuteCommand` 形参（兼容 `ShellActivity` 调用），默认 `{}`，可不使用。

### 3) `ShellActivity.kt`（收敛 TAB 常量）
- `companion object` 常量改为：
  ```
  const val TAB_TERMINAL = 0
  const val TAB_SETTINGS = 1
  ```
  删除 `TAB_KNOWLEDGE=2`、`TAB_SERVER=3`。
- 三处范围 `TAB_TERMINAL..TAB_SERVER` → `TAB_TERMINAL..TAB_SETTINGS`（`onCreate` 的 `initial` 推导、`onNewIntent`、`switchTo`）。
- `ShellActivity.open(activity, tab)` 与 `switchTo(index)` 保留；越界 index 经 `switchTo` 的 range 检查自动忽略（安全）。

## 实施顺序
1. 删除上述 12 个 main 文件 + 2 个 test 文件。
2. 重写 `EdgeSwipePanel.kt`、`AppNavHost.kt`；改 `ShellActivity.kt`。
3. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon` 修到 BUILD SUCCESSFUL（预期错误：残留引用 SettingsPanel 之外的删除符号；逐一清掉）。
4. `:app:assembleDebug --no-daemon` → BUILD SUCCESSFUL，buildCount 自增。
5. `cp app/build/outputs/apk/debug/app-debug.apk /sdcard/AIDev/app-debug.apk`。
6. `git add -A && git commit`（**不 push**，沿用 b169 以来约定）。

## 真机验证
- 应用仅剩「终端」主屏 + 左滑「设置」面板。
- 终端内 OpenCode 跑 `aidev-build-request` → 宿主 B 宇宙构建 → APK 落到 `/sdcard/AIDev`，可安装。
- 代码评审/任务书/调试/设备/知识库/代码索引均在终端用 `aidev-*` 脚本或对话完成，无对应安卓 UI。
