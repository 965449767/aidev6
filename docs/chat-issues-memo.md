# Chat UI 与官方 OpenCode 客户端差异分析备忘录

> 创建日期：2026-07-12
> 目的：记录 aidev6 聊天界面与官方 OpenCode CLI/Web 前端的所有已知差异和缺失功能，作为后续修复的备查清单。

## 修复记录

### b61（2026-07-12）— 权限流程 + 模型联动

**P0-1 权限流程：✅ 已修复**
- `ChatModels.kt`：`PermissionRequest` 增加 `v2`、`patterns`、`save` 字段
- `OpenCodeClient.kt`：`parsePermission` 同时解析 v1 (`permission`/`patterns`) 和 v2 (`action`/`resources`) 格式
- `OpenCodeClient.kt`：`replyPermission` 根据 ID 前缀 (`per_`) 选 v1 `/permissions/:id` 或 v2 `/permission/:id` 端点
- `OpenCodeClient.kt`：`parsePermission` 对 v2 replied 事件读 `requestID` 作为 id，确保弹窗能正确关闭
- `ChatPanel.kt`：SSE 处理器对 `.replied`/`.removed` 事件（含 v2）关闭弹窗
- `ChatPanel.kt`：`PermissionDialog` 显示 v2 的 `action` + `resources` + `save` 提示

**P1-3 模型跟随 agent：✅ 已修复**
- `ChatUiState.agentModels`：每种 agent 推荐模型（build→hy3-free，plan→deepseek-v4-flash-free）
- 切换 agent 时自动跟随推荐模型；用户手动选的模型记回该 agent
- 模型下拉显示 ★推荐 标记

**P1-4 动态模型列表：✅ 已修复**
- `OpenCodeClient.kt`：新增 `listProviders()`（GET `/provider`）
- `ChatPanel.kt`：初始化时动态填充 `state.models`，失败回退 `Constants.SELF_EVOLUTION_MODELS`

**P0-2 AI 追问（Question）：✅ 已修复（b62）**
- `ChatModels.kt`：新增 `QuestionRequest` / `QuestionOption` 数据类，`OcEvent` 增加 `question` 字段
- `OpenCodeClient.kt`：`parseQuestion` 解析 v1/v2 格式；`replyQuestion`/`rejectQuestion` 兼容两套端点
- `ChatPanel.kt`：SSE 处理器对 `question.asked`/`.replied`/`.rejected`（含 v2）弹窗/关闭；新增 `QuestionDialog`（单选/多选/自由输入）

**P1-5 斜杠命令（Slash Command）：✅ 已修复（b63）**
- `ChatModels.kt`：新增 `ChatCommand` 数据类
- `OpenCodeClient.kt`：新增 `listCommands()`（GET `/command`）、`sendCommand()`（POST `/session/:id/command`，解析 `/cmd args`）
- `ChatPanel.kt`：输入以 `/` 开头时弹出命令建议下拉；发送时路由到 `sendCommand`（驱动 /build、/compact 等自进化闭环）

**P2 SSE 会话生命周期覆盖：✅ 已修复（b64）**
- `ChatPanel.kt`：SSE 处理器新增 `session.created`/`session.updated`/`session.deleted` → 实时 `reloadSessions()`，会话列表与后端同步
- 顺带修复潜在 bug：原 `state.connLost=true; delay(2000)` 在每次事件后执行（阻塞 SSE 读取），现移至连接断开后（重连逻辑正确）

**P1-5 会话内 shell 执行：✅ 已修复（b65）**
- `OpenCodeClient.kt`：新增 `sendShell()`（POST `/session/:id/shell`）
- `ChatPanel.kt`：`send()` 路由 `/sh <cmd>` 到 `sendShell`（如 `/sh ls -la`），其余 `/` 走命令、普通文本走提示

