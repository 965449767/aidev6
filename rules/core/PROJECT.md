# PROJECT.md

# Project Knowledge Base

Version: 1.0 (aidev6)

This document is the single source of truth for the project.

Every AI agent MUST read this document before modifying any code.

---

# PROJECT OVERVIEW

Project Name

aidev6

Purpose

一个运行在 Android 设备上的「设备内 AI 开发环境」。在本地（Xiaomi 14 Pro / HyperOS / Android 16 / arm64-v8a）通过 PRoot 运行 Ubuntu，集成 Termux 终端、Shizuku 提权、OpenCode（本地 AI 编码服务）与 AIDev 宿主 App，让开发者在手机上完成「写代码 → 编译 → 装包 → 运行」的闭环，且离线可用。

- 谁用：Android 开发者、终端 / AI 编码爱好者、需要在设备上自维护 App 的用户。
- 为何存在：把「AI 写代码 + 本地编译 + 真机部署」收敛到一个离线、受控、可回滚的环境，避免把项目源码 / 密钥外发到云端。
- 只描述业务目的，不描述实现。

---

# PROJECT GOALS

Primary Goal

提供现代、稳定、离线的 Android 终端 + AI 开发环境（Shizuku + PRoot Ubuntu + OpenCode）。

Secondary Goals

Fast / Stable / Maintainable / Offline capable / Root optional / Safe。

---

# NON-GOALS

The following are intentionally NOT part of this project.

- 成为 Linux 发行版
- 支持每一个 Android 版本
- 取代 Termux（aidev6 复用 Termux terminal-view 库）
- 重写 Android 框架
- 成为通用 shell 管理器

Never allow AI to introduce these accidentally.

---

# TARGET USERS

Primary Users

- Power Users
- Android Developers
- Terminal Users
- Root Users
- Shizuku Users
- AI Coding Users

Secondary Users

- Advanced Android Enthusiasts

Never optimize for beginners unless requested.

---

# PROJECT PHILOSOPHY

The project values.

Reliability

↓

Predictability

↓

Maintainability

↓

Performance

↓

Beauty

Performance never comes before correctness.

Beauty never comes before stability.

---

# ENGINEERING PHILOSOPHY

Always choose.

Simple

↓

Stable

↓

Understandable

↓

Maintainable

Instead of.

Complex

↓

Clever

↓

Over-engineered

---

# CURRENT STATUS

Development Stage

Beta（持续演进，宿主已交付至 b165）

Current Milestone

- 通信升级完成：桥接由 500ms 文件轮询升级为 TCP loopback Socket 主用 + 文件轮询灾备（5 桥全接入，可回滚开关 `BRIDGE_SOCKET_ENABLED`）。
- 工程审计 ①②③④⑤⑥⑦ 完成（AgentTaskRunner 弹性 / 桥接设防 / 3 个测试转绿 / 最小 CI / AGENTS 版本同步 + App 架构文档 / SafeCommandGuard 加固 / god-file 最小安全拆分）。
- 引入 `rules/` 工程宪法与工作流 SOP。

Next Milestone

由人类指派（例如：本文件填充后的后续任务）。

---

# TECHNOLOGY STACK

Language

Kotlin

UI

Jetpack Compose

Platform

Android

Minimum SDK

26

Target SDK

36

Build

Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.0.21 / Java 17 / Compose BOM 2024.12.01

ADB

支持（经 Shizuku 桥接，无 adb）

Shizuku

Supported（提权主路径）

Root

Optional（不要求）

Termux

Supported（terminal-view 库）

Ubuntu

Supported（PRoot --link2symlink，bundled rootfs）

注意：Gradle / AGP / Kotlin / SDK / BOM 版本见根 `AGENTS.md`，且**禁止修改**（属于硬约束）。

---

# PROJECT MODULES

Describe every major module.

