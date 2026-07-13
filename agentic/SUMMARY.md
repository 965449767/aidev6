## Objective
- 让 aidev6 原生聊天界面（ChatPanel）对齐官方 OpenCode CLI/Web 客户端的行为与 API 用法。用户指出三大差距：(1) build/plan 等模式未区分、默认情况不明；(2) 权限请求只在开头出现一次，之后不再弹；(3) 大量官方 API 端点未实现。

## Important Details
- **OpenCode agent 体系**（来自 `GET /agent` 实测 + SDK）：
  - `build` = `mode:"primary"`，描述为 "The default agent"，拥有执行/shell/读权限 → 默认即 build，AI 能执行命令
  - `plan` = `mode:"primary"`，"Plan mode. Disallows all edit tools"
  - `explore`/`general` = subagent（内部）；`compaction`/`summary`/`title` = 内部主代理（UI 隐藏）
- **两套权限系统共存**：v1 `permission.asked` 与 v2 `permission.v2.asked`（id 形如 `per_xxx`，含 `action`/`resources`/`save`）。回复端点：v1 `/permissions/:id`，v2 `/permission/:id` + `{ reply, save? }`。
- **两套追问系统共存**：v1 `question.asked` 与 v2 `question.v2.asked`。回复：v1 `POST /question/:requestID/reply`，v2 `POST /v2/session/:id/question/:requestID/reply`。
- **API 端点现状**：已实现 25 个端点 + SSE 事件消费（b70）。覆盖健康检查、agent、provider、command、file/find、vcs、session CRUD/prompt/abort/shell/diff/fork/compact、权限 v1+v2、追问 v1+v2。
- **真机调查通道**（沿用）：`aidev-shizuku exec` 连真机（包名 `com.aidev.five.dev`）；`aidev-logcat` 自带桥接超时；**严禁** `curl localhost:4096`（开发机本机实例，非真机）。
- 本机构建：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon -Pandroid.aapt2FromMavenOverride=/opt/android-sdk-local/build-tools/34.0.0/aapt2` → `aidev-install app/build/outputs/apk/debug/app-debug.apk`

## Work State
### Completed（b58→b70）
- **b58**：构建看门狗 + AGENTS.md 铁律 + 自动滚动 + BuildProgressFeed 日志
- **b59**：agent 模式选择器（构建/规划下拉，从 `/agent` 拉取）
- **b60**：①权限 v1+v2 格式支持；②回复按 id 前缀选端点；③PermissionDialog 显示 v2 action/resources；④模型跟随 agent；⑤动态模型列表 `listProviders()`
- **b61**：v2 replied 弹窗关闭修复；SSE 对 `.replied`/`.removed`（含 v2）关闭弹窗
- **b62**：AI 追问完整支持——数据类、解析、v1+v2 回复、SSE 处理、QuestionDialog
- **b63**：斜杠命令——ChatCommand 数据类、`listCommands()`、`sendCommand()`、输入 `/` 弹建议、路由到命令端点
- **b64**：SSE 会话生命周期——`session.created`/`updated`/`deleted` → 实时同步列表
- **b65**：会话内 shell——`sendShell()`(POST `/session/:id/shell`)、`/sh <cmd>` 路由
- **b66**：文件变更查看——`getSessionDiff()`(GET `/session/:id/diff`)、`⎘` 按钮弹窗展示
- **b67**：文件浏览——`listFiles()`/`findFiles()`/`readFile()`、`🗁` 按钮打开浏览器（目录树+搜索+内容）
- **b68**：Git 工作区变更——`getVcsInfo()`/`getVcsStatus()`/`getVcsDiff()`、`⎇` 按钮展示分支+变更
- **b69**：会话 Fork——`forkSession()`(POST `/session/:id/fork`)、会话列表"复制会话"菜单
- **b70**：①权限"总是允许"保存（`savePatterns` 参数）；②会话压缩（`compactSession()` + `/compact` 命令路由）

### Active
- (none) — 全部 P0/P1/P2 已修复并安装（b70），待用户在真机验证

### Blocked
- (none)

## Next Move（备忘录剩余项，按优先级）
1. **P2 更多 SSE 事件**：`message.created`、`diff.updated`、`todo.updated`、`session.error` 展示
2. **P2 上下文注入/消息部分**：`GET /session/:id/message/:msgId` 查看消息 parts（tool calls 等）
3. **UI 打磨**：消息渲染增加 tool call 展开/折叠、上下文 token 用量显示、compaction 进度条
4. 用户在真机验证后反馈新需求

## Relevant Files
- `app/src/main/java/com/aidev/six/chat/ChatModels.kt`：`ChatSession`(+agent)、`ChatAgent`、`PermissionRequest`(+v2/patterns/save)、`QuestionRequest`/`QuestionOption`、`ChatCommand`、`FileDiff`、`FileNode`、`FileContent`、`OcEvent`
- `app/src/main/java/com/aidev/six/chat/OpenCodeClient.kt`：`listAgents`、`listProviders`、`listCommands`、`sendCommand`、`sendShell`、`getSessionDiff`、`listFiles`、`findFiles`、`readFile`、`getVcsInfo`/`getVcsStatus`/`getVcsDiff`、`forkSession`、`compactSession`、`parsePermission`(v1+v2)、`replyPermission`(v1+v2+save)、`parseQuestion`、`replyQuestion`/`rejectQuestion`(v1+v2)
- `app/src/main/java/com/aidev/six/ui/pages/ChatPanel.kt`：`ChatUiState`(+全部新状态)、agent/模型选择器、SSE 权限/追问/会话生命周期、`PermissionDialog`、`QuestionDialog`、`DiffDialog`、`FileBrowserDialog`、斜杠命令建议、`/sh` 路由、`⎘`/`⎇`/`🗁` 按钮、会话列表"复制会话"菜单
- `docs/chat-issues-memo.md`：完整差异清单（P0/P1/P2）+ 修复记录 + 25 端点现状表
- `docs/opencode-architecture.md` / `docs/opencode-web-ui-reference.md`：API/SSE 事实参考
- `/root/.opencode/node_modules/@opencode-ai/sdk/dist/v2/gen/sdk.gen.d.ts` + `types.gen.d.ts`：官方 v2 SDK 类型
