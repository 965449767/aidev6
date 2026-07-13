# 当前任务：自动化构建流程优化（14 项）

> 🎯 目标：全面优化 BuildBridgeService 自动化构建流程的可靠性、性能、体验。
> 逐项推进，每项独立构建验证。b100 起。

## 冻结说明

以下任务一律冻结，不再推进，仅保留记录：
- Phase I Chat UI 全面优化（30 项）— 冻结
- ServerPanel 3 Tab 重设计 — 冻结
- Tasks Tab 9 项审计修复 — 冻结
- Phase H 真机端到端闭环验证 — 冻结（待用户实测）
- Phase G 自治/守护 — 冻结
- pending_optional 全部 — 冻结

冻结原因：当前聚焦构建流程优化，其他任务不阻断。

---

## 黑盒封装专项（标准分工）

> 🎯 原则：每个复杂任务封装为**独立黑盒**，固定「标准入口 / 标准出口」契约，内部不被乱改。
> 各黑盒待在自身天然环境：**宇宙 B = 编译隔离**（只管编译，不碰设备）；**部署/验证 = 设备侧**（由 App 的 Shizuku/Crash 桥承载，对 OpenCode 暴露成同款标准入口/出口命令）。
> 不把所有逻辑堆进宇宙 B——部署/验证需要真实设备（Shizuku/logcat），硬塞进编译器反而破坏隔离、失稳。

### 黑盒 1：构建黑盒（宇宙 B）— ✅ 已完成
- 职责：编译出 APK，绝对稳定、不受其他环境干扰
- 标准入口：`aidev-build-request --project <p> [--autoInstall --autoLaunch]`
- 标准出口：`result-<id>.json` → `{ success, apk_path, log_path, message }`
- 产物路径取自 `init.d/copy-apk.gradle` 的 `AIDev: APK -> <绝对路径>`（标准出口标记），消费方直接读，不再猜路径 / 不再用 mtime 启发式
- 落地版本：**v117**（含成功时清理上次失败回流产物）
- **v120 修正（真机暴露的误报）**：构建黑盒不再内置部署——移除 `installAndLaunch` 及 autoInstall/autoLaunch，
  产物路径优先取 Gradle 标准输出（UP-TO-DATE 时即为有效产物）、其次 `AIDev: APK ->` 副本，且两者都_require 文件真实存在；
  部署完全交由黑盒2（aidev-deploy）。根因：原先 `installAndLaunch` 在 APK 复制失败/UP-TO-DATE 时仍 `finishAndPublish(true)` 误报成功与拉起。

### 黑盒 2：部署黑盒（设备侧 / Shizuku 桥）— ✅ 已实现（v119），v121 接入面板
- 职责：把 APK 装到真机并启动，封装 Shizuku 安装、启动、权限等复杂性
- 标准入口：`aidev-deploy --apk <绝对路径> --pkg <包名> [--launch | --no-launch]`
- 标准出口：`{ installed, launched, activity, error }`（stdout JSON）
- 实现：`aidev-deploy.sh`，委托 `aidev-install`（Shizuku 静默安装）+ `aidev-shizuku exec`（resolve-activity / am start），
  安装后 `pm list packages` 二次校验落地；从构建黑盒成功分支解耦
- 优先级：中（闭环次之）
- **v121 接入「服务器中心」面板**：新增 `DeployBridgeService`（与 BuildBridgeService 同构的桥接服务）+
  `DeployRequestTracker`，在「宇宙 B」页新增「部署到设备」区，提供「安装并拉起」「仅安装」两个按钮。
  按钮写 `req-<id>.json` → DeployBridgeService 轮询、在 PRoot 内跑 `aidev-deploy`、解析标准出口、
  把「安装/拉起」两步进度作为单一真源写 `agent-tasks.json`，面板轮询即看到一致结果（与构建按钮完全同款服务一致性）。
  部署脚本（aidev-deploy/install/shizuku/verify-run）在 DeployBridgeService 启动时从 assets 落地到 `dev-env/bin`，
  不依赖开终端即可 headless 调用。
- **v121 构建结果带包名**：BuildBridgeService 用 aapt2 dump badging 解析产物包名，写入 `result-<id>.json` 的 `pkg` 字段，
  面板部署按钮直接取用，不再靠猜。

