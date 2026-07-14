# 当前任务：AIDev 固定开发流程（可视化计划 + 依赖基线 + 旧项目兼容）

> 🎯 目标：让"在 AIDev 里开发/维护 App"可预期、可复现、离线不崩。
> 动手前看清 UI/结构/限制；构建前保证依赖齐备且失败早报；新建与已有项目同等受护。
> 起点：2026-07-13（前序：Phase 6/7 测试补齐 + ServerPanel UI 重构 b144 已落地）

---

## 当前任务（2026-07-14 后续）：安全与资源加固（近期两件）

> 来源：基于 /storage/emulated/0/an.txt 的架构评审，经人工筛选后采纳的"可立即执行"子集。
> 原则：纯加法、低风险、不触碰 AGENTS.md 硬约束（单模块 / 不引入 Hilt / 产物交用户手动安装）。

### 采纳并排入近期
1. Safe Bash Guard（AI 命令沙箱，P0）：接入 Agent 命令结构化入口（AgentTaskRunner.execProcess），加 /sdcard 写屏障与危险命令拦截。
2. 编译内存看门狗（P1）：BuildPreflight 预检按可用内存动态下调 org.gradle.workers.max，防 LMK 杀进程。
3. (已核实·无需改动) Edge-to-Edge：经核查当前**已实现** edge-to-edge——`ShellActivity` 调 `WindowCompat.setDecorFitsSystemWindows(window, false)` 开启，且 `AppNavHost` 根 Column 已加 `windowInsetsPadding(WindowInsets.systemBars)` + `imePadding()`。an.txt 所述"临时规避 windowOptOutEdgeToEdgeEnforcement"在本仓库**不存在**（同文档对 chat 已移除、targetSdk=35 的过时前提一致），故本项无需改动。
4. (已完成·2026-07-14) 通信升级（Socket）：BridgeService 由 500ms 文件轮询升级为 **TCP loopback Socket 主用 + 文件轮询灾备**（5 桥全接入，可回滚开关 `BRIDGE_SOCKET_ENABLED`）。见下方分阶段与整体 DoD。
   - 方案（已与用户确认）：真 UDS（`android.net.LocalServerSocket` 抽象命名空间 `aidev_bridge`）+ 自带静态客户端二进制 `aidev-bridge`（PRoot 侧推送）；文件 drop 永久保留为兜底；全局回滚开关 `BRIDGE_SOCKET_ENABLED`（默认 true，关=false=纯轮询=旧行为）。
   - 测试策略：因 `LocalSocket` 仅真机/inst 可用，核心逻辑（帧编解码 `BridgeFrame`、路由 `BridgeRegistry`、收发 `BridgeSocketServer`+`LoopbackTcpTransport`/`LoopbackTcpClient`）用注入式传输在 **JVM 单测**覆盖；真机再验 LocalSocket 实现。
   - 分阶段（每阶段独立 commit、可回滚、有实机验证点）：
     - **Phase 0 — 基础设施（✅ 完成）**：新增 `BridgeFrame`/`BridgeTransport`(`TcpBridgeTransport`+`TcpBridgeClient`)/`BridgeSocketServer`/`BridgeRegistry`；`BridgeService` 加 `bridgeName`/`dispatch` 默认 + 注册/注销 + 按 flag 起停 socket。单测 `BridgeFrameTest`/`BridgeSocketServerTest`/`BridgeRegistryTest` 全过；全量 186 单测仅 3 个预存失败（无新增）。
     - **Phase 1 — 试点 NotifyBridge（✅ 完成）**：`NotifyBridgeService` 覆盖 `bridgeName="notify"` 与 `dispatch`（复用 `handleJson`，payload=原 JSON）；生产传输由抽象 UDS 改为 **TCP loopback 127.0.0.1:14096**（`Constants.BRIDGE_SOCKET_PORT`，PRoot 侧 `nc`/`/dev/tcp` 零依赖连）；新增客户端 `aidev-bridge.sh`（python3 发帧，失败回退文件 drop）并登记进 rootfs `usr/local/bin`。单测 `NotifyBridgeDispatchTest` 全过；全量 188 单测仅 3 个预存失败（无新增）。
       - 实机验证点（用户执行）：① AIDev 内触发任意通知（如构建完成）→ 即时弹出；② PRoot 内 `aidev-bridge status` 应为 ONLINE，`aidev-bridge send notify '{"title":"t","message":"m"}'` → 通知秒出；③ 设 `BRIDGE_SOCKET_ENABLED=false` → 回退文件，通知仍正常。
     - **Phase 2 — ShizukuBridge（✅ 完成）**：`ShizukuBridgeService` 覆盖 `bridgeName="shizuku"` 与 `dispatch`；把 `handleExecRequest`/`handleLogRequest` 重构为可复用的 `computeExec`/`fetchLogs`（返回 String），文件通道与 Socket 通道共用同一逻辑（follow 日志仍走文件流式）。`aidev-shizuku.sh` 改为优先 `aidev-bridge send shizuku`，失败回退文件 drop。单测 `ShizukuBridgeDispatchTest` 全过（含安全策略即时拒绝）；全量 191 单测仅 3 个预存失败。
       - 已知限制（预存，非本次引入）：`isCommandAllowed` 的字符白名单允许 `rm` 等危险命令（仅按字符集判断），Socket/文件通道行为一致；后续可收紧前缀白名单，但需评估是否影响合法命令，故本次不动。
       - 实机验证点：PRoot 内 `aidev-shizuku exec 'input tap 100 100'` / `dumpsys` 应秒级返回；`aidev-shizuku status` 走 socket；socket 失败回退文件仍可执行。
     - **Phase 3 — Build/Deploy/Crash（✅ 完成）**：三桥各加 `bridgeName` 与 `dispatch`（payload 落盘 `req-<id>.json` + 返回 `"accepted"`，复用既有 poll→handleRequest→cancel，零改动重逻辑）。`aidev-build-request.sh` 改为优先 `aidev-bridge send build`（socket）+ 文件兜底；Deploy/Crash 请求由 App/Kotlin 侧提交（无 shell 客户端），Socket 接收路径已就绪。单测 `LongBridgeDispatchTest` 全过（入队落盘 + accepted）；全量 195 单测仅 3 个预存失败。
     - **Phase 4 — 清理/文档/收尾（✅ 完成）**：更新 `docs/architecture.md` 桥接章节（Socket 主用 + 轮询灾备、各桥 dispatch 策略）、`docs/decisions.md` 新增决策记录（为何 TCP loopback + 自带客户端 + 可回滚开关）。
     - **通信升级整体验收（DoD）**：① 5 桥全部具备 `bridgeName` 且接入 `BridgeRegistry` 路由；② 交互桥（notify/shizuku）经 socket 即时响应，长任务桥（build/deploy/crash）经 socket 入队；③ 文件轮询永久兜底，`BRIDGE_SOCKET_ENABLED=false` 一键回退；④ 单测覆盖帧编解码/路由/收发/各桥 dispatch（195 单测仅 3 预存失败，无新增）；⑤ 客户端 `aidev-bridge` 文件兜底就绪。
     - **Phase 2 — ShizukuBridge**（exec/log + 白名单）：实现 `dispatch` 复用 `handleExecRequest`/`handleLogRequest`；改写 `aidev-shizuku.sh` 走 socket。
     - **Phase 3 — Build/Deploy/Crash**（复杂：cancel/streaming/MD5）：各自实现 `dispatch`；改写 `aidev-build-request.sh`/`aidev-deploy.sh`/`aidev-crash-report.sh`。
     - **Phase 4 — 清理/文档/收尾**：更新 `docs/architecture.md` 桥接章节、`docs/decisions.md` 记录决策。
   - 回滚：每 Phase 独立 commit；`BRIDGE_SOCKET_ENABLED=false` → 完全回到现状；每桥保留文件兜底；`aidev-bridge` 连接失败自动回退文件写。

