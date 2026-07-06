# 当前任务：Phase E — UI Layout Audit & Fixes

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