### 黑盒 3：运行验证黑盒（设备侧 / Crash 桥）— ✅ 已实现（v118）
- 职责：窗口内监控目标包是否崩溃/ANR，返回**确定结论**（闭环最脆环节）
- 标准入口：`aidev-verify-run --pkg <包名> [--window <秒>] [--launch]`
- 标准出口：`{ pkg, running, crashed, crash_log_path, window_ms, error }`（stdout JSON）
- 实现：`aidev-verify-run.sh`，委托 `aidev-logcat --watch-crash`（Shizuku 桥）窗口内监听，
  捕获 FATAL EXCEPTION / ANR / Native crash / Process died 即 `crashed=true` 并落崩溃日志
- 优先级：高（当前靠"等几秒 + 手查文件"最不可靠）

### 闭环编排（ServerPanel 指令简化为三黑盒循环）— ✅ 整合完成（v119）
```
loop:
  build(project)     → {success, apk_path, log_path, pkg}
  if !success: 读 log_path 改码; continue
  deploy(apk_path)   → {installed, launched}
  verify(pkg)        → {crashed, crash_log_path}
  if crashed: 读 crash_log_path 改码; continue
```
→ ServerPanel「生成修复命令」长 prompt 缩成"循环调用三个黑盒"一句话（v119）。
→ 「服务器中心」面板按钮（v121）：构建按钮只编译出 APK；「部署到设备」区两个按钮直接调部署黑盒，
  两者经 DeployBridgeService / BuildBridgeService 统一回写 `agent-tasks.json`，状态完全一致。

### 执行顺序
黑盒1(✅ v117) → 黑盒3(✅ v118) → 黑盒2(✅ v119) → 整合 ServerPanel 指令(✅ v119) → 逐项编译验证(✅)

### 待真机验证（需设备，无法在此环境跑）
- 终端重启后新脚本 `aidev-verify-run` / `aidev-deploy` 被强制覆盖生效
- 端到端：触发失败 → 按钮 → OpenCode 自驱 build→deploy→verify 闭环，直到「构建通过+部署成功+运行不崩」
- v121 面板部署按钮：点「提交构建请求」→ 出现「编译成功 + APK 路径 + 包名」记录 → 点「安装并拉起」，
  面板出现 `deploy-<id>` 记录，如实显示「已安装/已拉起/失败原因」；需确认 Shizuku 授权与真机拉起。

---

## P0 — 阻塞性 / 高收益

### OPT-01 JDK 192MB 下载多镜像 fallback + 缓存校验
- 问题：`ensureJdk()` 从清华镜像下载 192MB JDK tarball，单镜像无 fallback，首次构建或宇宙 B 重建失败率高
- 文件：`BuildBridgeService.kt:834-892`（`ensureJdk()`）
- 方案：
  1. 多镜像 fallback 链：清华 → 阿里 → 华为 → GitHub Releases
  2. 下载完成后做 SHA256 校验，避免重复下载
  3. 下载失败时给出明确中文提示
- 验证：断网/换镜像场景下 fallback 生效；已下载的 JDK 不重复下载

### OPT-02 AgentTaskStore 内存缓存 + 延迟写盘
- 问题：`upsertTask()` 每次全量读写 `agent-tasks.json`（12 条 × 6000 字符/条），构建期间每 ≥800ms 更新一次，IO 压力大
- 文件：`AgentTaskStore.kt:92-99`
- 方案：
  1. 内存缓存 + dirty flag + 2s debounce 写盘
  2. 或构建进度单独存轻量文件，避免每次重写全部 12 条
  3. 考虑换用更高效格式（JSON Lines 或 SQLite）
- 验证：构建期间 IO 降低；单测通过

### OPT-03 BuildProgress 改用结构化状态
- 问题：`BuildProgress.derive()` 靠日志文本包含特定字符串推导阶段，脆弱易误判
- 文件：`BuildProgress.kt` + `BuildBridgeService.kt:120-143`
- 方案：
  1. `append()` 接收 `Phase` 枚举参数
  2. 发布时直接用枚举，`derive()` 退化为简单映射
  3. 保留日志文本作为辅助信息
- 验证：阶段推导准确，不受用户代码文本干扰

---

## P1 — 体验 / 稳定性

### OPT-04 Shizuku 安装重试机制
- 问题：`installAndLaunch()` 检查 Shizuku 未就绪时直接跳过，无重试
- 文件：`BuildBridgeService.kt:894-948`
- 方案：3 次重试，间隔 3-5 秒；始终保留 APK 到 `/sdcard/AIDev/`
- 验证：Shizuku 短暂不可用时自动重试成功

### OPT-05 动态崩溃等待
- 问题：构建成功后固定等 8 秒再抓 logcat，太死板
- 文件：`BuildBridgeService.kt:297-306`
- 方案：先等 3 秒抓一次，有崩溃就结束；没有再等 5 秒抓第二次
- 验证：快速崩溃和慢崩溃都能捕获

