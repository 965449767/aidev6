# AIDev6 应用架构（宿主侧）

> 给 AI agent 的参考文档：描述 **aidev6 宿主 App** 自身的入口、运行时、PRoot 集成与桥接通信。
> 区别于 `docs/opencode-architecture.md`（OpenCode HTTP/SSE 协议参考）。

---

## 1. 进程与入口

| 组件 | 文件 | 说明 |
|---|---|---|
| 启动器 | `ShellActivity.kt` | 唯一 `ComponentActivity` + `setContent`，无 XML 布局；应用唯一入口 |
| 应用对象 | `AIDevApp.kt` | `Application` 子类，全局初始化 |
| 保活 | `KeepAliveService.kt` + `KeepAliveBootReceiver.kt` | 前台 Service + 开机广播，维持 PRoot/桥接常驻 |
| 导航 | `navigation/AppNavHost.kt` | Jetpack Navigation Compose，统一路由 |

## 2. 终端与 PRoot 宇宙

- **终端**：Termux `terminal-view` 库，`EmbeddedTerminalPage` / `terminal/SessionManager` 管理会话。
- **Ubuntu（宇宙 A）**：打包 PRoot（`--link2symlink`），运行在 `filesDir/home/ubuntu-rootfs`。
- **就绪判定**：`home/ubuntu-rootfs/.aidev-rootfs-ready` 存在（非 `etc/os-release`）。
- **脚本部署**：`TerminalShellAssets.copyAssetScripts` 每次 App 启动重放资产脚本到 rootfs（改脚本后需强制停止+重启 AIDev 才生效）。
- **运行时家目录**：`filesDir/home`（宿主侧）；rootfs 内经 `/host-home` 挂载可见。

## 3. OpenCode 集成

- 固定端口 `127.0.0.1:4096`（`Constants.OPENCODE_BASE_URL`）。
- `OpenCodeMonitorService` 经 SSE 监听 `session.status: busy→idle` 触发 `sysnotify` 通知。
- 详见 `docs/opencode-architecture.md`。

## 4. 桥接通信（宿主 ↔ PRoot）

所有「宿主 App ↔ PRoot 宇宙」请求桥统一继承 `BridgeService`：

- **Socket 主用（TCP loopback）**：`BridgeSocketServer` + `TcpBridgeTransport`，绑定 `127.0.0.1:14096`（`Constants.BRIDGE_SOCKET_PORT`），`BRIDGE_SOCKET_ENABLED` 开关（默认 true）。
  - 帧格式 `BridgeFrame{bridge, id, payload, auth}`：4 字节大端长度头 + JSON。
  - `auth` 为静态共享密钥（`Constants.BRIDGE_SOCKET_TOKEN`），服务端校验来源；客户端 `aidev-bridge` 携带。
  - 有界线程池 + `Semaphore` 限连 + `soTimeout` 防本地滥用。
- **文件轮询兜底（长期保留）**：`poll()` 每 500ms 扫描各自 `BRIDGE_DIR`，`claimFile` 重命名锁防重复消费；socket 不可用时自动回退。
- **路由**：`BridgeRegistry` 按桥名分发到对应 `*BridgeService.dispatch`。
- **五大桥**：`Notify`/`Shizuku`/`Build`/`Deploy`/`Crash`；前两者同步返回结果，后三者仅落盘 `req-<id>.json` 并返回 `"accepted"`，由既有轮询流程异步处理。

## 5. 自我进化闭环

- `agent/BuildRequestTracker`：提交构建请求（`submit` 写 `req-<id>.json` + 插入 RUNNING 记录）、崩溃回流（`watchCrashReport`）、自治重建（未修复则自动触发下一轮，上限 `MAX_AUTO_ITERATIONS=10`）。
- `agent/AgentTaskRunner`（`object` 单例）：执行 shell 命令，带超时与 `destroyForcibly()` 强制取消，防挂死。
- `BuildBridgeService` / `DeployBridgeService`：编译与部署黑盒，状态经 `agent-tasks.json` 单一真源回流。

## 6. 关键约束

- 无 Hilt、无 Retrofit、单模块；改动纯加法。
- 无相机/麦克风/联系人/短信/定位权限。
- 危险命令经 `SafeCommandGuard` 拦截（非交互上下文直接失败）。
