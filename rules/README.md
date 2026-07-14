# 项目规则集（rules/）

> 来源：`/storage/emulated/0/提示词/`（AI 工程规则 v1.0）。
> 用途：本目录是 AI 在 aidev6 中开发时必须遵守的**工程宪法 + 工作流 SOP**。
> 任何代码改动、调试、重构、评审都以此为准绳；具体技术实现以 `docs/` 与代码为准。

## 分类

### `core/` —— 宪法 / 总纲（定义边界与价值观，不可违背）
| 文件 | 作用 |
|---|---|
| `AGENTS.md` | **AI 开发宪法**：稳定性>安全>可维护性>可预测性；小改动（≤3 文件，最多 5）；最小 diff；风险优先；禁止惊喜重构；响应必须含 Summary（文件/原因/风险/验证/回滚/剩余）；绝不编造 API/行为。 |
| `ARCHITECTURE.md` | **架构治理**：架构属于人类，AI 只实现；AI 无权决定 MVVM/MVI/Clean/模块化/包结构/DI/导航/状态管理/持久化/线程模型；大文件≠坏架构；架构变更须先给"现状/问题/证据/替代/收益/风险/迁移成本/回滚"并等批准。 |
| `PROJECT.md` | **项目知识库（single source of truth）**：改代码前必读。当前为**模板**（含 `<Project Name>` 等占位符），需填充为 aidev6 实际信息（见下方"待办"）。 |
| `ANDROID.md` | **Android 工程标准**：生命周期优先；Kotlin/Compose；SSOT；ViewModel 拥有业务状态；不阻塞主线程；Shizuku/Root 可选须有 fallback；Shell 必须经 Shell Layer；日志不记 token/secret。 |
| `人类.md` | **人类技术负责人手册**：定义人与 AI 的协作契约（方向/优先级/架构/风险/质量归人；单任务规则；审批事项；要求证据；结构化沟通）。AI 应理解，但不"遵守"其人类侧职责。 |

### `workflow/` —— 工作流 SOP（每类操作的标准流程）
| 文件 | 作用 |
|---|---|
| `EXECUTION.md` | **执行 SOP**：Understand→Clarify→Plan→Risk→Implement→SelfReview→Compile→Verify→Regression→Doc→Commit→Report；>5 文件或架构/依赖/Gradle/迁移变更须停手请求批准。 |
| `VERIFY.md` | **Definition of Done**：未验证 = 未完成；16 级验收（编译/功能/回归/性能/内存/Android/Shizuku/ADB/DB/网络/安全/日志/回滚/文档）；置信度需证据，禁止"应该能工作"。 |
| `SHELL.md` | **Shell 执行标准**：一切 JVM 外执行皆危险；命令须返回结构化结果；权限层级 L0-L4（API<Shizuku<ADB<Root）；命令构造防注入；必须超时/可取消/检查退出码/捕获 stderr；禁止自动提权/关 SELinux/改系统分区。 |
| `task.md` | **任务执行标准**：一任务一目标一提交；任务 15-60 分钟，>2 小时须拆；改动 ≤3 文件（>5 需批准）；核心模块（Build/Bootstrap/Shell/ADB/Bridge/Installer/SelfEvolution/Gradle）改动需批准；提交格式 `type(scope): summary`。 |
| `debug.md` | **调试标准**：证据优先，禁止猜测；Observe→Reproduce→Collect→Locate→RootCause→MinimalFix→Verify→Regression；栈帧自底向上读；修复只修一个问题、不顺手重构/优化。 |
| `refactor.md` | **重构标准**：默认不重构；仅当重复代码/结构致 bug/可量化可维护性收益/性能瓶颈/人类明确要求；大文件不因"长"拆；提取顺序常量>工具>纯函数>独立类>业务；行为锁（不改行为/API/生命周期/线程/构建）；核心模块受保护。 |
| `review.md` | **评审标准**：提交前必自审；清单覆盖正确性/安全/可维护性/回归/Android/Compose/线程/空安全/错误/日志/性能/内存/安全/架构；类 >1000 行不自动拆；风险分 + 置信度；证据不足拒绝合并。 |

## 与项目根 `AGENTS.md` 的关系
- 项目根 `AGENTS.md` 是 **aidev6 特定的环境/交付约束**（Gradle 版本锁定、单模块/无 Hilt、手动安装 APK、PRoot 集成、架构入口等），属于"项目事实"。
- 本目录 `rules/` 是 **通用 AI 工程纪律**（如何安全地改任何代码），属于"行为准则"。
- 二者互补：做 aidev6 改动时，既遵守根 `AGENTS.md` 的硬约束，也遵守 `rules/` 的工程纪律。若二者冲突，**根 `AGENTS.md` 的硬约束（如 Gradle 版本禁止改、禁止多模块）优先**。

## 待办（后续任务，非本次）
- **填充 `core/PROJECT.md`**：把 aidev6 的真实信息（项目名、目标、非目标、技术栈版本、模块职责、约束、已知限制、技术债、优先级）写进去，使其成为本项目 single source of truth。当前仍是模板。

## 使用约定
- 开始任何开发任务前，先读 `core/AGENTS.md` + `core/PROJECT.md`（填充后）+ 相关 `workflow/` 文件。
- 每个任务结束按 `EXECUTION.md` / `AGENTS.md` 的格式输出结构化报告。
- 任何触及"核心模块 / 架构 / Gradle / 依赖 / 公共 API / >5 文件 / 删除核心代码"的改动，先停手请求人类批准。