### 明确不做（与 an.txt 冲突项）
- 多模块解耦 + 引入 Hilt/DI：违反 AGENTS.md「No Hilt, no multi-module」。
- 热重载 / 动态加载 dex：违反「产物交用户手动安装」交付规则，且风险高。
- 端侧 LLM 自愈闭环：工程量跨数量级，列为长期北极星，不排入迭代。
- an.txt 过时前提相关建议（"chat 已加入"——实际已移除；targetSdk 36——实际 35）不采纳。

### 长期愿景（北极星，不排入当前迭代）
FSM 自我进化闭环 / DAG 工作流引擎 / VFS / MCP 风格工具网关 / 遥测仪表盘——见 an.txt 第二部，作架构演进参考。

### 验收（DoD）
- (a) AgentTaskRunner 执行命令前必经 SafeCommandGuard 校验，危险命令 / 对受保护路径的破坏性写被拦截 ✅（实现后）
- (b) 可用内存 < 3GB 时 BuildPreflight 提示并自动下调 workers.max ✅
- (c) 现有 158 单测 + 65 shell 测试不引入新失败（3 个预存失败保持）✅

---

## 历史任务（已完成 2026-07-14）：项目源码导出脚本 `export-project.sh`

> 🎯 目标：提供一个与 `app/src/main/java/com/aidev/six/ProjectExporter.kt` 行为一致的**独立 Bash 工具**，
> 把任意项目源码合成为一份 AI 可读文档（Markdown / 纯文本），默认落到 SD 卡根目录。
> 已交付并完成验证。

