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
| `AI` | （已移除）原编码引擎抽象（AIEngine）/ 会话中止 | 不得重新挂 AI 写码代理 |
| `Runtime` | PRoot、Shell Layer、Bootstrap（rootfs/资产/就绪判定） | 暴露内部状态给 UI |
| `Bridge` | Transport（Socket/File）+ Protocol（Build/Notify/Install）分层 | Transport 与 Protocol 混写 |
| `Build` | Compiler 驱动、Installer、Deploy（人类提交 `aidev-build-request`） | 持有 UI 引用 |
| `Automation` | （已移除）原自我进化闭环 / 崩溃回流 / 闭环 FSM | 散落 callback 协调 |
| `Core` | 统一事件模型、Config、Storage | 业务规则 |

## 已确认的真实缺口 → 纳入目标架构

 外部评审 6 条「大问题」中，仅 **1 条被代码证实成立**（现已随重构消除）：

> **AI Provider 写死**：原 `OpenCode` 经固定 `Constants.OPENCODE_BASE_URL="http://127.0.0.1:4096"` + SSE 直连，无 Provider 抽象。

其余 5 条（Service 过多、Bridge 混层、无状态机、无事件总线、Bootstrap 过重）经代码核实**多数已存在对应结构**（Bridge 三层、`TaskStatus`/`BuildProgress.Phase` 状态枚举、`TerminalCommandBus` + 任务流），属评审误判。详见 `ARCHITECTURE_REVIEW.md`。

### AI / OpenCode 处理结论（2026-07-17 彻底清除）

原目标架构曾规划 `AIEngine` 抽象（OpenCode 为首个实现）。在「人类驱动重构」中，该方向被**整体推翻**：

- **`monitor/AIEngine.kt` 与 `monitor/OpenCodeEngine.kt` 已删除**，连同 `OpenCodeActionReceiver`、`OpenCodeMonitorService`。当前项目**不含任何 AI 写码代理 / OpenCode 集成代码**。
- **不再有任何 AI Provider 抽象**：终端是人类的唯一开发入口；若用户自行安装 OpenCode 等工具，也只是普通终端命令，宿主不与之有任何程序级耦合。
- **自我进化闭环已整体移除**：`BuildRequestTracker` / `DeployRequestTracker` / `CrashReportBridgeService` 删除；`SELF_EVOLUTION_*` 偏好键与 `consumeLegacyCrashes` 移除；构建失败仅把完整日志落到 `logs/<project>/last-build-failure.log` 供人类排查，不再写回流文件、不再自动改写用户 `app/build.gradle.kts`。
- 设计原则（硬约束）：**终端在无 AI Agent 时也必须完整可用**；宿主不挂任何自动代码编辑 / 自动重建 / 自动修复能力。

## 不在本期（严守评审「非现在」+ 治理红线）

- 不立即迁移/重分包现有代码。
- 不拆 `BuildBridgeService`(1170) / `UbuntuBootstrapScripts`(536) / `TerminalShellAssets`(445) 等大文件——按 `rules/workflow/refactor.md` 大文件≠坏架构，无清晰边界+低风险不得为「长」而拆。
- 不引入 Kotlin `Flow` 全局事件总线替换 `TerminalCommandBus`（属「替代设计模式」，需批准）。
- 不引入 AI 写码代理 / OpenCode 集成（方向已推翻，见上文）。
- 不改 `Constants.kt` 硬编码端口。

## 演进触发条件（何时顺势执行）

每当出现具体功能任务，且该任务落在某 Domain 内时，按护栏归位：
1. 新增类放入对应 Domain 包（当前包已含 `domain/`，方向一致）。
2. 终端工具链新增能力 → 视为纯人类开发工具，不得挂任何自动闭环。
3. 闭环需暂停/恢复/取消/超时/多 Agent → 启动「闭环统一 FSM」子任务（单独获批）。
4. 每个子任务遵守 ≤5 文件、核心模块批准、行为锁。

## 参考

- 当前真实架构：[`../README.md`](README.md)、[`rules/core/PROJECT.md`](rules/core/PROJECT.md)、[`app-architecture.md`](app-architecture.md)
- 评估与事实核对：[`ARCHITECTURE_REVIEW.md`](ARCHITECTURE_REVIEW.md)
- 治理约束：[`rules/core/ARCHITECTURE.md`](rules/core/ARCHITECTURE.md)（ARCHITECTURE CHANGE REQUEST）、[`rules/workflow/refactor.md`](rules/workflow/refactor.md)（CORE MODULE PROTECTION）
