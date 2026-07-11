# 自我进化闭环 · 反向驱动宇宙 A（文件契约）

> 配套实现：Phase F（闭环贯通 + 可见）+ Phase G（宇宙 A 反向驱动）。
> 本文件定义 App（宇宙 B 宿主）与 OpenCode（宇宙 A）之间**不依赖 IPC/网络**的文件契约，
> 让「崩溃回流 → 改码 → 重建」自动闭合。

---

## 1. 三个角色

| 角色 | 是什么 | 在闭环里做什么 |
|---|---|---|
| 宇宙 A | OpenCode（宿主里的 AI 写码工具） | 读崩溃回流 → 改源码 → 触发重建 |
| 宇宙 B | 手机内 PRoot 编译环境（JDK+SDK+Gradle） | 编译 → Shizuku 安装 → 自动拉起 → 抓崩溃 |
| 共享工作区 | `home/workspace`（宇宙 A/宇宙 B 挂载为同一份） | 源码 + 闭环契约文件的唯一真相源 |

---

## 2. 文件契约

### 2.1 崩溃回流（App → 宇宙 A）
路径：`home/workspace/.aidev-loop/crash-<ts>.json`
由 `BuildRequestTracker.publishCrashRecord` 在 App 拉起后抓到崩溃时写出（同时也会进「服务器中心」任务流）。

```json
{
  "type": "self-evolution/crash",
  "id": "crash-1700000000000",
  "package": "com.example.app",
  "time": 1700000000000,
  "crashed": true,
  "fatal": "FATAL EXCEPTION",
  "stack": ["java.lang.RuntimeException: boom", "    at com.x.Main.onCreate(Main.kt:12)"],
  "project": "MyAndroidProject",
  "fix_applied": false
}
```
- `crashed=true` 且有堆栈 = 真崩；`crashed=false` = 运行正常（无需处理）。
- `fix_applied=false` = 还没被宇宙 A 修过；修完后由宇宙 A 改为 `true`。

> 旧路径 `home/.aidev-mcp/crash-*.json` 仍保留（给 `aidev-crash-report` 工具用），但**宇宙 A 应读 `.aidev-loop/` 这份**，因为工作区才是共享的。

### 2.2 重建触发（宇宙 A → App）
路径：`home/.aidev-build-bridge/req-<id>.json`
宇宙 A 改完码后写出，等价于在「服务器中心」点一次「提交构建请求」。

```json
{
  "id": "se-1700000000999",
  "project": "MyAndroidProject",
  "flavor": "debug",
  "autoInstall": true,
  "autoLaunch": true,
  "triggeredBy": "self-evolution"
}
```
`BuildBridgeService` 轮询该目录 → 宇宙 B 编译 → 安装 → 拉起 → `CrashReportBridgeService` 抓崩溃 →
再次写出 2.1 的崩溃文件。**闭环闭合。**

> 也可在 App 内直接调用 `BuildRequestTracker.requestRebuild(context, project, stateFile, onUpdate)`，效果相同。

### 2.3 源码位置
`home/workspace/<project>/`（如 `MyAndroidProject`）。宇宙 A 改的就是这份，宇宙 B 编译的也是这份。

---

## 3. 宇宙 A 怎么用（推荐行为）

1. 周期性读 `home/workspace/.aidev-loop/crash-*.json`。
2. 找 `crashed=true` 且 `fix_applied=false` 的文件。
3. 读 `stack`，定位 `home/workspace/<project>` 里对应源码，直接修复。
4. 把该文件 `fix_applied` 改为 `true`，并附一句修复说明。
5. 写 `home/.aidev-build-bridge/req-<id>.json` 触发下一轮（见 2.2）。

参考脚本 `scripts/aidev-self-evolution` 已实现上述流程（含 `--max-iter` 防失控、空闲重试），
可直接当「宇宙 A 自动驾驶」跑；也可只作契约示例，由 OpenCode 自己读目录改码。

---

## 4. 防失控

- 崩溃文件带 `fix_applied`，避免同一份崩溃被反复"修"。
- 重建请求建议带 `triggeredBy: "self-evolution"`，便于在「服务器中心」区分人工/自动。
- `aidev-self-evolution --max-iter N` 限制自动轮数；真要全自动可配合进程守护。
- 若某轮改码后仍崩，会再次产出 `fix_applied=false` 的新崩溃文件，进入下一轮——这是预期行为。

---

## 5. 现状与边界（Phase G 收尾时）

- 已落地：崩溃回流写共享工作区（G01）、AgentTaskRecord.note 回填位（G02）、
  `requestRebuild` 触发下一轮（G03）、本契约（G04）、参考脚本（G05）。
- 未在 App 内做"自动无限循环"：是否全自动由宇宙 A 侧（脚本/OpenCode）决定，App 只负责把契约文件摆好。
- 真机端到端（改码→自动构建运行→崩溃回流→OpenCode 改码→再构建）需设备+Shizuku 实测，本环境仅做了文件契约与单测覆盖。

## 6. 「自我进化自治开关」（App 内一键自治）

`服务器中心` 新增开关 **自我进化自治模式**（`PreferencesManager.selfEvolutionAutonomous`，
key `self_evolution_autonomous`）。

