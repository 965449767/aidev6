# 决策记录（decisions.md）

## Phase F — pending_optional 冻结判据（2026-07-11）

Phase F 目标：把「自我进化闭环」（`宇宙A 写码` → `宇宙B 编译` → `Shizuku 安装` → `自动拉起` → `logcat 抓崩溃` → `喂回宇宙A`）端到端贯通、可观测、收敛长尾。

### 冻结总判据
一个改动若**不能让「写码 → 自动构建运行 → 自动反馈」更快 / 更可靠 / 更可见**，则默认冻结（不做）。
仅当满足以下任一「解冻条件」才重新开工：

1. 用户实测一次完整闭环出现断点 → 对应项解冻。
2. 后台长任务（编译 >5min / install-compiler >10min）被 HyperOS 实测中断 → 解可靠性项。
3. 实测误报率 / 漏报率高到干扰判断 → 解健壮性项。

### 已冻结项
| 项 | 内容 | 冻结理由 |
|----|------|----------|
| B6 | bridges 改前台 Service / KeepAliveService 覆盖 | 当前进程内协程在终端会话内可用，骨架已通；前台化是增强非阻断 |
| B8 | install-compiler 首次静默无进度 | 属一次性环境搭建，非主闭环路径，可在终端观察 |
| B9 | parseCrash 误报/截断健壮性 | 当前关键字兜底够用，待实测误报率再优化 |
| F03-细化 | 阶段标记正则精细化 | 4 阶段推导已覆盖主路径，精细化不阻断可见性 |
| 并发 | 多构建请求排队/并行管理 | 单 tracker + 独立线程已够用，待真实并发需求 |
| 冗余入口 | 「查看崩溃报告」按钮保留 | 与任务流并存无害，收敛成本 > 收益 |

### 已交付（本阶段）
- F01 断点清单；F02+F03 BuildRequestTracker（可见状态机 + 实时日志，解决 B1/B2/B3/B4）；
- F04 崩溃回流统一进任务流视图；F05 崩溃回流单测验证；测试环境修正（includeAndroidResources + org.json + 延迟 mainHandler）。

## Phase G — 反向驱动宇宙 A（2026-07-11）

### 决策
- 宇宙 A 与 App 解耦协作，**只用共享工作区文件契约**（`home/workspace/.aidev-loop/crash-<id>.json` + `req-<id>.json`），不引入 IPC/网络。
- 「自治开关」放在 App 内：开启后崩溃自动触发下一轮构建（`requestRebuild`），但**改码仍由宇宙 A 完成**；防失控靠 `fix_applied` 收敛 + `MAX_AUTO_ITERATIONS=10` 上限。
- 未在 App 内做"无限自动循环"的更激进形态：是否全自动由宇宙 A 侧（OpenCode / `aidev-self-evolution` 脚本）决定，App 只负责把契约文件摆好并响应自治开关。