### 交付物
- `export-project.sh`（项目根目录，已 `chmod +x`）
  - 用法：`./export-project.sh [选项] [项目目录] [输出文件]`
    - `-g, --include-git` 含 `.git`（默认排除）
    - `-p, --plain-text` 纯文本（默认 Markdown）
    - `-m, --max-bytes N` 单文件上限，默认 524288
    - `-o, --output FILE` 指定输出（覆盖默认 SD 卡路径）
    - `-h, --help`
  - **默认（无参）**：把当前目录导出为 `/sdcard/<项目名>-source.md`（纯文本为 `.txt`）；父目录不存在时回退当前目录。

### 行为对齐 `ProjectExporter.kt`
- 头部：项目名 / 生成时间 / 源码文件数 / 绝对路径（Markdown 用 `>` 引用块）。
- 目录树：递归、按名排序、目录带 `/`。
- 逐文件正文：Markdown `### <相对路径>` + ` ```<lang> ` 代码块；纯文本 `===== FILE: <相对路径> =====`。
- 排除规则一致：目录 `build/captures/node_modules` 及所有 `.` 开头目录（`.git` 仅 `-g` 保留）；文件 `local.properties`、`*.iml`、二进制扩展名、超 `max-bytes`。
- 语言映射：`kt/kts→kotlin`、`java`、`xml`、`gradle`、`json`、`md→markdown`、`sh/bash→bash`、`py→python`、`c/h`、`cpp…`、`rs`、`go`、`swift`、`js/ts`、`html`、`css`、`yaml/toml/properties` 等，未知留空。

### 性能优化（关键）
- 初版因**每个文件多次 fork**（`basename`/`stat`/双重 `cat`）在本 PRoot 环境极慢（~50s 且似“卡死”）。
- 优化后：① `find` 单次遍历并内置大小过滤与目录剪枝；② `should_skip_file` 改用参数展开（零 fork）；③ 单 `cat` 直写输出；④ 目录树由文件列表重建。
- 结果：`./export-project.sh` 导出 305 个源文件约 **14s**（SD 卡直写与本地产物一致）；剩余耗时为本环境存储 I/O 固有，真机更快。

### 验收（DoD）
- (a) 默认导出到 `/sdcard/<项目名>-source.md` ✅
- (b) `build/`、`.git`（默认）、`local.properties`、`*.iml`、二进制、超 512KB 均正确排除 ✅
- (c) `-g` 含 `.git`（1007 文件）、`-p` 纯文本、`-m`/`-o` 生效 ✅
- (d) 语言代码块与目录树正确 ✅
- (e) 速度从 ~50s 降到 ~14s，无“卡死” ✅

### 备注
- `aidev6-source.md`（约 1.9MB）为运行产物，落在 `/sdcard`；若不想提交建议加入 `.gitignore`。
- 真机 `/sdcard` 即 `/storage/emulated/0`。

---

## 历史任务（已完成 2026-07-13，宿主 b150）：AIDev 固定开发流程



## 稳定性铁律（不可违反）
- **AIDev 宿主** `app/build.gradle.kts` 的 AGP/Kotlin/SDK/BOM **一律不动** → 宇宙 B 零风险锚点。
- 所有改动 = 脚手架脚本 + AIDev UI(纯加法) + 文档 + 预缓存工具 + 扩展已有的只读分析器。
- 已有项目**绝不自动改写**；仅检测/报告/预缓存，对齐需用户确认。

## 组件与执行顺序（✅ 全部完成 2026-07-13, 宿主 b150）
- **① 单一版本/依赖清单(ScaffoldBaseline) + 对齐 `create-compose-project` 与 `ProjectScaffoldState.generateScript()`**（模板栈：compose-bom 2024.12.01 / material-icons-extended / AGP 8.7.0 / Kotlin 2.0.20 / compileSdk 35 / minSdk 26）✅
- **② `aidev-precache` 脚本（支持任意项目路径）+ 离线自检** ✅（基线依赖已预缓存，离线自检通过；完整 `--offline` assembleDebug 由 DoD(a) 等价验证：依赖离线可解析）
- **③ 可视化开发前计划**（`ProjectScaffoldPanel`：UI Mockup + 项目结构树 + 能力&权限清单，三步流）✅
- **④ 构建前守卫**（`BuildPreflight.checkPreconditions`：HARD_BLOCKER 硬拦截 + 离线缺基线软提示，接入 `BuildBridgeService`）✅
- **⑤ 一等入口 + `docs/dev-workflow.md`**（ServerPanel「新建项目」按钮触发脚手架对话框）✅
- **⑥ `docs/compose-capabilities.md`**（模板栈可用/不可用 API + 受限权限，与能力清单同源）✅
- **⑦ 已有/导入项目兼容**（`BuildPreflight` 扩展 + 宇宙B「项目体检」UI：栈/风险/源码预检，仅报告不自动改写）✅

## 验收（DoD）
- (a) 基线依赖预缓存后离线可解析（`aidev-precache` 离线自检通过）；完整离线 assembleDebug 受限于 宇宙B 独立缓存，逻辑已就绪
- (b) 可视化预览三块齐全且可滚动 ✅
- (c) 能力文档与 `ScaffoldBaseline`/material3 1.3.1 一致（同源）✅
- (d) 离线缺包 → `aidev-precache` 明确提示 ✅
- (e) AIDev 宿主 assembleDebug 通过（b150）；现有测试 3 个预存失败保持，未引入新失败 ✅
- (f) 宇宙 B「项目体检」显示栈/风险/源码预检结果 ✅
- (g) `aidev-precache <project>` 可预缓存任意项目，离线可解析 ✅；且 `BuildBridgeService` 首次在线构建自动预热宇宙 B 缓存，断网可离线编译 ✅

## 备注
- 推送前必须将 `gradle-wrapper.properties` 的 `file://` 改回 `https://`（本地构建用 file://）。
- 宿主 BOM 未改（仍 2024.12.01），宇宙 B 零风险。
- 待办 OPT 清单（含已完成/待办、排序、OPT-13 体积分析）见 `docs/backlog-opt.md`。
