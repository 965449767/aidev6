# 架构评审：断言 vs 代码事实（ARCHITECTURE_REVIEW）

> 目的：记录一次外部 AI 架构评审（来源 `/storage/emulated/0/建议.md`）的**评估过程与结论**，作为决策日志，防止未来重复争论。
> 结论：**6 条「大问题」中 5 条与代码事实不符，仅 1 条成立。**
> 人类已于 2026-07-17 认可该评审的**目标架构方向**（按 Domain 重分包 + AIEngine 抽象），蓝图见 [`target-architecture.md`](target-architecture.md)。
> 核查范围：`app/src/main/java/com/aidev/six/`。所有结论基于代码事实，非猜测。

## 评估矩阵

| # | 评审断言 | 代码实际 | 判定 |
|---|---|---|---|
| 1 | Service 太多，将成 God Object；列举 `BridgeSocketServer`/`AgentTaskRunner`/`BuildRequestTracker` 都是 Service | 真正 `Service` 子类仅 3 个：`KeepAliveService`、`OpenCodeMonitorService`、`QuickTerminalTileService`。`BridgeSocketServer` 是 11 行薄封装类（持 `BridgeTransport`）；`AgentTaskRunner` 是 `object` 单例；`BuildRequestTracker` 是普通 `internal class`。Bridge 是 1 抽象基类 `BridgeService` + 5 个极小 `object` 单例 + `BridgeRegistry` 注册表 | **断言错误** |
| 2 | Bridge 混了 Transport+Protocol，会成 Everything Bus | 已有三层分离：`BridgeTransport`（接口 + `TcpBridgeTransport` 实现，仅管监听/收发帧/鉴权）、`BridgeFrame`（统一 4 字节长度 + UTF-8 JSON 帧，与传输无关）、`BridgeRegistry`（按 bridge 名 `dispatch`）+ 各 `BridgeService` 子类只管业务 `poll()`/`dispatch()` | **断言错误** |
| 3 | Build 自我进化闭环「没有状态机」 | 已有显式状态枚举：`AgentTaskStatus`(PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED，`AgentTaskStore.kt`)、`BuildProgress.Phase`(PREPARE/COMPILE/INSTALL/LAUNCH，`BuildProgress.kt`)、`MonitorState`(DISCONNECTED/IDLE/BUSY，`OpenCodeMonitorService.kt`)；闭环由 `BuildRequestTracker` 协调 + `MAX_AUTO_ITERATIONS=10` 封顶。缺的仅是「单一统一 LoopState 枚举」，代码本未声称有 | **断言夸大** |
| 4 | 系统「没有 Event Bus」 | 已有 `TerminalCommandBus`（`object` + `ConcurrentLinkedQueue`，`post()`/`consume()`）+ OpenCode SSE 事件流（`server.connected`/`session.status`/`session.idle`/heartbeat 经 `dispatchEvent`）。缺的仅是 Kotlin `Flow`/`SharedFlow` 响应式总线 | **绝对化错误** |
| 5 | Bootstrap 太重，`copyAssetScripts` 会膨胀到 3000 行 | `UbuntuBootstrapScripts.kt` 536 行、`TerminalShellAssets.kt` 445 行、`BuildBridgeService.kt` 1170 行；`copyAssetScripts` 确实存在。规模属实，但按 `refactor.md`「大文件≠坏架构」，无清晰边界+低风险不得为「长」而拆 | **规模属实，重构需批准** |
| 6 | OpenCode/AI Provider 写死（端口 4096、SSE），无抽象 | 确实：无 `AIEngine`/`AIProvider` 接口；`Constants.OPENCODE_BASE_URL="http://127.0.0.1:4096"` 直接硬编码，`OpenCodeMonitorService` 经 `HttpURLConnection` 直连。模型列表 `SELF_EVOLUTION_MODELS` 是 OpenCode 模型 ID，非可插拔 Provider | **唯一成立的断言** |

## 结论

1. 评审的**描述性批评大部分不准确**——状态机、事件总线、Bridge 分层实际都已存在；被点名的「Service」多数不是 Android `Service`。
2. 评审的**方向性建议有价值**：按 Domain 内聚、Bridge 保持 Transport/Protocol 分离、为未来多 Provider 预留抽象、闭环显式 FSM——这些与现有设计**不冲突**，可作为演进护栏。
3. 唯一被代码证实的真实缺口是 **AI Provider 硬编码（断言 6）**，已纳入目标架构（`target-architecture.md` 的 AIEngine 契约），本期只定义契约、不实现。
4. 评审提出的「立即大重构」（重分包/拆大文件/引 Flow 总线/抽象 AIEngine 实现）均属**架构/模块化变更 + 核心模块 + 远超 5 文件**，按 `ARCHITECTURE.md`/`refactor.md` 需人类批准；人类已批准**方向**，但明确「现在不重构」，故本期只建护栏。

## 治理依据

- `rules/core/ARCHITECTURE.md:71-103,463-501`：架构属人类，AI 无权决定模块化/包结构/状态管理；架构变更须走 ARCHITECTURE CHANGE REQUEST 并等批准。
- `rules/workflow/refactor.md:379-405`：核心模块（BuildBridge/Bootstrap/Terminal/Shell/Bridge/Self Evolution）受保护，任何修改需批准。
- `AGENTS.md` FILE MODIFICATION POLICY：重写架构/改项目结构/替代设计模式未经批准禁止；>5 文件须停手请求批准。

## 后续

- 目标蓝图：[`target-architecture.md`](target-architecture.md)
- 待具体功能任务触发时，按护栏顺势归位；每个子任务单独走 EXECUTION.md（≤5 文件、核心模块批准、行为锁）。