**P1-5 文件变更查看：✅ 已修复（b66）**
- `ChatModels.kt`：新增 `FileDiff` 数据类
- `OpenCodeClient.kt`：新增 `getSessionDiff()`（GET `/session/:id/diff` → `Array<SnapshotFileDiff>`）
- `ChatPanel.kt`：顶栏新增 `⎘` 按钮拉取并弹窗展示 AI 的本会话改动（unified diff + 增删行数 + 状态）

**P1-6 文件浏览 UI：✅ 已修复（b67）**
- `ChatModels.kt`：新增 `FileNode`、`FileContent` 数据类
- `OpenCodeClient.kt`：新增 `listFiles()`（GET `/file`）、`findFiles()`（GET `/find`）、`readFile()`（GET `/file/content`）
- `ChatPanel.kt`：顶栏新增 `🗁` 按钮打开文件浏览器——目录树浏览、按名查找、查看文本文件内容（二进制提示不可预览）

**P1-7 Git 工作区变更：✅ 已修复（b68）**
- `OpenCodeClient.kt`：新增 `getVcsInfo()`（GET `/vcs`，返回分支名）、`getVcsStatus()`（GET `/vcs/status`）、`getVcsDiff()`（GET `/vcs/diff?mode=git`）
- `ChatPanel.kt`：顶栏新增 `⎇` 按钮——拉取分支信息+变更文件列表+unified diff，弹窗展示（标题含分支名，复用 DiffDialog）

**P2-6 会话 Fork：✅ 已修复（b69）**
- `OpenCodeClient.kt`：新增 `forkSession()`（POST `/session/:id/fork`）
- `ChatPanel.kt`：会话列表菜单新增"复制会话"选项——fork 后自动切换到新会话并刷新列表

**P2-7 权限"总是允许"保存：✅ 已修复（b70）**
- `OpenCodeClient.kt`：`replyPermission` 新增 `savePatterns` 参数，v2 "always" 时携带 `save` 数组
- `ChatPanel.kt`：PermissionDialog "总是允许" 按钮传递 `perm.save` 作为保存规则

**P2-8 会话压缩（compact）：✅ 已修复（b70）**
- `OpenCodeClient.kt`：新增 `compactSession()`（POST `/api/session/:id/compact`）
- `/compact` 斜杠命令已通过 `sendCommand` 路由到后端命令端点

---

## 调查来源

- `/root/projects/aidev6/docs/opencode-architecture.md` — 后端 HTTP/SSE 事实
- `/root/projects/aidev6/docs/opencode-web-ui-reference.md` — Web 前端设计参考
- `/root/.opencode/node_modules/@opencode-ai/sdk/dist/v2/gen/sdk.gen.d.ts` — 官方 v2 SDK 类型定义（2302 行）
- OpenCode 二进制 `/root/.opencode/bin/opencode`（嵌入源码分析）
- 本地 OpenCode 实例 `GET /agent` 实测

## 当前实现现状（b70）

已实现 25 个端点 + SSE 事件消费：

| 端点 | 方法 | 状态 |
|---|---|---|
| `/global/health` | GET | ✅ |
| `/agent` | GET | ✅ |
| `/provider` | GET | ✅ |
| `/command` | GET | ✅ |
| `/file` | GET | ✅ |
| `/file/content` | GET | ✅ |
| `/find` | GET | ✅ |
| `/vcs` | GET | ✅ |
| `/vcs/status` | GET | ✅ |
| `/vcs/diff` | GET | ✅ |
| `/session` | GET | ✅ |
| `/session` | POST | ✅ |
| `/session/:id/message` | GET | ✅ |
| `/session/:id/prompt_async` | POST | ✅ |
| `/session/:id/abort` | POST | ✅ |
| `/session/:id` | PATCH | ✅ |
| `/session/:id` | DELETE | ✅ |
| `/session/:id` | DELETE | ✅ |
| `/session/:id/command` | POST | ✅ |
| `/session/:id/shell` | POST | ✅ |
| `/session/:id/diff` | GET | ✅ |
| `/session/:id/fork` | POST | ✅ |
| `/session/:id/permissions/:permID` | POST | ✅ (v1) |
| `/session/:id/permission/:permID` | POST | ✅ (v2 + save) |
| `/api/session/:id/compact` | POST | ✅ |
| `/session/active` | GET | ✅ |
| `/event` | SSE | ✅ |

