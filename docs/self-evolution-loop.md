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
