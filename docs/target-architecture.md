# 目标架构蓝图（Target Architecture）

> 状态：**目标蓝图（人类于 2026-07-17 批准）**，非当前代码结构。
> 来源：外部架构评审建议（`/storage/emulated/0/建议.md`），经人类认可为项目演进方向。
> 配套评估与事实核对见 [`ARCHITECTURE_REVIEW.md`](ARCHITECTURE_REVIEW.md)；本文件是「往哪走」的权威依据。
> 落地原则：评审主张「现在不重构」。本蓝图**只作护栏**——未来新增代码按 Domain 归属，既有代码待具体功能任务时顺势归位，**不一次性大改、不触碰核心模块、不超 5 文件/任务**。

## 设计目标

把 aidev6 从一个「按技术层（UI/Service/数据）平铺」的结构，演进为**按业务领域（Domain）内聚**的结构，使新增功能自然落到对应领域，而不是不断堆出新的全局 `Service` / 全局回调。

核心理念（评审原文提炼）：
- 分层边界保留：`UI → ViewModel → Repository/Service → Shell Layer → PRoot Ubuntu`，**UI 禁止直连 shell**。
- Android 与 Ubuntu 解耦：仅靠 `workspace/` 文件契约（`req-<id>.json` / `crash-<id>.json`），不引入 IPC/网络。
- Bridge 作为独立 Transport/Protocol 抽象层保留。

## 目标结构

```
AIDev
├── UI
├── IDE
│      Project / Editor / Terminal
├── AI
│      Engine / Session / Context
├── Runtime
│      PRoot / Shell / Bootstrap
├── Bridge
│      Transport / Protocol
├── Build
│      Compiler / Installer / Deploy
├── Automation
│      Loop / Crash / FSM
└── Core
       Event / Config / Storage
```

每个 Domain 自己内聚拥有 `Repository / Manager / Service`，**禁止跨 Domain 的全局 Service 蔓延**（评审问题一）。

## 各领域职责（护栏）

| Domain | 拥有 | 禁止 |
|---|---|---|
| `UI` | Compose 页面、主题、组件 | 任何 shell / 业务调用（经 ViewModel） |
| `IDE` | Project / Editor / Terminal 会话管理 | 直接执行构建/安装 |
| `AI` | 编码引擎抽象（AIEngine）/ 会话中止 | 写死单一 Provider（OpenCode）；仅人类触发 |
| `Runtime` | PRoot、Shell Layer、Bootstrap（rootfs/资产/就绪判定） | 暴露内部状态给 UI |
| `Bridge` | Transport（Socket/File）+ Protocol（Build/Notify/Install）分层 | Transport 与 Protocol 混写 |
| `Build` | Compiler 驱动、Installer、Deploy（人类提交 `aidev-build-request`） | 持有 UI 引用 |
| `Automation` | （已移除）原自我进化闭环 / 崩溃回流 / 闭环 FSM | 散落 callback 协调 |
| `Core` | 统一事件模型、Config、Storage | 业务规则 |

## 已确认的真实缺口 → 纳入目标架构

外部评审 6 条「大问题」中，仅 **1 条被代码证实成立**：

> **AI Provider 写死**：当前 `OpenCode` 经固定 `Constants.OPENCODE_BASE_URL="http://127.0.0.1:4096"` + SSE 直连，无 Provider 抽象。未来若要支持 Claude Code / Gemini CLI / Codex / Qwen / Aider 等，成本较高。

其余 5 条（Service 过多、Bridge 混层、无状态机、无事件总线、Bootstrap 过重）经代码核实**多数已存在对应结构**（Bridge 三层、`AgentTaskStatus`/`BuildProgress.Phase`/`MonitorState` 状态枚举、`TerminalCommandBus` + SSE 流），属评审误判。详见 `ARCHITECTURE_REVIEW.md`。

### AIEngine 抽象契约（已批准，本期只定义，不实现）

人类已批准将 AI Provider 抽象纳入目标架构。本期**仅固化接口契约**，不动 `Constants.kt` 硬编码、不实现——实现留作后续单独获批子任务。