### OPT-06 编译错误分类 + 中文修复建议
- 问题：编译失败只显示 `exit=$exit` + 原始日志，小白看不懂
- 文件：`BuildBridgeService.kt:274-278` + 新增解析器
- 方案：解析常见编译错误（Unresolved reference、Type mismatch、Missing class、Could not resolve），给出中文修复建议
- 验证：常见错误场景有中文提示

### OPT-07 scaffold 默认 Compose 模板
- 问题：`scaffoldProject()` 创建 View 项目（AppCompatActivity + XML），不符合 vibe coding 预期
- 文件：`BuildBridgeService.kt:364-633`
- 方案：默认创建 Compose 模板（ComponentActivity + setContent）
- 验证：新项目默认是 Compose，编译通过

### OPT-08 源码预检（import/Manifest）
- 问题：`preflightCheck()` 只检查 build.gradle.kts，不检查源码
- 文件：`BuildPreflight.kt` + `BuildBridgeService.kt:218-226`
- 方案：检查 import 引用、Manifest 中 Activity/Service 是否存在
- 验证：缺失 import 时提前告警

---

## P2 — 锦上添花

### OPT-09 增量编译提示
- 问题：用户不知道 Gradle 是否利用了增量编译
- 文件：构建日志输出
- 方案：检测 `build/` 目录存在性，显示"增量编译"/"全量编译" + 耗时
- 验证：首次全量，后续增量

### OPT-10 构建历史统计
- 问题：只保留 12 条记录，无成功率/平均耗时等
- 文件：`AgentTaskStore.kt` + UI
- 方案：状态 Tab 增加"构建统计"卡片（总次数、成功率、平均耗时）
- 验证：统计正确

### OPT-11 BridgeService 空闲退避
- 问题：BridgeService 每 500ms 固定轮询，空闲时浪费 CPU
- 文件：`BridgeService.kt:26-31`
- 方案：空闲时指数退避到 2-3 秒；有新请求写入时立即唤醒（FileObserver）
- 验证：空闲 CPU 降低；新请求及时响应

### OPT-12 构建产物缓存
- 问题：即使源码没变，每次都要完整编译
- 文件：`BuildBridgeService.kt:handleRequests` 入口
- 方案：项目目录文件 hash 快照，源码未变直接返回上次 APK
- 验证：源码未变时跳过编译

### OPT-13 Gradle 分发包预置
- 问题：宇宙 B 内 gradlew 首次下载 Gradle 9.1.0（~150MB），手机网络慢
- 文件：`BuildBridgeService.kt:ensureCompilerRootfs`
- 方案：将 Gradle 分发包预置到 `gradle-cache/wrapper/dists/`，或在 ensureCompilerRootfs 阶段预下载
- 验证：宇宙 B 重建后无需重新下载

### OPT-14 取消确认弹窗
- 问题：点击"取消"直接强杀进程，无二次确认
- 文件：UI 取消按钮
- 方案：弹出确认对话框
- 验证：误触可取消

---

## 执行顺序

OPT-01 → OPT-02 → OPT-03 → (编译验证)
→ OPT-04 → OPT-05 → OPT-06 → (编译验证)
→ OPT-07 → OPT-08 → (编译验证)
→ OPT-09 ~ OPT-14 → (编译验证)

---

## 已完成（本轮之前）
- b58-b71：API 端点覆盖 + 权限/追问/命令/shell/diff/浏览/git/fork/compact
- b72-b83：Phase I UI 全面优化（顶栏菜单/Tool 折叠/斜杠建议/Scroll-to-bottom/字符计数/AI 打字指示器/会话搜索/Diff 折叠/streamingText/模型列表/状态 Crossfade/时间戳/光标/Markdown 增强）
- b84-b86：流式+Tool 修复（part.updated 直接替换/Tool input+toolCallId/File+Patch 类型）
- b87-b93：Markdown 渲染升级 + Reasoning + Tool 专用渲染器 x10 + Part 类型补齐
- b94-b99：ServerPanel 全面重设计（3 Tab 导航 + 9 项 Bug 修复 + 确认弹窗 + 日志滚动）+ Tasks Tab 9 项审计修复

## 已完成（本轮，构建流程优化）
- v121：服务器中心面板「部署到设备」按钮（安装并拉起 / 仅安装）接入部署黑盒；新增 DeployBridgeService + DeployRequestTracker，
  与 BuildBridgeService 同构，统一回写 agent-tasks.json（服务一致性）；构建结果带包名 pkg 供部署直接使用；
  任务取消逻辑接入 deploy 标签。编译安装成功（b121 / versionName 1.0.0-b121）。
