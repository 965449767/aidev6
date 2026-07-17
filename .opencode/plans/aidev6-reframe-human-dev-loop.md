# 计划：aidev6 重构为「人类主导的终端开发环境」（去 AI Agent 闭环）

## 核心决策（用户确认）
1. 终端不依赖 AI 即可完整使用：人写码、建项目、编、装、调全靠终端命令。
2. OpenCode 仅作写码工具（若装），无附加能力；`OpenCodeMonitorService` 删除。
3. 构建统一走 B 宇宙：删 `aidev-build`（A 宇宙本地编，矛盾且多余），只留 `aidev-build-request` → 宿主桥 → B 宇宙编译器编 → 自动部署。人无需在 A 装 Gradle。
4. 不自动改码：失败在终端给中文提示，由人决定。
5. 命令唯一性：终端命令是事实源，UI 按钮只调同一条命令。

## 已核实事实
- `aidev-build-request` 人触发即可，桥 `BuildBridgeService` 非 AI 专属，B 宇宙 Gradle 宿主预置、人零配置 → 保留为主构建命令。
- `BuildBridgeService.preflightCheck` 会静默 `writeText` 改写用户 `app/build.gradle.kts` → 去掉写回，仅检测+中文提示。
- `aidev-build.sh` / `BuildBridgeService.writeLoopBuildFailure` / `BuildRequestTracker` 写 `.aidev-loop/*.json` 供 AI 消费 → 人本模型下全删。

## 分阶段执行（每阶段单独获批、≤5 文件、核心模块单独批准）

### Phase 1a — 删 AI 写码闭环代码
- 1a-1（删 Kotlin 闭环类）：`agent/BuildRequestTracker.kt`、`agent/DeployRequestTracker.kt`（死）、`CrashReportBridgeService.kt`；清理惰性 pref `Constants.SELF_EVOLUTION_AUTONOMOUS`/`SELF_EVOLUTION_MODEL`、`PreferencesManager` 对应 getter。
- 1a-2（删脚本与 AI 资产）：`assets/scripts/aidev-self-evolution`（死）、`aidev-crash-report.sh`、`assets/config/opencode/commands/*.md`（11个）。
- 验证：compileDebugKotlin + testDebugUnitTest + shell 测试；`aidev-build-request` 仍可提交、无 `.aidev-loop` 写盘。

### Phase 1b — 去自动改写与回路文件
- `BuildBridgeService`：移除 `writeLoopBuildFailure`（调用+定义）；`preflightCheck` 去掉 `gradleFile.writeText(fixedText)`，保留检测与中文提示。
- `aidev-build.sh`：移除失败写 `.aidev-loop/build-failure-*.json` 段落，改纯终端中文错误。
- 验证：失败构建只见中文错误、无回路文件、无 Gradle 改写。

### Phase 2 — 命令唯一性 + 统一建项目（版本对齐，需再确认）
- 统一建项目=`create-compose-project`，版本对齐宿主（AGP 9.0.1/Kotlin 2.0.21/Gradle 9.1.0/compileSdk 36）；删或薄封装 `aidev-create-android-project.sh`。
- 删 `aidev-build`；确保 `aidev-deploy` 注册为终端函数。
- 更新 `TerminalShellAssets.kt` + `CompletionEngine.kt` 命令清单。
- 验证：create-compose-project → aidev-build-request → aidev-install 跑通。

### Phase 3 — 闭环统一 + UI 对齐（删 OpenCode 监控）
- 删 `OpenCodeMonitorService` + `monitor/OpenCodeEngine.kt` + `AIEngine` 接口 + `AndroidManifest` `<service>` + `OpenCodeActionReceiver`。
- `TerminalPanel`：新增「安装」chip 调 `aidev-install`；「拉起」改调终端命令 `aidev-shizuku launch <pkg>`。
- 验证：按钮与手敲命令一致；调试链终端可用。

### Phase 4 — 文档收敛
- `docs/dev-workflow.md` 重写为人类终端开发闭环；`target-architecture.md` 标 AIEngine 已移除；`self-evolution-loop.md` 标已移除；`decisions.md` 追加决策；`PROJECT.md` 改 Build/Deploy。

## 不做
- 不引入 AI 自动改码/重建/回流；不改宿主 Gradle/AGP 版本锁；不重写架构、不为「长」拆大文件。

## 回滚
- 每子提交单独 `git revert`；每阶段跑 compileDebugKotlin + 单测 + shell 测试 + harness_check.sh。