- Shell / Terminal：Termux terminal-view，EmbeddedTerminalPage 会话管理；PRoot Ubuntu（宇宙 A）。
- Bridge：宿主 ↔ PRoot 请求桥（5 桥 Notify / Shizuku / Build / Deploy / Crash）；TCP loopback 127.0.0.1:14096（`BRIDGE_SOCKET_ENABLED` 开关）+ 文件轮询兜底；有界线程池 + `soTimeout` + 静态共享密钥鉴权（`Constants.BRIDGE_SOCKET_TOKEN`，帧内 `auth` 字段）。
- AI / OpenCode：固定端口 4096，SSE 事件总线监控 session 状态（busy→idle 触发通知）。
- Bootstrap：PRoot rootfs 部署、`copyAssetScripts` 每次启动重放资产脚本、就绪判定 `home/ubuntu-rootfs/.aidev-rootfs-ready`。
- Build / Self-Evolution：AgentTaskRunner（`object` 单例 + 进程超时 + `destroyForcibly()`）、BuildRequestTracker（提交写 `req-<id>.json` + 插入 RUNNING 记录）、BuildBridgeService / DeployBridgeService；构建闭环（宇宙 B 编译 → 产出 APK）。
- Installer / Elevation：Shizuku 静默安装（android-shizuku / aidev-install），产物交用户手动安装到 `/sdcard/AIDev/`。
- Launcher：ShellActivity（唯一入口，`ComponentActivity` + `setContent`，无 XML 布局）、AppNavHost 导航、KeepAliveService 保活。

Every module should have ONE responsibility.

---

# MODULE OWNERSHIP

Every module must answer.

- 为何存在 / 谁依赖它 / 谁拥有它 / 解决什么问题。

例：Bridge 归宿主侧，被 Notify/Shizuku/Build/Deploy 四桥共享；Bootstrap 归 PRoot 部署，被 Terminal 与 Build 依赖；Build 由人类经 `aidev-build-request` 提交，被 App「编译」按钮与终端命令驱动。

---

# DOMAIN OWNERSHIP（目标架构护栏）

> 人类于 2026-07-17 批准目标架构方向：按业务领域（Domain）内聚，演进蓝图见 `docs/target-architecture.md`，评估核对见 `docs/ARCHITECTURE_REVIEW.md`。
> 当前代码已部分贴合（含 `domain/` 包）。**护栏原则：未来新增代码按 Domain 归属，不立即大改既有结构。**

## 目标 Domain 与归属

| Domain | 应拥有的组件 | 禁止 |
|---|---|---|
| `UI` | Compose 页面 / 主题 / 组件 | 任何 shell / 业务调用（必须经 ViewModel） |
| `IDE` | Project / Editor / Terminal 会话管理 | 直接执行构建 / 安装 |
| `AI` | 编码引擎抽象（AIEngine）/ 会话中止 | 写死单一 Provider（OpenCode）；仅人类触发 |
| `Runtime` | PRoot / Shell Layer / Bootstrap（rootfs / 资产 / 就绪判定） | 向 UI 暴露内部状态 |
| `Bridge` | Transport（Socket / File）+ Protocol（Build / Notify / Install）分层 | Transport 与 Protocol 混写 |
| `Build` | Compiler 驱动 / Installer / Deploy（人类提交 `aidev-build-request`） | 持有 UI 引用 |
| `Automation` | （已移除）原自我进化闭环 / 崩溃回流 / 闭环 FSM | 散落 callback 协调 |
| `Core` | 统一事件模型 / Config / Storage | 业务规则 |

## 硬护栏

- 新增类必须归入对应 Domain 包；**禁止新增顶层全局 `*Service` 组件**导致跨 Domain 蔓延（评审问题一）。
- Bridge 保持 Transport / Protocol 分离，二者不得混写（评审问题二）。
- 闭环状态演进须走显式状态枚举 / FSM，不得散落 callback（评审问题四）。
- AI Provider 经 `AIEngine` 抽象接入，OpenCode 为首个实现；OpenCode 仅作为人类驱动的写代码工具，宿主被动提供中止等人机交互入口，**不挂任何自动代码编辑 / 自动重建 / 自动修复能力**（契约见 `docs/target-architecture.md`）。
- 每个落地子任务遵守 `rules/workflow/EXECUTION.md`（≤5 文件、核心模块批准、行为锁），不一次性大改。

---

# DATA FLOW

UI

↓

ViewModel

↓

Repository

↓

Service

↓

Shell

↓

Android（PRoot）

桥接数据流：宿主 AgentTaskRunner → `/system/bin/sh -c <command>` → 产出 → BuildRequestTracker（落盘 `req-<id>.json`）→ BridgeSocketServer / 文件轮询 → PRoot 侧 `aidev-bridge` 转发到对应桥 → `dispatch`。

Never bypass layers.

---

# DEPENDENCY RULES

Dependencies always point downward.

Never reverse dependency direction.

Never introduce circular dependency.

---

# PROJECT CONSTRAINTS

