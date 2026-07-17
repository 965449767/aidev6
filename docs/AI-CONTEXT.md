# AI-CONTEXT.md（aidev6 的 AI 上下文架构与路由）

> 本文件解释 aidev6 的「文档即上下文」组织方式，以及 OpenCode 如何**按需取上下文**。AI 写代码时的强制路由表见 [`rules/workflow/WORKFLOW.md`](../rules/workflow/WORKFLOW.md)；本文件是「为什么」与「全貌」。

## 为什么需要这套结构

AI 读文档有成本：每次任务都让它读十几个 `.md`，会越读越慢、上下文越浪费。正确做法是**按任务加载最小最相关的上下文**，而非全量拼接。这与「工作方向」文档中 **Context Router** 的主张一致——只是我们用「宪法层无条件加载 + 任务层按需加载」来落地，暂不依赖额外脚本。

## 四层架构（映射到 aidev6 实际文件）

### 1. constitution（永不变，每次会话无条件加载）
- 根 `AGENTS.md`：aidev6 特定环境/交付硬约束（Gradle 版本锁、单模块/无 Hilt、手动安装、PRoot 集成）。
- `rules/core/`：`AGENTS`（开发宪法）、`ARCHITECTURE`（架构治理）、`PROJECT`（项目知识库）、`ANDROID`（Android 标准）、`UI`（**UI 设计标准**）、`人类`（人类负责人手册）。
- `rules/workflow/`：`EXECUTION` / `SHELL` / `VERIFY` / `review` / `refactor` / `debug` / `task` / **`WORKFLOW`**（强制闭环 + 路由）。

### 2. project（每项目不同）
- `docs/app-architecture.md` / `opencode-architecture.md` / `android-guidelines.md` / `coding-guidelines.md` / `dev-workflow.md` / `compose-capabilities.md` / `verification.md` / `silent-install.md` / `self-evolution-loop.md` / `DESIGN_SYSTEM.md` / `error-journal.md` / `decisions.md`。
- `rules/core/PROJECT.md`（已填充为 aidev6 真实信息，single source of truth）。

### 3. knowledge（AI 真正学习处，按需检索）
- `docs/decisions.md`：稳定架构决策。
- `docs/error-journal.md`：重复 bug 与非显然失败 + 教训。

### 4. playbook / skills（SOP，任务匹配时加载）
- `skills/`：`plan` / `review` / `commit` / `handoff` / `debug` / `start`。
- `~/.config/opencode/skills/android-*`：`android-apk-pipeline` / `android-code-index` / `android-shizuku-automation` / `android-log-debug` 等。

### session（运行时）
- `.harness/session-state.json` / `.harness/session-log.md` / `current-task.md`。

## Context Routing（强制表，机器可执行）

见 `rules/workflow/WORKFLOW.md` 的路由表。要点：

- **constitution 永远在上下文里**（OpenCode 每次会话加载根 `AGENTS.md` + `rules/`）。
- **project / knowledge / playbook 按需**：按任务类型只加载相关文件。
  - 修 UI → 加载 `rules/core/UI` + `docs/DESIGN_SYSTEM` + `docs/compose-capabilities`。
  - 修 Shizuku/ADB → 加载 `rules/workflow/SHELL` + `docs/silent-install` + `android-shizuku-automation`。
  - Review → `rules/workflow/review` + `docs/verification` + `docs/coding-guidelines`。
  - 崩溃 → `android-log-debug` + `docs/error-journal`。
- 不相关的文件**不要读**，避免上下文污染与漂移。

## 未来增强（非当前必需）

- `index.db`（SQLite）：类/函数/调用图/依赖索引，替代 grep（对应「工作方向」的 Project Analyzer / Context Manager）。
- 真正的 Context Router 脚本：解析任务 → 自动注入文件清单。当前靠 `rules/workflow/WORKFLOW.md` 的路由表 + AI 自觉遵守实现同等效果。
