# AIDev6

设备内 AI 开发环境（Android 端）。在本地（Xiaomi 14 Pro / HyperOS / Android 16 / arm64-v8a）通过 PRoot 运行 Ubuntu，集成 Termux 终端、Shizuku 提权、OpenCode（本地 AI 编码服务）与 AIDev 宿主 App，让开发者在手机上完成「写代码 → 编译 → 装包 → 运行」的离线闭环。

- **一句话**：把「AI 写代码 + 本地编译 + 真机部署」收敛到一个离线、受控、可回滚的环境，源码与密钥不离开设备。
- **技术栈**：Kotlin + Jetpack Compose，单模块，Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.0.21 / Java 17 / Compose BOM 2024.12.01。
- **版本锁定与硬约束**：见根 [`AGENTS.md`](AGENTS.md)（Gradle 版本锁定、单模块/无 Hilt、手动安装 APK、PRoot 集成），**禁止修改**。

## 怎么运行（构建）

```bash
# 1. 准备 SDK 与 keystore 配置（均已被 .gitignore 排除）
cp local.properties.sample local.properties   # 编辑 sdk.dir 指向 Android SDK
cp keystore.properties.example keystore.properties

# 2. （可选）ARM64 静态 curl；或构建时 -PdownloadCurlMusl=true
./gradlew downloadCurlMusl

# 3. 构建
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
```

- Debug APK → `app/build/outputs/apk/debug/app-debug.apk`，自动复制到 `/sdcard/AIDev/`（`/sdcard` 即 `/storage/emulated/0`）。
- **禁止自动安装**：产物只交付到上述目录，由用户自行安装。
- 详见 [`docs/build-environment.md`](docs/build-environment.md)。

## 测试

```bash
./gradlew :app:compileDebugKotlin --no-daemon   # Kotlin 编译检查
./gradlew :app:testDebugUnitTest --no-daemon     # 158 单测（JUnit4 + Mockito；3 个预存失败，与改动无关）
./gradlew :app:assembleDebug --no-daemon         # 全量构建
bash app/src/test/sh/run.sh                      # 65 shell 测试（含 create-compose-project 等），全过
bash scripts/harness_check.sh                    # Harness / 文档校验
```

## 文档地图

> 文档读取优先级与 Context Routing 见 [`rules/workflow/WORKFLOW.md`](rules/workflow/WORKFLOW.md)；「为什么这样组织」见 [`docs/AI-CONTEXT.md`](docs/AI-CONTEXT.md)。

### 工程宪法与工作流 SOP（`rules/`，任何代码改动都须遵守）
- `core/AGENTS.md`（开发宪法）、`core/ARCHITECTURE.md`（架构治理）、`core/PROJECT.md`（项目知识库，single source of truth）
- `core/ANDROID.md`（Android 标准）、`core/UI.md`（UI 设计系统）、`core/人类.md`（人类负责人手册）
- `workflow/EXECUTION.md`、`VERIFY.md`、`SHELL.md`、`task.md`、`debug.md`、`refactor.md`、`review.md`、`WORKFLOW.md`

### 项目技术文档（`docs/`）
| 文件 | 内容 |
|------|------|
| `app-architecture.md` | 宿主应用架构：入口、运行时、PRoot 集成、桥接通信、自我进化闭环 |
| `architecture.md`（→`opencode-architecture.md`） | OpenCode HTTP/SSE 协议参考（非 App 架构） |
| `opencode-architecture.md` | OpenCode 集成参考 |
| `opencode-web-ui-reference.md` | OpenCode Web 前端设计参考 |
| `self-evolution-loop.md` | 自我进化闭环文件契约（Phase F/G） |
| `silence-install.md` | Shizuku 静默安装方法与修复记录 |
| `android-guidelines.md` | HyperOS 规则、权限范围、验证 |
| `coding-guidelines.md` | Android / 终端 / agent 约定 |
| `dev-workflow.md` | 开发工作流 |
| `git-workflow.md` | Git 工作流 |
| `verification.md` | 按变更类型的验收矩阵 |
| `decisions.md` | 稳定架构决策日志（single source of truth） |
| `error-journal.md` | 重复 bug、非显然失败、经验教训 |
| `DESIGN_SYSTEM.md` | UI 设计系统依据（`core/UI.md` 配套） |
| `compose-capabilities.md` | `create-compose-project` 生成模板的 Compose 能力边界 |
| `AIDEV6_PROOT_DIAGNOSIS.md` | PRoot 启动失败完整诊断 |
| `aidev6-init-runbook.md` / `real-device-runbook.md` | 实机初始化 / 自我进化闭环真机实测手册 |
| `backlog-opt.md` | 待办 OPT 清单 |
| `chat-issues-memo.md` | 聊天 UI 与官方客户端差异备忘 |

### 会话与技能
- 会话状态：`.harness/session-state.json`、`session-log.md`、`current-task.md`
- 技能：`skills/start`、`plan`、`review`、`commit`、`handoff`、`debug`

## 项目状态

Beta（宿主已交付至 b165）。通信升级（TCP loopback + 文件轮询灾备）完成；工程审计 ①~⑦ 完成；`rules/` 工程宪法与工作流 SOP 已落地。下一里程碑由 `current-task.md` 指派。