---

## P0 — 严重功能缺陷

### 问题 1：权限流程不工作（v2 事件被静默丢弃）

**现象：** 官方客户端每次 AI 做危险操作都会频繁申请权限；aidev6 只在最开始出现过一次，之后消失。

**根因（已确认）：**
- OpenCode 有 **两套权限系统**：v1 `permission.asked` 和 v2 `permission.v2.asked`
- 最新版 OpenCode（1.17+）使用 **v2 系统**，SSE 发的是 `permission.v2.asked`
- 事件数据格式不同：
  - v1 `permission.asked`：`{ id: "perm_xxx", sessionID, permission: "bash", patterns: [...], always: [...], tool: { messageID, callID } }`
  - v2 `permission.v2.asked`：`{ id: "per_xxx", sessionID, action: "bash", resources: [...], save: [...], source: { type: "tool", messageID, callID } }`
- 我们的 `parsePermission()` **只解析 v1 格式**，v2 事件因字段名不匹配被 `runCatching` 静默吞掉
- 回复端点也不同：
  - v1：`POST /session/:id/permissions/:permissionID`
  - v2：`POST /session/:id/permission/:requestID`（单数 form）

**修复方案：** 见下方「实施计划」。

**参考：** OpenCode 源码 `EventV2Bridge` 服务、`PermissionV2` 模块。

---

### 问题 2：问题（Question）处理完全缺失

**现象：** 官方客户端 AI 可发起追问（多选/自由输入），用户回答后继续。aidev6 无任何 UI 或 API 支持。

**根因：** 未实现 `question.asked` / `question.replied` / `question.rejected` SSE 事件，也未实现 `POST /question/:id/reply` 端点。

**影响：** 若 AI 发起追问，agent loop 会静默阻塞，用户看不到任何提示。

**修复方案：**
- `parseEvent` 增加 `question.asked` / `question.replied` / `question.rejected` 匹配
- 新增 `QuestionRequest` 数据类：`{ id, sessionID, question, header?, options: [{label, description}], multiple?, custom? }`
- 新增 `OpenCodeClient.askQuestionReply(sessionId, requestId, answers)`
- `ChatPanel` 新增追问弹窗（单选/多选/自由输入）

**参考：** SDK `QuestionV2Info` / `QuestionV2Option` / `QuestionV2Reply`。

---

## P1 — 与官方交互模型差异

### 问题 3：模型不跟随 agent（全局独立选择）

**现象：** 官方客户端切换 agent（build→plan）时，模型也跟着变（每种模式有推荐模型）。aidev6 全局模型选择器与 agent 完全独立。

**根因：** `createSession` / `prompt_async` 的 `agent` 和 `model` 参数没有联动。

**修复方案：**
- `ChatUiState` 增加 `agentModels: Map<String, ModelRef>`（每种 agent 的默认模型）
- 切换 agent 时自动选中该 agent 的推荐模型
- `send()` 时同时传 `agent` + `model`

**参考：** SDK `SessionCreateParams` 含 `{ agent, model }`；`SessionPromptAsyncParams` 同。

---

### 问题 4：模型列表硬编码

**现象：** `Constants.SELF_EVOLUTION_MODELS` 写死模型列表，无法反映后端实际可用模型。

**修复方案：**
- 新增 `OpenCodeClient.listProviders()`：`GET /config/providers`（v1）或 `GET /v2/model`（v2）
- `ChatPanel` 初始化时动态填充模型选择器