```kotlin
// 目标契约（待实现，非当前代码）
interface AIEngine {
    fun start()
    fun stop()
    fun status(): EngineState          // DISCONNECTED / IDLE / BUSY
    fun chat(request: ChatRequest): Flow<ChatEvent>   // 消息/Part/状态流
    fun cancel()
}

// OpenCode 为首个实现；未来可加 ClaudeCodeEngine / GeminiCliEngine 等
```

- 约束：`status()` 语义对齐现有 OpenCode 会话状态（DISCONNECTED/IDLE/BUSY）。
- 事件流对齐现有 OpenCode SSE 事件协议（`server.connected` / `session.status` / `session.idle` / heartbeat）。
- 实现子任务须走 EXECUTION.md：≤5 文件、核心模块（含 Self Evolution）改动需批准、行为锁（不改现有闭环行为）。

### 实现状态（2026-07-17，已二次收敛）

`AIEngine` 抽象**已落地**，但职责在「人类驱动重构」中进一步收敛：

- `monitor/AIEngine.kt`（接口）+ `monitor/OpenCodeEngine.kt`（OpenCode 实现）保留，但 `OpenCodeEngine` 现在**只提供人类触发的能力**（查询会话状态、中止指定会话，供通知「中止」按钮调用），**不参与任何自动代码编辑或闭环**。
- **`OpenCodeMonitorService` 已删除**（原被动 SSE 监控前台服务，对人无用）：`OpenCodeActionReceiver` 的中止请求直接 delegate 给 `OpenCodeEngine.abortSession`，不再依赖任何监控 Service。
- `baseUrl` 解析：优先读 `${aidevHome}/.aidev-opencode-port`（OpenCode 落非 4096 端口时由 aidev-opencode 脚本写出），回退 `Constants.OPENCODE_BASE_URL`（4096）。
- **自我进化闭环已整体移除**：`BuildRequestTracker` / `DeployRequestTracker` / `CrashReportBridgeService` 删除；`SELF_EVOLUTION_*` 偏好键与 `consumeLegacyCrashes` 移除；构建失败仅把完整日志落到 `logs/<project>/last-build-failure.log` 供人类排查，不再写 `self-evolution/build-failure` 回流文件、不再自动改写用户 `app/build.gradle.kts`。
- 设计原则（本期硬约束）：**终端在无 AI Agent 时也必须完整可用**；OpenCode 仅作为人类驱动的写代码工具，宿主被动提供中止等人机交互入口，不挂任何自动能力。

## 不在本期（严守评审「非现在」+ 治理红线）

- 不立即迁移/重分包现有代码。
- 不拆 `BuildBridgeService`(1170) / `UbuntuBootstrapScripts`(536) / `TerminalShellAssets`(445) 等大文件——按 `rules/workflow/refactor.md` 大文件≠坏架构，无清晰边界+低风险不得为「长」而拆。
- 不引入 Kotlin `Flow` 全局事件总线替换 `TerminalCommandBus`（属「替代设计模式」，需批准）。
- 不实现 `AIEngine` 接口（仅定契约）。
- 不改 `Constants.kt` 硬编码端口。

## 演进触发条件（何时顺势执行）

每当出现具体功能任务，且该任务落在某 Domain 内时，按护栏归位：
1. 新增类放入对应 Domain 包（当前包已含 `domain/`，方向一致）。
2. 需要 Provider 多实现 → 启动「AIEngine 抽象」子任务（单独获批）。
3. 闭环需暂停/恢复/取消/超时/多 Agent → 启动「闭环统一 FSM」子任务（单独获批）。
4. 每个子任务遵守 ≤5 文件、核心模块批准、行为锁。

## 参考

- 当前真实架构：[`../README.md`](README.md)、[`rules/core/PROJECT.md`](rules/core/PROJECT.md)、[`app-architecture.md`](app-architecture.md)
- 评估与事实核对：[`ARCHITECTURE_REVIEW.md`](ARCHITECTURE_REVIEW.md)
- 治理约束：[`rules/core/ARCHITECTURE.md`](rules/core/ARCHITECTURE.md)（ARCHITECTURE CHANGE REQUEST）、[`rules/workflow/refactor.md`](rules/workflow/refactor.md)（CORE MODULE PROTECTION）
