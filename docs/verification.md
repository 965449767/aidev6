# Verification Guide

## Purpose

This file defines how agents verify aidev6 changes before reporting completion.

## Baseline Harness Check

Run after harness or documentation changes:

```bash
bash scripts/harness_check.sh
```

## Android Debug Build

Run after Kotlin, Android resource, manifest, Gradle, or terminal behavior changes:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug
```

If the build environment is missing, run:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew --no-daemon
```

## APK Export

After a successful debug build:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/AIDev/app-debug.apk
```

## Change-Type Validation

| Change Type | Required Validation |
|---|---|
| Harness files | `bash scripts/harness_check.sh` |
| Documentation only | `bash scripts/harness_check.sh` |
| Git workflow docs | `bash scripts/harness_check.sh` and `git status --short` if Git exists |
| Kotlin code | Android debug build |
| Manifest/resources | Android debug build |
| Ubuntu bootstrap scripts | Android debug build plus device smoke test |
| assets/scripts/*.sh (AIDev 命令脚本) | `dash -n` 逐个语法校验（PRoot `/bin/sh` 是 dash，禁 `pipefail`/`[[`/`<<<`/`read -a`/`$RANDOM`/函数外 `local`）；改后用 `sed` 批量改 shell 语法属高危，须精确编辑 + `dash -n` 兜底 |
| Navigation changes | Android debug build plus manual entry-path check |
| HyperOS keep-alive behavior | Android debug build plus Xiaomi device smoke test |

## Manual Device Smoke Tests

For Ubuntu terminal changes on Xiaomi 14 Pro / HyperOS 3.0 / Android 16.0, install the APK and run:

```sh
type ubuntu
ubuntu
cat /etc/os-release
pwd
```

For clean bootstrap testing:

```sh
install-ubuntu --clean
ubuntu
cat /etc/os-release
```

### AIDev command scripts (assets/scripts/*.sh)

改动后**必须**在 PRoot 实跑冒烟（dash 基线），至少：

```sh
aidev-bridge status          # 应显示 bridge socket: ONLINE
aidev-shizuku status         # 应显示「Shizuku 状态: 正常」或明确的未授权原因（exit 码区分）
aidev-install --status       # 若支持；否则 aidev-shizuku status 等价于探测
```

覆盖层验证（确认用户自救通道生效）：

```sh
echo '#!/bin/sh
echo OVERRIDE-OK' > ~/overrides/bin/aidev-install
chmod +x ~/overrides/bin/aidev-install
aidev-install                   # 应输出 OVERRIDE-OK（命中覆盖层）
rm ~/overrides/bin/aidev-install
aidev-install                   # 回落出厂版
```

注意：PRoot 以 `-0`(root) 启动，`setReadOnly()` 非硬挡；防篡改由「覆盖层优先 + 版本升级恢复」保障，约定层不许直接改 `rootfs/usr/local/bin` 出厂脚本。

## If Validation Cannot Be Run

Record in `.harness/session-log.md`:

- command not run
- reason
- risk
- recommended follow-up

Do not claim completion without validation evidence.

## Git Validation

Before and after each phase, run if Git is available:

```sh
git status --short
git diff --stat
```

If Git is not initialized, record:

```text
Git unavailable: not a repository
```

Do not initialize Git or create commits without explicit user approval.

## Phase H — 自我进化闭环验证（已退役）

> ⚠️ **2026-07-17 起「自我进化闭环」整体移除**，本阶段原验证项（`BuildRequestTrackerTest`、`verify-self-evolution.sh`、自治开关、守护）已随代码删除不再适用。
> 取而代之的是**人类驱动验证**：构建失败由人类读 `logs/<项目>/last-build-failure.log` + `aidev-error-why` 排查，无自动改码。

### 等价验证（本环境可验）

1. **单元测试**：`testDebugUnitTest`（已移除 `BuildRequestTrackerTest` 等 AI 闭环测试，无新增失败）。
2. **构建**：`assembleDebug`（aapt2 覆盖）。
3. **Shell 测试**：`bash app/src/test/sh/run.sh`（含 `aidev-build-request`、建项目等命令测试）。
4. **Harness**：`bash scripts/harness_check.sh`。
5. **人工端到端**：见 `docs/real-device-runbook.md` 第 1–4 步（装 App → 宇宙B 编译 → 可选 OpenCode 改码 → 人类提交 `aidev-build-request`）。

## Phase I — 面板部署黑盒（DeployBridgeService）验证

「宇宙 B → 部署到设备」按钮（v121）的验证分两层。

### 本环境可验（已自动化 / 可模拟）

1. **构建**：`assembleDebug`（aapt2 覆盖）—— 产物生成且 result json 含 `pkg`（aapt2 dump badging 解析）。
2. **契约模拟**（无真机）：手投 `home/.aidev-deploy-bridge/req-<id>.json` → 确认 `DeployBridgeService` 轮询、写 `agent-tasks.json` 单一真源、面板轮询读到该记录。
3. **脚本落地**：`DeployBridgeService.onStart()` 后确认 `dev-env/bin` 含 `aidev-deploy`/`aidev-install`/`aidev-shizuku`/`aidev-verify-run` 且可执行（headless 可用）。

### 必须真机 / Shizuku（冻结为待办，非漏做）

- [ ] 「仅安装」：点按 → `aidev-deploy --no-launch` → `installed:true`、`launched:false`、`error:null`，设备已装该包（pm list 校验）
- [ ] 「安装并拉起」：点按 → `aidev-deploy --launch` → `installed:true`、`launched:true`、回带 `activity`
- [ ] 取消：部署进行中点「取消」→ `DeployBridgeService.cancel` 生效，状态回到空闲
- [ ] 安装失败可读根因：`aidev-install` exit non-zero 时面板显示 `error` 文本（非「假成功」）

判据：安装失败路径（见 `error-journal.md` 2026-07-13 aidev-install exit non-zero）需先解决再解冻「仅安装」实战验收。


