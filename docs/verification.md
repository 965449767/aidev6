# Verification Guide

## Purpose

This file defines how agents verify aidev3 changes before reporting completion.

## Baseline Harness Check

Run after harness or documentation changes:

```bash
bash scripts/harness_check.sh
```

## Android Debug Build

Run after Kotlin, Android resource, manifest, Gradle, or terminal behavior changes:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
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

## Phase H — 自我进化闭环验证

闭环相关改动（Phase F/G：BuildRequestTracker、自治开关、aidev-self-evolution 守护）的验证分两层。

### 本环境可验（已自动化）

1. **单元测试**（已持续绿灯）：`testDebugUnitTest`
   - `BuildRequestTrackerTest`：崩溃写共享工作区、requestRebuild 写 req、自治三场景（触发/已修复收敛/手动不自动）、note 向后兼容。
2. **构建**：`assembleDebug`（aapt2 覆盖）。
3. **闭环文件契约模拟**（无真机）：`bash scripts/verify-self-evolution.sh`
   - 用 fake OpenCode 模拟 崩溃→改码→标记 fix_applied→写 req→构建桥 result 成功，断言契约闭合。

### 必须真机 / Shizuku（冻结为待办，非漏做）

在真实设备上跑一次完整闭环，确认下面各项实战没问题（详见 `current-task.md` Phase H 待办）：

- [ ] 完整闭环：改码 → 提交 → 宇宙B 编译 → Shizuku 安装 → 自动拉起 → logcat 抓崩溃 → 守护改码 → 再构建，自动收敛
- [ ] B5：崩溃回流 60s 轮询窗口是否够（App 启动慢 / 晚崩场景）
- [ ] B6：长构建（>5min）期间 App 被 HyperOS 杀进程是否中断闭环（前台 Service / KeepAlive 覆盖）
- [ ] B8：首次 install-compiler 静默长任务是否需进度提示
- [ ] B9：parseCrash 误报 / 截断率
- [ ] 设备开机后 OpenCode `serve` + 守护自启（目前需手动起）

 判据：以上默认冻结，仅当用户实测完整闭环出现断点、或某项误报/中断率实测偏高时才解冻动工（见 `decisions.md`）。

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