**参考：** SDK `ProviderListResponses` / `V2ModelListResponses`。

---

### 问题 5：API 端点覆盖不足（13/100+）

**已缺失的关键端点：**

| 端点 | 用途 | 优先级 |
|---|---|---|
| `GET /session/:id` | 单会话详情（含 agent/model/status） | 中 |
| `GET /session/status` | 批量会话状态 | 中 |
| `POST /session/:id/shell` | 会话上下文内运行 shell | 高 |
| `POST /session/:id/command` | 执行斜杠命令（/build, /compact 等） | 高 |
| `POST /session/:id/fork` | 在指定消息处 fork 会话 | 低 |
| `GET /session/:id/todo` | 会话 todo 列表 | 中 |
| `GET /session/:id/diff` | 消息产生的文件变更 | 中 |
| `POST /session/:id/compact` | 会话压缩 | 中 |
| `POST /session/:id/init` | 初始化会话（AGENTS.md） | 低 |
| `GET /file` + `GET /file/read` | 服务端文件浏览 | 中 |
| `GET /find/text` | ripgrep 搜索 | 中 |
| `GET /vcs/*` | Git status/diff | 中 |

**参考：** SDK `OpencodeClient` 完整命名空间。

---

## P2 — 体验差距

### 问题 6：SSE 事件覆盖不足

**现状：** 只处理 6 种事件（`session.status`、`permission.*`、`message.*`）。

**缺失的关键事件（70+ 种）：**
- `session.created` / `session.updated` / `session.deleted` / `session.error`
- `message.created` / `message.deleted`
- `diff.updated` / `todo.updated`
- `question.asked` / `question.replied`（见 P0-2）
- `session.compacted`

**参考：** SDK `V2Event` union（70+ 事件类型）。

---

### 问题 7：无文件浏览能力

**现状：** 依赖 PRoot 文件系统访问，缺失服务端文件 API。

**缺失：** `/file`、`/fs/*`、`/find/*` 系列。

---

### 问题 8-12：其他缺失功能

- 无会话 fork/diff/todo UI
- 无 shell 命令执行端点
- 无斜杠命令支持
- 无会话压缩（compact）
- 权限"总是允许"保存功能缺失（v2 `save` 字段未处理）

---

## 实施计划（按优先级）

### 第一批（P0）：权限流程 + 模型联动

1. **权限 v2 格式支持**
   - `ChatModels.kt`：`PermissionRequest` 增加 `v2: Boolean` 字段
   - `OpenCodeClient.kt`：`parseEvent` 匹配 `permission.v2.asked` / `permission.v2.replied`
   - `OpenCodeClient.kt`：`parsePermission` 解析 v2 格式（`action`→`permission`, `resources`→`patterns`）
   - `OpenCodeClient.kt`：`replyPermission` 根据 ID 前缀选 v1/v2 端点
   - `ChatPanel.kt`：`PermissionDialog` 显示 v2 的 `action` + `resources`

2. **模型跟随 agent**
   - `ChatUiState` 增加 `agentModels` map
   - 切换 agent 时联动模型
   - `send()` 同时传 agent + model

3. **动态模型列表**
   - `OpenCodeClient.kt`：`listProviders()`
   - `ChatPanel.kt`：初始化动态填充

### 第二批（P0-2）：问题处理

- 新增 `QuestionRequest` 数据类
- `parseEvent` 匹配 `question.*`
- `askQuestionReply()` 端点
- 追问弹窗 UI

### 第三批（P1/P2）：API 扩展

- 按优先级补全端点
- SSE 事件覆盖
- 文件浏览 UI

---

## 验证方法

1. 启动 aidev6 + OpenCode 后端
2. 在聊天中要求 AI 执行危险操作（如修改文件、运行 shell）
3. 确认权限弹窗频繁出现且可正常回复
4. 确认切换 build/plan 模式时模型联动
5. 确认模型列表来自后端实际可用模型
6. 确认 AI 追问时能正常回答

