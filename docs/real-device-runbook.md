# 真机实测手册（Real-Device Runbook）

> ⚠️ **已退役「自我进化闭环」**：原「改码 → 提交 → 编译 → 安装 → 拉起 → 抓崩溃 → 守护自动改码 → 再构建」的自治闭环已在 2026-07-17 的人类驱动重构中整体移除。AIDev 现在要求**终端在无 AI Agent 时也必须完整可用**，OpenCode 仅作为人类驱动的写代码工具。
> 本手册改为描述**人类驱动的端到端验证**：装 App → 确认宇宙B 能编译 →（可选）用 OpenCode 改码 → 人类提交构建。

---

## 第 1 步：装 App 到手机

  电脑侧出包（本环境已验证产物存在：`app/build/outputs/apk/debug/app-debug.apk`）：
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
  ./gradlew assembleDebug --no-daemon
  ```
  > ⚠️ 产物校验**不要**直接用 `aidev-apk-info`：该工具有 3 个解析 bug（包名误显 `16`、Native ABI 空、Debuggable 误报「否」）。
  > 真值用 `aapt2 dump badging app-debug.apk` 确认：包名 `com.aidev.six.dev`、含 `lib/arm64-v8a/*.so`、`application-debuggable`。

**装到手机（二选一，均经 Shizuku 静默安装）：**
```bash
# 方式 A：用 aidev-install（推荐，自动发现/指定 APK，优先静默）
aidev-install --silent app/build/outputs/apk/debug/app-debug.apk

# 方式 B：直接用 adb（需手机开 USB 调试）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
安装前确认：手机已装 **Shizuku** 并授权 aidev6 的 `moe.shizuku.manager.permission.API_V23`；
`aidev-install --status` 应显示"桥接通道正常"。

## 第 2 步：确认宇宙B 能编译（关键前提）

在终端执行统一构建入口：
```bash
aidev-build-request --project /workspace/<应用名>
```
或在 App 终端页面对项目目录点 **「编译」** 按钮（等价于在终端写入上述命令）。盯着任务流看 4 个阶段：
- 准备宇宙B → 编译 → 安装 → 拉起
全绿（最后一条 SUCCEEDED）才说明宇宙B 编译链路正常。**这步不过，后面都没意义。**

## 第 3 步：（可选）用 OpenCode 改码

若需在真机环境写代码，自行启动 OpenCode（人类驱动）：
```bash
opencode serve --port 4096 &
```
OpenCode 仅作为写代码工具，宿主不自动调用它做任何事；如要中止某个 OpenCode 会话，用通知的「中止」按钮（经 `OpenCodeActionReceiver` → `OpenCodeEngine.abortSession`）。

## 第 4 步：构建失败时人工排查

构建失败不会自动改码。完整日志落在手机 `logs/<项目>/last-build-failure.log`，可在终端用：
```bash
aidev-error-why            # 从 last-build-failure.log 自动匹配中文方案
```
由人类判断如何修复源码，再重新 `aidev-build-request`。

---

## 排查清单（出问题先看这里）
- **编译很慢**：几分钟到十几分钟正常，看任务流日志在走就别急。
- **闭环中途断**：HyperOS 杀后台 → 把 aidev6 加**电池免优化**；Shizuku 断了手动重连。
- **构建失败无自动修复**：这是预期行为（无自治闭环），按第 4 步人工排查。

## 本环境已验证（无需真机）
- `testDebugUnitTest` + `assembleDebug` 绿灯。
- `bash app/src/test/sh/run.sh`：shell 测试全过。
- `bash scripts/harness_check.sh`：Harness check passed（文档/结构完好）。
- A3 复核：宇宙B 项目 `./gradlew assembleDebug` → BUILD SUCCESSFUL。
- A1 复核：APK 真值（aapt2）包名 `com.aidev.six.dev`、含 arm64-v8a、debuggable=true、权限齐全。
- 真机端到端（Shizuku 安装/拉起）仍需在设备上实测。
