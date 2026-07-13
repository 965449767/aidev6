# 待办 OPT 清单（Backlog）

> 来源：`session-state.json` 的 `active_tasks`（OPT-01 ~ OPT-14）。
> 状态分两类：**已完成**（在 dev-workflow 等轮次里顺手交付）/ **待办**（尚未动手）。
> 目标：让 AIDev 内置开发环境更稳、更快、更省心。

## 一、已完成（不用再做）

| 编号 | 名称 | 落地情况 |
|---|---|---|
| OPT-02 | AgentTaskStore 内存缓存 + 延迟写盘 | 代码里早有 `ConcurrentHashMap` 缓存 + 2000ms 延迟写盘 + `flush()`；本轮打磨为真正防抖（取消上一个待写再排新）。宿主 b154。 |
| OPT-06 | 编译错误分类 + 中文建议 | `BuildDiagnostics.diagnoseBuildErrors` 已在构建失败调用；本轮补强"离线缺包 → 提示 aidev-precache"。 |
| OPT-07 | scaffold Compose 模板 | dev-workflow 的 `ProjectScaffoldState.generateScript()` + `create-compose-project` 已生成 Compose 模板。 |
| OPT-08 | 源码预检 | `BuildPreflight.inspectSourceCode`（import/Manifest/资源引用预检）已实现，宇宙 B「项目体检」UI 调用。 |
| OPT-09 | 增量编译提示 | ServerPanel 构建卡片按 `app/build` 是否存在显示增量/全量提示与 clean 建议。宿主 b153。 |
| OPT-10 | 构建历史统计 | ServerPanel 已有「最近构建」InfoNote（读取构建结果）。 |
| OPT-01 | JDK 多镜像 fallback + 缓存校验 | **经核查，功能早已实现且正确**：`ensureJdk` 已有 3 镜像（tuna→ustc→github）+ `--retry 3` + 下一镜像重试；`JDK_SHA256` 与 Adoptium 官方校验值**逐位核对一致**（83a521...8e81）；已存在 JDK 则跳过下载。本轮仅补一道防御：期望 SHA 为空时不误杀所有镜像（`[ -n "$EXPECTED_SHA" ]` 守护）。宿主编译通过。 |
| OPT-03 | BuildProgress 结构化状态 | **基础设施早已存在**：`BuildProgress.Phase` 枚举 + `deriveFromPhase` + 已在 BuildBridgeService 接线（PREPARE→COMPILE）。本轮修正一处不准确：构建黑盒只拥有 PREPARE→COMPILE，安装/拉起是独立部署黑盒（DeployBridgeService）；原 `finalize` 会把 INSTALL/LAUNCH 也误标 ✓。新增 `deriveUpTo(currentPhase)`，构建任务只发布自身拥有的阶段，SUCCESS 不再谎报安装/拉起。宿主编译通过。 |
| OPT-04 | Shizuku 安装重试 | **已落地在 bundled 脚本 `aidev-deploy`**：安装循环 `try 1/2/3`（最多重试 2 次）容忍瞬时 Shizuku 桥抖动，失败 `sleep 2` 重试；并带二次校验（`pm list packages` 确认包落地，防 HyperOS 假成功）。DeployBridgeService 只解析其出口 JSON，不越界。无需改 Kotlin。 |
| OPT-11 | BridgeService 空闲退避 | **`BridgeService.kt` 基类已实现**：轮询 500ms →（空闲>5s 翻倍）→ 最大 3s；有新的请求立即重置 500ms。所有桥接服务共享此基类，省电省资源。 |
| OPT-14 | 取消确认弹窗 | **`ServerPanel.kt:316` 已有**：`ConfirmDialog("取消任务", "确认取消…正在运行的进程将被终止。")`。取消按钮先 `set cancelTarget`，确认后才真正调用 Build/Deploy/TaskRunner.cancel，防误触已具备。 |

