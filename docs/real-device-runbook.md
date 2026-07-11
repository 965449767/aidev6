# 真机实测手册 · 自我进化闭环（Real-Device Runbook）

> 目标：在真机上跑通一次完整的自我进化闭环 —— 改码 → 提交 → 宇宙B 编译 → Shizuku 安装 → 自动拉起 → 抓崩溃 → 守护改码 → 再构建，直到自动收敛。
> 前置：手机已装 aidev6（debug）、已配置 Shizuku、宇宙A 与宇宙B 通过 `home/workspace` 共享同一份源码。

---

## 第 1 步：装 App 到手机

电脑侧出包（本环境已验证产物存在：`app/build/outputs/apk/debug/app-debug.apk`，约 19.7MB）：
```bash
./gradlew assembleDebug -Pandroid.aapt2FromMavenOverride=/host-home/android-sdk/build-tools/34.0.0/aapt2 --no-daemon
```

**装到手机（二选一，均经 Shizuku 静默安装）：**
```bash
# 方式 A：用 aidev-install（推荐，自动发现/指定 APK，优先静默）
aidev-install --silent app/build/outputs/apk/debug/app-debug.apk

# 方式 B：直接用 adb（需手机开 USB 调试）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
安装前确认：手机已装 **Shizuku** 并授权 aidev6 的 `moe.shizuku.manager.permission.API_V23`；
`aidev-install --status` 应显示"桥接通道正常"。

手机上打开 aidev6，**底部上滑**进「服务器中心」。

> 本环境已确认：APK 可构建、权限齐全（含 Shizuku/IPC/安装/通知/电池免优化/开机广播）、`aidev-install --silent` 可用、Shizuku 桥接通道正常。
> 仅"把 APK 推到真机并执行安装"这一步需真机，无法在开发机完成。

## 第 2 步：确认宇宙B 能编译（关键前提）
在「服务器中心」点 **提交构建请求**，盯着任务流看 4 个阶段：
- 准备宇宙B → 编译 → 安装 → 拉起
全绿（最后一条 SUCCEEDED）才继续。**这步不过，后面都没意义。**

## 第 3 步：起 OpenCode 服务（宇宙A 常驻）
在宇宙A（OpenCode 宿主）终端：
```bash
opencode serve --port 4096 &
```
这把写码 AI 开成后台服务，供守护"附着"调用。
验证：`curl -s http://127.0.0.1:4096/  | head` 有响应即起好（端口以实际为准）。

## 第 4 步：起自我进化守护
```bash
aidev-self-evolution --daemon
aidev-self-evolution status        # 看到"运行中 (pid …)"即正常
cat ~/.aidev-self-evolution.log    # 看启动日志
```
守护每 5s 扫 `home/workspace/.aidev-loop/crash-*.json`，对 `crashed=true & fix_applied=false` 的调 OpenCode 改码。

可调参数：
- `OPENCODE_URL=http://127.0.0.1:4096`（覆盖服务地址）
- `OPENCODE_CMD=...`（覆盖非交互调用方式）
- `AIDEV_WORKSPACE=...`（覆盖工作区根）
- `--max-iter N`（限制轮数，调试用）、`--once`（前台跑一轮）

## 第 5 步：开 App 自治开关
回手机「服务器中心」，打开 **自我进化自治模式** 开关。
效果：App 抓到崩溃会自动再提交构建（不光靠守护触发），闭环自转。

## 第 6 步：造一个必崩改动，看它自己修
在 `home/workspace/MyAndroidProject` 里故意留必崩 bug，例如：
```kotlin
// MainActivity.kt 某处
val s: String? = null
println(s!!.length)   // 必崩 NullPointerException
```
然后在「服务器中心」点 **提交构建请求**，之后**别管它**，观察任务流：
1. 编译安装拉起 → 崩 → 守护读崩溃 → 调 OpenCode 改码（加空判断等）
2. 守护把崩溃标记 `fix_applied=true` + 触发下一轮构建（App 开关也可能触发）
3. 再编译安装拉起 → 若还崩重复；直到不崩 → 任务流出现"运行正常" / SUCCEEDED

**收敛标志**：崩溃文件 `fix_applied=true`，任务流最后一条成功，不再有新崩溃。

---

## 排查清单（出问题先看这里）
- **守护没反应**：`aidev-self-evolution status` 是否在跑；`tail ~/.aidev-self-evolution.log` 有无"OpenCode 调用失败"（多半 `opencode serve` 没起或端口不对）。
- **编译很慢**：几分钟到十几分钟正常，看任务流日志在走就别急。
- **闭环中途断**：HyperOS 杀后台 → 把 aidev6 和 OpenCode 都加**电池免优化**；Shizuku 断了手动重连。
- **崩溃文件一直 fix_applied=false**：说明守护没改到——查 OpenCode 服务是否真起、守护日志报错。
- **App 侧不自动重建**：确认"自我进化自治模式"开关已开；且崩溃文件 `fix_applied=false`（已修则不会重建）。

## 本环境已验证（无需真机）
- `testDebugUnitTest` + `assembleDebug` 绿灯。
- `bash scripts/verify-self-evolution.sh`：fake OpenCode 模拟完整闭环，断言契约闭合。
- `aidev-self-evolution` 守护对**真实 opencode**（本环境 v1.17.18）跑通"崩溃→改码→标记→触发重建"（见 Phase H H12）。
- 真机端到端（Shizuku 安装/拉起 + logcat 抓崩溃）仍需上面 1–6 步在设备上实测。