---

## 附录：官方权限事件完整格式

### v1 `permission.asked`
```json
{
  "id": "evt_xxx",
  "type": "permission.asked",
  "properties": {
    "id": "perm_xxx",
    "sessionID": "ses_abc",
    "permission": "bash",
    "patterns": ["git status"],
    "metadata": {},
    "always": [],
    "tool": { "messageID": "msg_001", "callID": "call_001" }
  }
}
```

### v2 `permission.v2.asked`
```json
{
  "id": "evt_xxx",
  "type": "permission.v2.asked",
  "properties": {
    "id": "per_xxx",
    "sessionID": "ses_abc",
    "action": "bash",
    "resources": ["git status"],
    "save": [],
    "metadata": {},
    "source": { "type": "tool", "messageID": "msg_001", "callID": "call_001" }
  }
}
```

### 回复端点
- v1：`POST /session/:id/permissions/:permissionID` `{ response: "once"|"always"|"reject" }`
- v2：`POST /session/:id/permission/:requestID` `{ reply: "once"|"always"|"reject" }`

---

## 附录：官方 Question 事件格式

### `question.asked` (v2)
```json
{
  "id": "evt_xxx",
  "type": "question.asked",
  "properties": {
    "id": "q_xxx",
    "sessionID": "ses_abc",
    "question": "要使用哪个分支？",
    "header": "分支选择",
    "options": [
      { "label": "main", "description": "主分支" },
      { "label": "dev", "description": "开发分支" }
    ],
    "multiple": false,
    "custom": true
  }
}
```

### 回复端点
- v1：`POST /question/:id/reply` `{ answers: [["main"]] }`
- v2：`POST /v2/session/:id/question/:requestID/reply` `{ questionV2Reply: { answers: [["main"]] } }`

---

## b84（2026-07-12）— 流式输出修复（核心）

**问题**：AI 输出不是流式显示，文本整体延迟出现。

**根因分析（对比官方 OpenCode Web 源码）**：
- 官方 SDK：用 `message.part.updated` 事件携带完整 part 对象（含最新 text），直接替换本地 parts
- 我们（原实现）：用 `message.part.delta` 追加到 `streamingText` 缓冲，但 `part.updated` 时**立即清空缓冲 + 全量 refresh()** → 文本闪烁/丢失
- 官方无单独的 streaming buffer，delta 事件作为低级细节由 SDK 内部处理

**修复方案**：
- `ChatModels.kt`：`ChatPart.Text` 新增 `partId`；`OcEvent` 新增 `updatedMessage` 字段
- `OpenCodeClient.kt`：`parseEvent` 对 `message.part.updated` / `message.updated` 事件解析完整消息存入 `updatedMessage`
- `ChatPanel.kt` SSE handler：
  - `part.updated` → 从事件提取完整消息，**直接替换 `messages` 中对应消息的 parts**（不清空 buffer、不 refresh）
  - `delta` 事件仍追加到 `streamingText` 作为中间态（bridge delta → part.updated）
  - `message.updated` + 无 updatedMessage → 兜底 refresh()
- 流式光标 ▍ 改为 `state.busy && isLastAssistant`（不再依赖 streamingText.containsKey）

---

## b85（2026-07-12）— Tool 输出修复（核心）

**问题**：Tool 输出只显示"修改了哪个文件"，不显示实际代码/diff 内容。

**根因分析**：
- 官方 `BasicTool`：折叠显示 input 信息（文件名/命令）；展开显示 output（完整代码/diff）
- 我们（原实现）：
  - `ChatPart.Tool` 缺少 `input` 字段（工具调用参数）
  - 显示优先级 `title ?: output ?: errorText` → `title`（如 "Modified file: main.kt"）总是遮盖 `output`（实际内容）
  - 用户看到的永远是文件名，看不到改了什么

