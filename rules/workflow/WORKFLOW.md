# 工作流 SOP（AI 强制）：Plan → Code → Review → Verify → Commit → Handoff

> 定位：本文件定义 aidev6 **每一次开发任务的强制闭环**。AI 接任务后必须按此流程推进，不得跳步、不得「直接改完就交」。
> 各步骤的具体动作由对应 skill 承载：`skills/plan/`（Plan）、`skills/review/`（Review）、`skills/commit/`（Commit）、`skills/handoff/`（Handoff）、`skills/debug/`（定位崩溃）。
> 上下文如何取，见下方 **Context Routing 表**；详细解释见 [`docs/AI-CONTEXT.md`](../../docs/AI-CONTEXT.md)。
> 与 `rules/workflow/EXECUTION.md`（执行 SOP）互补；冲突时以根 `AGENTS.md` 硬约束优先。

## 强制闭环

```
Understand → Plan → Risk → Implement → SelfReview → Compile → Verify → Regression → Commit → Report
   │          │       │        │           │            │         │         │           │        │
   │          │       │        │           │            │         │         │           │        └ 结构化报告（文件/原因/风险/验证/回滚/剩余）
   │          │       │        │           │            │         │         │           └ 提交（type(scope): 摘要；核心模块改动需批准）
   │          │       │        │           │            │         │         └ 回归：158 单测 + 65 shell 测试无新增失败
   │          │       │        │           │            │         └ 验证：未验证 = 未完成（见 VERIFY.md 16 级 DoD）
   │          │       │        │           │            └ 编译：compileDebugKotlin 通过
   │          │       │        │           └ 自审：对照 review.md 清单
   │          │       │        └ 实现：最小 diff；核心模块（Build/Bootstrap/Shell/ADB/Bridge/Installer/SelfEvolution/Gradle）改动需停手请求批准
   │          │       └ 风险：影响面/回滚/置信度需证据
   │          └ Plan：经 skills/plan 出范围/验收/验证
   └ Understand：先按 Context Routing 取上下文，再动手
```

## Context Routing（按任务取最小最相关上下文，禁止全量加载）

> 接到任务后，**先按表加载对应文件，再开始**。不相关的文件不要读，避免上下文浪费与漂移。
> `constitution` = 根 `AGENTS.md` + `rules/core/*`；`project` = `docs/*`；`knowledge` = `docs/decisions.md` + `docs/error-journal.md`；`playbook` = `skills/*` + `~/.config/opencode/skills/android-*`。

| 任务类型 | 必须加载 |
|---|---|
| 修 Compose / UI Bug 或新页面 | `rules/core/AGENTS` + `rules/core/ANDROID` + **`rules/core/UI`** + `rules/workflow/review` + `skills/review` + `docs/compose-capabilities` + `docs/DESIGN_SYSTEM` + `knowledge`(error-journal) |
| 修 Shizuku / ADB / Shell | `rules/core/AGENTS` + `rules/workflow/SHELL` + `rules/core/ANDROID` + `android-shizuku-automation` + `docs/silent-install` + `knowledge` |
| Code Review | `rules/workflow/review` + `docs/verification` + `docs/coding-guidelines` |
| 构建 / 发布 APK | `rules/workflow/EXECUTION` + `android-apk-pipeline` + `docs/verification` |
| 新功能 / 重构 | `rules/workflow/refactor` + `rules/workflow/task` + `skills/plan` |
| 会话恢复 / 交接 | `skills/start` + `.harness/session-state.json` + `.harness/session-log.md` + `current-task.md` |
| 崩溃诊断 | `android-log-debug` + `docs/error-journal` + 崩溃相关代码 |
| 设计系统 / 主题改动 | **`rules/core/UI`** + `docs/DESIGN_SYSTEM` + `AIDevTheme.kt` |

## 与 skills 的分工

- **Plan**：`skills/plan/` 产出范围、验收、验证清单。
- **Review**：`skills/review/` 做 diff 正确性检查（对照 `review.md` 清单）。
- **Commit**：`skills/commit/` 准备提交信息（`type(scope): summary`）。
- **Handoff**：`skills/handoff/` 固化 `current-task.md` / `.harness/*` / `docs/decisions.md` / `docs/error-journal.md`。
- **Debug**：`skills/debug/` + `android-log-debug` 做崩溃根因定位。

## 硬约束提醒（来自根 AGENTS.md，优先级最高）

- Gradle / AGP / Kotlin / SDK / BOM 版本**禁止修改**。
- 单模块、无 Hilt、无多模块；产物交用户手动安装（AI 不触发安装）。
- 改动 ≤3 文件（>5 或核心模块/架构/依赖/Gradle 变更须停手请求批准）。
