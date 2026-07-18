# OpenCode Web 前端设计参考

> 给 AI agent 的参考文档。为 aidev6 新增「纯安卓原生聊天式 Vibe Coding 界面」提供设计依据：
> 记录 OpenCode 官方 Web 前端的布局、配色、主题体系，以及聊天渲染所依赖的消息/Part 数据模型与 API。
> 来源：OpenCode 官方文档（[web](https://opencode.ai/docs/web/) / [server](https://opencode.ai/docs/server/) / [themes](https://opencode.ai/docs/themes/)）、SDK 类型源码、默认主题 JSON。
> 后端通信事实见 `docs/opencode-architecture.md`（HTTP/SSE 章节），本文不重复。

---

## 1. Web 前端布局（官方 `opencode web`）

`opencode web` 启动本地服务（`127.0.0.1` + 随机端口，可 `--port 4096`），自动开浏览器。三个核心界面：

| 界面 | 说明 | 组成 |
|---|---|---|
| **Homepage / New Session** | 首页，管理会话 | 会话列表（活动会话）、「新建会话」入口、「See Servers」入口 |
| **Active Session（聊天）** | 单个会话的对话视图 | 消息流（用户/助手气泡、工具调用、推理、diff）、底部输入区、顶部会话标题 |
| **Server Status** | 点击「See Servers」 | 已连接服务器及其状态 |

**映射到 aidev6 原生：**
- Homepage → 会话列表页（`GET /session` + `GET /session/status`）
- Active Session → `ChatPanel`（新建）核心页
- Server Status → 已有 `ServerPanel` 可承载/复用

**布局要点（原生聊天页参考）：**
- 顶部：会话标题栏（可显示当前 agent/model、会话状态 busy/idle 指示）
- 中部：可滚动消息流，按 Part 顺序渲染（见 §4）
- 底部：输入区（多行输入 + 发送 + 模型/agent 选择器 + 停止/中止按钮）
- 助手「思考中」用 `session.status: busy` 驱动 loading 态；`idle` 收敛

---

## 2. 默认 `opencode` 主题配色（完整色值）

来源：`packages/opencode/src/cli/cmd/tui/context/theme/opencode.json`。每个语义 token 有 `dark`/`light` 两个变体，均引用下方色阶定义（`defs`）。

### 2.1 色阶定义（defs）

**Dark：**
| 名称 | Hex | 用途 |
|---|---|---|
| darkStep1 | `#0a0a0a` | background |
| darkStep2 | `#141414` | backgroundPanel / diffContextBg |
| darkStep3 | `#1e1e1e` | backgroundElement |
| darkStep4 | `#282828` | — |
| darkStep5 | `#323232` | — |
| darkStep6 | `#3c3c3c` | borderSubtle |
| darkStep7 | `#484848` | border |
| darkStep8 | `#606060` | borderActive |
| darkStep9 | `#fab283` | **primary**（暖橙） |
| darkStep10 | `#ffc09f` | primary hover |
| darkStep11 | `#808080` | textMuted |
| darkStep12 | `#eeeeee` | text |
| darkSecondary | `#5c9cf5` | secondary（蓝） |
| darkAccent | `#9d7cd8` | accent（紫，heading） |
| darkRed | `#e06c75` | error |
| darkOrange | `#f5a742` | warning |
| darkGreen | `#7fd88f` | success |
| darkCyan | `#56b6c2` | info |
| darkYellow | `#e5c07b` | markdown emph/type |

**Light：**
| 名称 | Hex | 用途 |
|---|---|---|
| lightStep1 | `#ffffff` | background |
| lightStep2 | `#fafafa` | backgroundPanel |
| lightStep3 | `#f5f5f5` | backgroundElement |
| lightStep6 | `#d4d4d4` | borderSubtle |
| lightStep7 | `#b8b8b8` | border |
| lightStep8 | `#a0a0a0` | borderActive |
| lightStep9 | `#3b7dd8` | **primary**（蓝） |
| lightStep10 | `#2968c3` | primary hover |
| lightStep11 | `#8a8a8a` | textMuted |
| lightStep12 | `#1a1a1a` | text |
| lightSecondary | `#7b5bb6` | secondary |
| lightAccent | `#d68c27` | accent |
| lightRed | `#d1383d` | error |
| lightOrange | `#d68c27` | warning |
| lightGreen | `#3d9a57` | success |
| lightCyan | `#318795` | info |
| lightYellow | `#b0851f` | — |

### 2.2 语义 token → 色阶映射

| Token | dark | light | 原生用途建议 |
|---|---|---|---|
| primary | `#fab283` | `#3b7dd8` | 强调/发送按钮/链接强调 |
| secondary | `#5c9cf5` | `#7b5bb6` | 次强调 |
| accent | `#9d7cd8` | `#d68c27` | 标题、heading |
| error | `#e06c75` | `#d1383d` | 错误态 |
| warning | `#f5a742` | `#d68c27` | 警告 |
| success | `#7fd88f` | `#3d9a57` | 成功/构建通过 |
| info | `#56b6c2` | `#318795` | 信息 |
| text | `#eeeeee` | `#1a1a1a` | 正文 |
| textMuted | `#808080` | `#8a8a8a` | 次要文字/时间戳 |
| background | `#0a0a0a` | `#ffffff` | 页面底 |
| backgroundPanel | `#141414` | `#fafafa` | 卡片/气泡底 |
| backgroundElement | `#1e1e1e` | `#f5f5f5` | 输入框/内嵌块 |
| border | `#484848` | `#b8b8b8` | 边框 |
| borderActive | `#606060` | `#a0a0a0` | 聚焦边框 |
| borderSubtle | `#3c3c3c` | `#d4d4d4` | 细分隔线 |

### 2.3 Diff 色（工具输出/代码变更渲染）

| Token | dark | light |
|---|---|---|
| diffAdded | `#4fd6be` | `#1e725c` |
| diffRemoved | `#c53b53` | `#c53b53` |
| diffAddedBg | `#20303b` | `#d5e5d5` |
| diffRemovedBg | `#37222c` | `#f7d8db` |
| diffContext | `#828bb8` | `#7086b5` |
| diffLineNumber | `#8f8f8f` | `#595959` |

### 2.4 Markdown / Syntax 色（消息正文渲染）

| Token | dark | 来源 |
|---|---|---|
| markdownHeading | `#9d7cd8` | accent |
| markdownLink | `#fab283` | primary |
| markdownLinkText | `#56b6c2` | cyan |
| markdownCode | `#7fd88f` | green |
| markdownStrong | `#f5a742` | orange |
| markdownEmph / BlockQuote | `#e5c07b` | yellow |
| syntaxKeyword | `#9d7cd8` | accent |
| syntaxFunction | `#fab283` | primary |
| syntaxString | `#7fd88f` | green |
| syntaxVariable | `#e06c75` | red |
| syntaxNumber | `#f5a742` | orange |
| syntaxType | `#e5c07b` | yellow |
| syntaxComment | `#808080` | textMuted |

---

## 3. 主题体系机制

来源：[opencode.ai/docs/themes](https://opencode.ai/docs/themes/)

- **格式**：JSON，`defs`（可选，命名色）+ `theme`（语义 token）
- **颜色值**：Hex `"#ffffff"` / ANSI `3`(0-255) / 引用 `"primary"` / 变体 `{"dark":"#000","light":"#fff"}` / `"none"`（继承终端）
- **内置主题**：`opencode`(默认)、tokyonight、catppuccin、dracula、gruvbox、nord、one-dark、github、everforest、rosepine、vercel、vesper、matrix、synthwave84 等 30+
- **system 主题**：`generateSystem()` 依据终端 ANSI 调色板动态生成（primary=cyan、secondary=magenta、accent=cyan 等）
- **配置**：现新版在 `tui.json` 的 `theme` 字段（`opencode.json` 里的 `theme` 已废弃但自动迁移）

**aidev6 落地建议**：把 dark/light 两套语义 token 映射到 `AIDevTheme`（Compose `ColorScheme`）。可先固化 `opencode` 默认主题，后续再做主题切换（复用 `prefs`）。核实现有 `Color.kt`/`Theme.kt` 与上表对齐。

---

## 4. 聊天渲染数据模型（关键）

来源：SDK `packages/sdk/js/src/gen/types.gen.ts`。一条消息 = `{ info: Message, parts: Part[] }`。渲染时遍历 `parts`，按 `type` 分派 UI。

### 4.1 Message（info）

**UserMessage**：`{ id, sessionID, role:"user", time:{created}, agent, model:{providerID,modelID}, system?, tools? }`

**AssistantMessage**：`{ id, sessionID, role:"assistant", time:{created,completed?}, error?, parentID, modelID, providerID, mode, path:{cwd,root}, cost, tokens:{input,output,reasoning,cache:{read,write}}, finish? }`
- `error` 有类型：`ProviderAuthError | UnknownError | MessageOutputLengthError | MessageAbortedError | ApiError`
- `time.completed` 缺失 = 仍在生成
- `cost` / `tokens` 可显示用量

### 4.2 Part 类型（`Part` union，逐一渲染）

| type | 关键字段 | 渲染建议 |
|---|---|---|
| `text` | `text`, `synthetic?`, `ignored?`, `time?` | Markdown 正文气泡（用户/助手主体）；`synthetic`/`ignored` 可隐藏 |
| `reasoning` | `text`, `time:{start,end?}` | 折叠的「思考」块（可用 `thinkingOpacity` 淡化） |
| `file` | `mime`, `filename?`, `url`, `source?` | 附件/图片卡片；`source` 指向 file/symbol |
| `tool` | `tool`, `callID`, `state` | 工具调用卡片（见下 state） |
| `step-start` | `snapshot?` | 一步开始分隔（通常隐藏或细线） |
| `step-finish` | `reason`, `cost`, `tokens` | 步骤结束/用量小结 |
| `snapshot` | `snapshot` | 快照标记（通常隐藏） |
| `patch` | `hash`, `files[]` | 文件变更列表 |
| `agent` | `name`, `source?` | sub-agent 标记 |
| `retry` | `attempt`, `error`, `time` | 重试提示（警告色） |
| `compaction` | `auto` | 上下文压缩标记 |
| `subtask` | `prompt`, `description`, `agent` | 子任务卡片 |

### 4.3 ToolPart.state（工具调用状态机）

| status | 字段 | UI |
|---|---|---|
| `pending` | `input`, `raw` | 排队中（灰） |
| `running` | `input`, `title?`, `time:{start}` | 运行中 spinner + title |
| `completed` | `input`, `output`, `title`, `metadata`, `time:{start,end}`, `attachments?` | 完成卡片，可折叠 output/diff |
| `error` | `input`, `error`, `time:{start,end}` | 错误卡片（error 色） |

**渲染顺序**：`step-start` → `reasoning` → `tool`(pending→running→completed) → `text`(助手最终答复) → `step-finish`。工具卡片默认折叠、点开看 input/output。

---

## 5. 发送消息 API（原生聊天输入区对接）

来源：`docs/opencode-architecture.md` §4 + server 文档。

**发送并等待**：`POST /session/:id/message`
```
body: { messageID?, model?, agent?, noReply?, system?, tools?, parts }
返回: { info: Message, parts: Part[] }
```
- `parts` 为用户输入构成的 Part 数组（文本用 `{type:"text", text}`；附文件用 `{type:"file", mime, url, filename?}`）
- `model`：`{ providerID, modelID }`；`agent`：agent 名

**异步提交**（推荐配合 SSE 流式渲染）：`POST /session/:id/prompt_async` → `204`，随后通过 `GET /event` 的 `message.part.updated` / `message.part.delta` 流式拿增量。

**其它**：
- `POST /session` 建会话 `{ parentID?, title? }`
- `GET /session/:id/message?limit=` 拉历史
- `POST /session/:id/abort` 中止（对应输入区「停止」按钮）
- `POST /session/:id/permissions/:permissionID` 回应权限请求 `{ response, remember? }`（原生可弹审批）
- `GET /agent`、`GET /config/providers` 填充 agent/model 选择器

**流式渲染回路**（原生聊天核心）：
```
建会话 → prompt_async 提交
      → SSE /event: message.updated / message.part.updated / message.part.delta
      → 按 partID 增量更新对应 Part 卡片
      → session.status busy→idle：结束 loading
```

---

## 6. 原生落地清单（对接现有 aidev6 架构）

| 项 | 现有锚点 | 动作 |
|---|---|---|
| Tab 入口 | `ShellActivity.kt` companion（TAB_*，152-157） | 加 `TAB_CHAT` |
| 导航分支 | `AppNavHost.kt`（AnimatedContent，89-106） | 加 `ChatPanel` 分支 |
| 边缘抽屉（可选） | `EdgeSwipePanel.kt`（`PanelType`，45） | 加 `CHAT` |
| 新页面 | — | 新建 `ui/pages/ChatPanel.kt` |
| HTTP/SSE 客户端 | — | OkHttp + SSE 消费 `/session`、`/event`（端口 4096） |
| 主题 | `AIDevTheme`/`Color.kt`/`Theme.kt` | 对齐 §2 dark/light token |
| 复用能力 | `BuildBridgeService`、自进化闭环、`ServerPanel` | 聊天触发的构建走既有闭环 |
| 保留 | 终端 CLI（Termux terminal-view） | 不动 |

---

## 7. 参考来源

| 来源 | URL / 路径 |
|---|---|
| Web 文档 | https://opencode.ai/docs/web/ |
| Server / API 文档 | https://opencode.ai/docs/server/ |
| Themes 文档 | https://opencode.ai/docs/themes/ |
| 默认主题 JSON | `packages/opencode/src/cli/cmd/tui/context/theme/opencode.json` |
| 主题引擎 | `packages/opencode/src/cli/cmd/tui/context/theme.tsx` |
| SDK 类型（Message/Part） | `packages/sdk/js/src/gen/types.gen.ts` |
| 后端 HTTP/SSE 事实 | 本仓 `docs/opencode-architecture.md` |
</content>
</invoke>