**修复方案**：
- `ChatModels.kt`：`ChatPart.Tool` 新增 `input: Map<String, Any?>` + `toolCallId: String?`
- `OpenCodeClient.kt`：`parsePart` 提取 `state.input` JSON → Map
- `ChatPanel.kt` PartView Tool 分支重写：
  - 折叠：显示 `getToolInfo(name, input)` 摘要（文件名/命令描述）
  - 展开：显示 input 参数 + output（完整内容）+ error
- 新增 `getToolInfo()` 辅助函数：按工具名（edit/write/read/shell/glob/grep）提取关键 input 信息

---

## b86（2026-07-12）— parsePart 增强

**改进内容**：
- `ChatModels.kt`：新增 `ChatPart.File(path, type)` + `ChatPart.Patch(files)` 类型
- `OpenCodeClient.kt`：`parsePart` 新增 `file` / `patch` 类型解析
- `ChatPanel.kt`：PartView 新增 File/Patch 渲染（📄 文件路径 / 📝 文件列表）
- `MessageBubble` fullText 复制逻辑同步更新

---

## M8 增强（2026-07-12）— 停止生成按钮升级

**原状**：`state.busy` 时显示红色 `■` 文本字符的 `IconButton`，视觉不够醒目。

**增强方案**：
- `ChatUiState` 新增 `stopping: Boolean` 状态
- 停止按钮改为 `Surface(onClick, CircleShape, error 色, 36.dp)` 圆形红色背景
- 点击后显示 `CircularProgressIndicator`（白色，18dp）+ "停止中…" 反馈
- `LaunchedEffect(busy)` 在 busy→idle 时自动重置 `stopping`
- 超时保护也同步清除 `stopping`

---

## b87（2026-07-12）— Markdown 渲染升级

**增强内容**（在手写解析器基础上补齐 6 个缺失语法）：
- **表格**：解析 `| col1 | col2 |` 连续行，渲染为 `TableBlock`（表头加粗 + 分割线 + 数据行）
- **引用块**：解析 `> text` 连续行，渲染为 `BlockquoteBlock`（左侧 3dp 绿色竖线 + 半透明背景）
- **删除线**：`~~text~~` 渲染为 `TextDecoration.LineThrough`
- **任务列表**：`- [x]` / `- [ ]` 渲染为 ☑/☐ checkbox + 内容
- **水平线**：`---` / `***` / `___` 渲染为 `HorizontalDivider`
- **图片**：`![alt](url)` 渲染为带下划线的链接（无 Coil，不直接显示图片）
- **代码块语言标签**：CodeBlock 新增 `lang` 参数，显示语言名称

**代码变更**：`ChatPanel.kt` — `MarkdownText` 重写为逐行状态机（支持表格/引用块的多行收集）、新增 `TableBlock`/`BlockquoteBlock` 组件、`InlineText` 增加删除线+图片模式、CodeBlock 新增 `lang` 参数

---

## b88（2026-07-12）— Reasoning 渲染升级

**变更**：`PartView` 中 `ChatPart.Reasoning` 从纯 `Text(...)` 改为 `MarkdownText(part.text, onSurfaceVariant)`，与 text part 同等渲染能力。

---

## 本轮完成总结（b84-b93 + M8）

| 批次 | 内容 | 状态 |
|------|------|------|
| b84 | 流式输出修复：part.updated 直接更新 parts | ✅ |
| b85 | Tool 输出修复：input 摘要 + output 展开 | ✅ |
| b86 | parsePart 增强：File/Patch 类型 + getToolInfo | ✅ |
| M8 | 停止按钮增强：红色圆形 + CircularProgressIndicator | ✅ |
| b87 | Markdown 升级：表格/引用/删除线/任务列表/水平线/图片/语言标签 | ✅ |
| b88 | Reasoning 改用 MarkdownText | ✅ |
| b90 | Tool 专用渲染器 x10：bash/edit/write/read/glob/grep/websearch/webfetch/task/todowrite/question | ✅ |
| b91 | Part 类型补齐：SourceUrl/StepStart/Compaction/Agent/QuestionAnswer | ✅ |