- 永不移除 Shizuku 支持
- 永不要求 Root
- 永不破坏离线模式
- 永不引入云依赖
- 永不破坏 Android 兼容性
- 不增加权限（无相机 / 麦克风 / 联系人 / 短信 / 定位）
- 单模块、无 Hilt、无 Retrofit、无多模块（根 `AGENTS.md` 硬约束）
- Gradle / AGP / Kotlin / SDK / BOM 版本锁定，禁止修改（根 `AGENTS.md` 硬约束）
- APK 产物交用户手动安装，不自动安装

---

# ARCHITECTURE DECISIONS

Reference.

`docs/decisions.md`

Never redefine architecture. Read decisions first.

---

# CODING STYLE

Prefer Kotlin idioms.

Prefer immutable data.

Prefer explicit names.

Avoid magic numbers.

Avoid hidden state.

Prefer readability.

---

# ERROR HANDLING

Every failure should.

Explain.

Recover if possible.

Log useful information.

Never silently fail.

---

# PERFORMANCE GOALS

Cold startup / Memory usage / Shell startup / Compose performance / Installation time / Background resource usage.

Document measurable targets. Measure before optimizing.

---

# SECURITY GOALS

Never expose secrets.

Validate inputs.

Escape shell commands.

Verify permissions.

Handle unsupported devices.

Fail safely.

桥接 Socket 用静态共享密钥鉴权（`Constants.BRIDGE_SOCKET_TOKEN`）；Agent 命令经 SafeCommandGuard 校验（危险命令 / 受保护路径破坏性写拦截）。

---

# DEBUGGING

Always use `rules/workflow/debug.md`.

Never guess.

---

# REVIEW

Always use `rules/workflow/review.md`.

Never skip.

---

# EXECUTION

Always follow `rules/workflow/EXECUTION.md`.

---

# REFACTOR

Always follow `rules/workflow/refactor.md`.

---

# VERIFY

Always follow `rules/workflow/VERIFY.md`.

---

# DECISION LOG

Important architectural decisions belong in.

`docs/decisions.md`

Never bury them inside chat history.

---

# KNOWN LIMITATIONS

List current limitations honestly. This prevents AI from "fixing" intentional decisions.

- `BuildBridgeService`(1166) / `UbuntuBootstrapScripts`(834) / `TerminalShellAssets`(641) 为大文件（已知，非必须拆；仅允许清晰低风险的常量 / 纯函数提取，见 `rules/workflow/refactor.md`）。
- ShizukuBridge 的 `isCommandAllowed` 字符白名单仍放行 `rm` 等危险命令（预存已知限制）。
- 23 个 `#!/bin/bash` 脚本未转 POSIX sh（PRoot dash 隐患，按范围 deferred，需按需逐个转）。
- SafeCommandGuard 为黑名单增强版（非白名单），已封死双空格 / `dd of=` / `eval` / `sh -c` / `$(…)` / 反引号等已知绕过。

---

# KNOWN TECHNICAL DEBT

Describe.

- 审计 ⑥ 原建议白名单化 SafeCommandGuard（未做；黑名单增强已覆盖已知绕过，白名单化是更大改造）。
- 审计 ⑦ 完整版（内嵌 shell 外置 `assets/scripts` + 构建流水线拆 coordinator / steps）未做；仅做最小安全拆分（脚本字符串抽到 `*Defs.kt`，逻辑未动）。

Why it exists / Why not fixed / Priority / Expected future solution.

- 均属可维护性，行为无回归；优先级低，按需、低风险提取。

Never let AI "clean" technical debt automatically.

---

# CURRENT PRIORITIES

Priority 1

由人类指派（见 `current-task.md`）。

Priority 2

（同上）

Priority 3

（同上）

Everything else waits.

---

# OUT OF SCOPE

List features intentionally rejected. This prevents feature creep.

- 同 NON-GOALS
- 端侧 LLM 自愈闭环（长期北极星，不排入当前迭代）

---

# SUCCESS METRICS

Measure.

Crash Rate / Installation Success / Build Stability / Regression Count / Startup Time / User Satisfaction.

NOT Lines of Code.

---

# AI INSTRUCTIONS

Before making any changes.

Read this file.

Read `rules/core/AGENTS.md`.

Read `rules/workflow/EXECUTION.md`.

Read `rules/workflow/task.md`.

Read `rules/core/ARCHITECTURE.md`.

Understand the project.

Then work.

Never assume.

---

# GOLDEN PRINCIPLE

The project exists to solve user problems.

Not to demonstrate engineering skills.