## 二、待办（未做）

| 编号 | 名称 | 大白话作用 | 风险 / 验证 | 排序建议 |
|---|---|---|---|---|
| OPT-05 | 动态崩溃等待 | 构建后多等几秒再判"是否本次弄崩" | **需真机验证** | P4（待真机） |
| OPT-13 | Gradle 分发包预置 | 已决议**不塞 APK**（体积暴涨 5~10 倍，收益有限） | 已下调/放弃实质预置 | 见分析要点 |

## 三、分析要点（按推进顺序）

### OPT-13 Gradle 分发包预置（第一个分析）
- **是什么**：把 Gradle 分发包（构建工具本身）作为资源预置进 AIDev，使断网也能 `./gradlew` 构建，无需现下 Gradle。
- **体积代价（实测）**：Gradle 9.1.0 分发包压缩 **129MB**、解压 143MB；当前 AIDev APK 仅 **26MB**。
  - 若预置 9.1.0 进 APK：`26MB → ≈155MB`（+129MB，assets 在 APK 内通常不二次压缩）。
  - 若模板项目用的 8.9 也预置：`26MB → ≈280MB`。
- **现实权衡**：
  - 当前构建环境已用 `file://` 指向本地 Gradle 缓存（`gradle-wrapper.properties` 已配），Gradle 其实**已经在环境里**，APK 再塞一份是"双份存放"，收益有限。
  - 仅对"全新干净设备 + 未预装 Gradle + 想断网构建"才真有价值。
- **结论/建议**：默认**不把 Gradle 塞进 APK**（代价 >> 收益）；改为确保首次配置时 Gradle 分发落盘（复用 `aidev-precache`/本地缓存思路）。若坚持做"离线安装包"，应作为**可选按需下载资源**，而非默认 APK 内容。优先级因此下调。

### 状态速览（截至本轮）
- **已完成**：OPT-01 / 02 / 03 / 04 / 06 / 07 / 08 / 09 / 10 / 11 / 14（均落地或核查确认真实存在）。
- **已放弃**：OPT-12（构建产物缓存，低收益，Gradle 自带 UP-TO-DATE，用户确认放弃）。
- **已决议不预置**：OPT-13（Gradle 分发包不塞 APK，体积暴涨 5~10 倍）。
- **待真机验证**：OPT-05（动态崩溃等待）。

### OPT-05 动态崩溃等待
- 构建后多等几秒再判"是否本次弄崩"。涉及崩溃判定，**必须真机实测**，建议真机验证后按需做。

### OPT-13 Gradle 分发包预置（已决议不塞 APK → 改为 AIDevRepo 仓库 App）
- 默认不把 Gradle 塞进 APK（代价 >> 收益）；断网构建已由本地 Gradle 缓存 + `aidev-precache` 预热覆盖。
- **落点**：已脚手架独立 `AIDevRepo` 项目（`/root/projects/AIDevRepo`），作为通用离线资源仓库（不止安卓，通用编程资源）。开头准备已完成：契约文档 `docs/repo-contract.md` + 数据模型 `RepoContract.kt` + 存储层 `RepoStore.kt` + 内置 `catalog.json` + 共享隐藏目录 `/sdcard/.AIDevRepo/`（点前缀 + `.nomedia`）；`compileDebugKotlin` 通过。UI（待缓存/已缓存双列表、推荐标识、删除更新）与下载引擎由后续 AI 接手（见 AIDevRepo/README.md）。
- **消费端已落地（aidev6 侧）**：新增 `/usr/local/bin/aidev-repo` 解析工具（fail-safe），并接入三处——`create-compose-project`（Gradle 分发）、`aidev-precache`（Maven 基线）、`BuildBridgeService.kt#ensureJdk`（JDK）。均"先 resolve 再回退网络"，零耦合；仓库 App 未装则静默降级。回归验证通过（compile/shell 65/assemble b156）。