**修改文件**：`ChatModels.kt`、`OpenCodeClient.kt`、`ChatPanel.kt`

---

## b94-b99（2026-07-12）— ServerPanel 全面重设计

**背景**：ServerPanel（服务中心）审计发现 L1-L8 布局问题、O1-O5 组织问题、B1-B9 Bug、U1-U7 UX 建议，原单 Column 709 行难以维护。用户确认 3 Tab 方案后一次性重写。

### 架构变更（b94-b97）
- **3 Tab 导航**：`TabRow` + `Crossfade`，独立滚动，状态/任务/进化各自聚焦
- **Tab 1 状态**：系统状态 2×2 网格 + 快速诊断（合并服务操作+AI助手）+ 安装工具
- **Tab 2 任务**：快速任务（动态模板+Android闭环）+ 构建项目选择器 + 提交构建 + 任务记录列表 + 崩溃报告卡片
- **Tab 3 进化**：闭环状态 + 自治模式开关 + 改码模型选择器 + 对话日志滚动 + 设计原则

### Bug 修复（b98）
| Bug | 修复 |
|-----|------|
| B1 状态指标不刷新 | 5s 轮询 `taskCount(context)` |
| B2 lastBuildResult 静态 | `LaunchedEffect` 动态读磁盘 |
| B3 取消任务不更新 | 轮询已覆盖（reconcile 每次读磁盘） |
| B4 删除/清空无确认 | `ConfirmDialog`（AlertDialog） |
| B5 轮询异常崩溃 | `runCatching` 包裹所有磁盘读写 |
| B6 重试缺项目名 | `projectOf(record).ifBlank { selectedProject.value }` |
| B7 JSON 拼接注入 | `org.json.JSONObject().put("model", model)` |
| B8 autonomousOn 不同步 | 3s 轮询 `PreferencesManager` 同步外部变化 |
| B9 upsertRecord 不统一 | 提取为 lambda，所有调用点统一 |

### 其他改进
- 任务日志：`heightIn(max=240.dp)` + `verticalScroll` 可滚动，不再被截断
- 对话日志：`heightIn(max=320.dp)` + `verticalScroll`
- 崩溃报告：卡片式（摘要+点击展开），5s 轮询更新
- `stepStatusLabel`/`taskStatusColor` 提取为共享函数，消除重复

**修改文件**：`ServerPanel.kt`（709 行 → ~570 行，更清晰的 Tab 结构）

---

## 本轮完成总结（b84-b93 + M8 + b94-b99）

| 批次 | 内容 | 状态 |
|------|------|------|
| b84 | 流式输出修复：part.updated 直接更新 parts | ✅ |
| b85 | Tool 输出修复：input 摘要 + output 展开 | ✅ |
| b86 | parsePart 增强：File/Patch 类型 + getToolInfo | ✅ |
| M8 | 停止按钮增强：红色圆形 + CircularProgressIndicator | ✅ |
| b87 | Markdown 升级：表格/引用/删除线/任务列表/水平线/图片/语言标签 | ✅ |
| b88 | Reasoning 改用 MarkdownText | ✅ |
| b90 | Tool 专用渲染器 x10：bash/edit/write/read/glob/grep/websearch/webfetch/task/todowrite/question | ✅ |
| b91 | Part 类型补齐：SourceUrl/StepStart/Compaction/Agent/QuestionAnswer | ✅ |
| b94-b99 | ServerPanel 全面重设计：3 Tab + 9 Bug 修复 + 确认弹窗 + 日志滚动 | ✅ |

**修改文件**：`ChatModels.kt`、`OpenCodeClient.kt`、`ChatPanel.kt`、`ServerPanel.kt`
