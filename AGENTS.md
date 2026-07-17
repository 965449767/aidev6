# aidev6 — AGENTS.md

An on-device AI dev environment for Android (Xiaomi 14 Pro / HyperOS / Android 16 / arm64-v8a). Kotlin + Jetpack Compose, single-module, bundled Ubuntu via PRoot.

> 📜 **AI 工程纪律（强制）**：任何代码改动 / 调试 / 重构 / 评审都须遵守 [`rules/`](rules/) 下的工程宪法与工作流 SOP（`AGENTS.md` 为开发宪法，`ARCHITECTURE`/`PROJECT`/`ANDROID`/`UI`/`SHELL`/`EXECUTION`/`VERIFY`/`REVIEW`/`DEBUG`/`REFACTOR`/`TASK`/`WORKFLOW` 为各标准）。其中 **`rules/core/UI.md`**（UI 设计系统）与 **`rules/workflow/WORKFLOW.md`**（强制闭环 + Context Routing）为**每次任务必守**；UI/主题/组件改动先读 `docs/DESIGN_SYSTEM.md`，接任务先按 `WORKFLOW.md` 路由表取上下文。本文件（根 `AGENTS.md`）的硬约束（Gradle 版本锁定、单模块/无 Hilt、手动安装 APK、PRoot 集成）与之冲突时**优先本文件**。

## Build

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
```

- Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.0.21 / Java 17
- Aliyun Maven mirrors (`settings.gradle.kts`), no VPN needed
- Modern AGP setup with built-in Kotlin and new DSL-compatible configuration
- Debug APK → `app/build/outputs/apk/debug/app-debug.apk`, auto-copied to `/sdcard/AIDev/`（`/sdcard` 即 `/storage/emulated/0`）
- Build counter auto-increments in `app/build-counter.properties`

## 交付规则（APK 安装）

- **禁止自动安装 APK**。构建完成后，只把产物复制到 `/storage/emulated/0/AIDev/`（等价于 `/sdcard/AIDev/`）由用户自行安装。
- 除非用户**主动要求**，否则一律不要执行安装（不要用 aidev-install / installapk / am start 安装器 / 任何方式触发安装）。
- 验证类操作也只把 APK 放到上述目录，安装动作交给用户。
- Optional `downloadCurlMusl` task downloads static curl-musl for arm64 into `assets/tools/`

## Testing (run in order)

```
./gradlew :app:compileDebugKotlin --no-daemon       # Kotlin compile check
./gradlew :app:testDebugUnitTest --no-daemon        # 158 unit tests (JUnit 4 + Mockito)；已知 3 个预存失败（BuildDiagnosticsTest/BuildPreflightSourceTest/BuildRequestTrackerTest），与改动无关
./gradlew :app:assembleDebug --no-daemon             # full build
```

- Shell tests（正确入口）：`bash app/src/test/sh/run.sh`  # 65 shell 测试（含 create-compose-project 等），全过
- 注：项目无 `testShellScripts` Gradle 任务，勿用 `./gradlew :app:testShellScripts`（会报 task not found）
- Harness/doc validation: `bash scripts/harness_check.sh`

## Architecture

- **Single launcher**: `ShellActivity` (`ComponentActivity` + `setContent`, no XML layouts)
- **Navigation**: Jetpack Navigation Compose via `AppNavHost`
- **Terminal**: Termux `terminal-view` library, `EmbeddedTerminalPage` session manager
- **Ubuntu**: bundled PRoot (`--link2symlink`), readiness = `home/ubuntu-rootfs/.aidev-rootfs-ready` (NOT `etc/os-release`)
- **OpenCode / AI 代理**: 宿主已彻底解除耦合，不含任何 AI 写码代理集成；若用户在 Ubuntu 内自装 OpenCode 等工具，仅为普通终端命令
- **Elevation**: Shizuku for privileged operations
- **Runtime home**: `filesDir/home`
- **Entrypoints**: `ShellActivity.kt`, `EmbeddedShellPages.kt`, `AIDevApp.kt`, `KeepAliveService.kt`

## Key constraints

- App-private scripts: run via `/system/bin/sh <script>`, NEVER execute directly
- Android `tar` may fail on hardlinks → preserve symlink fallback
- NDK r27 (r28 broken, downgraded); only `arm64-v8a`
- `useLegacyPackaging = true` for jniLibs
- No Hilt, no Retrofit, no multi-module
- No camera/mic/contacts/SMS/location permissions
- Edge-to-edge temporarily opted out (`windowOptOutEdgeToEdgeEnforcement`)

## Session workflow

1. **Start**: `current-task.md` → `.harness/session-state.json` → `.harness/session-log.md` → `docs/verification.md` → `docs/decisions.md` → `docs/error-journal.md`
2. **Plan** (via `skills/plan/`) → **Code** → **Verify** → **Handoff**
3. **Handoff**: update `current-task.md`, `.harness/session-state.json`, `.harness/session-log.md`, record decisions/errors in `docs/`

## Useful docs

| File | Content |
|------|---------|
| `docs/app-architecture.md` | 宿主应用架构：入口、运行时、PRoot 集成、桥接通信、与 AI 工具解耦关系 |
| `docs/architecture.md` | **（符号链接→`opencode-architecture.md`）** OpenCode HTTP/SSE 协议参考（仅供用户自装工具查阅），非 App 集成 |
| `docs/opencode-architecture.md` | OpenCode HTTP/SSE 协议参考（仅参考，宿主已不集成） |
| `docs/opencode-web-ui-reference.md` | OpenCode 官方 Web 前端设计参考 |
| `docs/self-evolution-loop.md` | 自我进化闭环文件契约（**已退役**，保留为历史参考） |
| `docs/verification.md` | validation matrix per change type |
| `docs/coding-guidelines.md` | Android, terminal, and agent conventions |
| `docs/decisions.md` | 稳定架构决策日志（single source of truth；含 Phase F/G 冻结判据） |
| `docs/error-journal.md` | 重复 bug、非显然失败、经验教训（含 Android 开发通用教训） |
| `docs/android-guidelines.md` | HyperOS 规则、权限范围、验证 |
| `docs/silent-install.md` | Shizuku 静默安装（App UI + 终端）的方法、修复记录、架构 |
| `docs/DESIGN_SYSTEM.md` | UI 设计系统依据（`rules/core/UI.md` 配套） |
| `docs/compose-capabilities.md` | `create-compose-project` 生成模板的 Compose 能力边界 |
| `docs/AIDEV6_PROOT_DIAGNOSIS.md` | PRoot 启动失败完整诊断 |
| `docs/aidev6-init-runbook.md` / `real-device-runbook.md` | 实机初始化 / 自我进化闭环真机实测手册 |
| `docs/backlog-opt.md` | 待办 OPT 清单 |
| `docs/chat-issues-memo.md` | 聊天 UI 与官方客户端差异备忘 |

## Skills

- `skills/start/` — session recovery procedure
- `skills/plan/` — scoped engineering plan
- `skills/review/` — diff correctness check
- `skills/commit/` — commit preparation
- `skills/handoff/` — durable state preservation
- `skills/debug/` — Android crash diagnosis (logcat via Shizuku)