- **关（默认）**：崩溃回流仅显示任务记录，下一轮构建需用户或宇宙 A（OpenCode / `aidev-self-evolution`）主动触发。
- **开**：`BuildRequestTracker` 在抓到崩溃后，若 `fix_applied=false`（宇宙 A 还没修），
  自动调用 `requestRebuild` 触发下一轮「宇宙B 编译 → 安装 → 拉起 → 抓崩溃」，形成自动循环。
  - 收敛条件：`fix_applied=true`（宇宙 A 已改码并重建）即停止；或达到 `MAX_AUTO_ITERATIONS=10` 上限防失控。
  - 开关通过 `submit(autonomous=)` 透传到 `watchCrashReport`，自动触发的下一轮同样是自治态。

> 注意：App 只负责"自动再构建"，**改码仍由宇宙 A（OpenCode）完成**。自治开关让"崩溃→重建"自动转，
> 配合常驻的 OpenCode（或 `aidev-self-evolution` 脚本）即可实现无人值守的"改码→验证"循环。

## 7. 守护进程（`aidev-self-evolution --daemon`）

关掉守护、只开 App 自治开关，闭环会"同一份坏代码反复重编"（见上）。**守护进程才是"自动改码"的那一半**。

在宇宙 A（OpenCode 宿主）里：

```bash
# 1) 先起 OpenCode 服务（常驻，避免每次冷启动）
opencode serve --port 4096 &

# 2) 启动自我进化守护（后台常驻，读崩溃→调 OpenCode 改码→触发重建）
aidev-self-evolution --daemon

# 查看状态 / 停止
aidev-self-evolution status
aidev-self-evolution --stop
```

守护每 5s 扫描 `home/workspace/.aidev-loop/crash-*.json`：
- 发现 `crashed=true` 且 `fix_applied=false` → 把堆栈喂给 `opencode run --attach http://127.0.0.1:4096` 改码
- 改完把该文件 `fix_applied` 置 `true`（防重复修）+ 写 `req-<id>.json` 触发下一轮构建
- 改码失败（OpenCode 服务没起）则跳过，等下一轮重试

**与 App 自治开关配合 = 完整无人值守闭环**：崩溃 → 守护自动改码 → App 开关（或守护自身）自动重建 → 拉起 → 再抓崩溃 → … 直到不崩。

> 双触发说明：守护和 App 自治开关都会写 `req-<id>.json`。同一份崩溃：若守护先修（置 `fix_applied=true`），App 侧因 `fix_applied=true` 不再自动重建；若 App 先重建，守护修完也会触发。不会死循环，因为 `fix_applied` 是收敛点。

> 调试：`aidev-self-evolution --once` 前台跑一轮；`OPENCODE_CMD` 可覆盖为其它非交互调用；`OPENCODE_URL` 覆盖服务地址；`AIDEV_WORKSPACE` 覆盖工作区根。

## 8. 现状与边界（守护化后）

- App 侧：自治开关（A 系列）让"崩溃→重建"自动转；守护进程（本节约 7）让"崩溃→改码"自动转。
- 两者齐备 = 无人值守自进化闭环；只开其一 = 半自动（人在环或空转重编）。
- 真机端到端需设备 + Shizuku + 常驻 OpenCode 实测；本环境已用 fake-OpenCode 验证守护"扫描→改码→标记→触发重建→启停"全链路。

## 9. 改码模型选择 + 对话可见（2026-07-12）

### 背景
真机实测发现：OpenCode 内置免费模型（hy3 / big-pickle / deepseek 等）按 IP 限额；额度耗尽时 `opencode run` **exit 0 且空返回、不打印任何错误** → 守护无法自动识别切换。因此改为「对话可见 + 用户手动切模型」。

### 实现（简单方案，已落地）
- **模型来源**：App「服务器中心 → 自我进化闭环 → 改码模型」下拉框，选择写入 `PreferencesManager.selfEvolutionModel` 并落盘 `workspace/.aidev-loop/se-config.json`（`{"model":"opencode/hy3-free"}`）。可选模型见 `Constants.SELF_EVOLUTION_MODELS`。
- **守护读取**：`aidev-self-evolution` 每轮 `read_model()` 读 `se-config.json`（回退 `OPENCODE_MODEL` 环境变量 / 默认 hy3-free），以 `opencode run --auto -m <model> --dir <项目> "<prompt>"` 调用（`--auto` 自动批准改文件，无 serve 依赖）。
- **对话可见**：守护把每轮「提示 + 回复」全文追加写 `workspace/.aidev-loop/conversation.log`；App 在「改码对话」可展开区块每 1.5s tail 展示。空回复时守护写一行 `⚠ 模型…无有效回复…请在 App 切换模型`，并**不**标记 `fix_applied`（留待换模型重试）。

### 待办（SSE 富文本方案，暂缓）
更好的体验是让守护用 `opencode run --attach http://127.0.0.1:4096`，App 订阅 4096 的 SSE `/event` 消息 part 事件，渲染流式聊天气泡（含 token 用量 / 模型名 / 分段增量）。工作量大（需 serve 常驻 + 事件解析 + 富文本渲染），记录备忘，后续实现。`OpenCodeMonitorService` 已连 SSE，仅处理 session.status/idle，可在其上扩展 message part 处理。
