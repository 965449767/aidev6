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

## 3. 终端与 AI 工具关系（已彻底解除耦合）

- 项目**不含任何 OpenCode / AI 写码代理集成代码**：`OpenCodeEngine` / `AIEngine` / `OpenCodeActionReceiver` / `OpenCodeMonitorService` 已全部删除，`Constants.OPENCODE_BASE_URL` 等常量已移除。
- 终端是人类的**唯一开发入口**；若用户自行在 Ubuntu 容器内安装 OpenCode 等工具，也只是普通终端命令，宿主不与之有任何程序级耦合（无端口绑定、无 SSE 监听、无通知按钮）。
- `docs/opencode-architecture.md` 仅保留为 OpenCode HTTP/SSE 协议参考，与宿主 App 的运行时解耦。

## 4. 桥接通信（宿主 ↔ PRoot）

所有「宿主 App ↔ PRoot 宇宙」请求桥统一继承 `BridgeService`：

- **Socket 主用（TCP loopback）**：`BridgeSocketServer` + `TcpBridgeTransport`，绑定 `127.0.0.1:14096`（`Constants.BRIDGE_SOCKET_PORT`），`BRIDGE_SOCKET_ENABLED` 开关（默认 true）。
  - 帧格式 `BridgeFrame{bridge, id, payload, auth}`：4 字节大端长度头 + JSON。
  - `auth` 为静态共享密钥（`Constants.BRIDGE_SOCKET_TOKEN`），服务端校验来源；客户端 `aidev-bridge` 携带。
  - 有界线程池 + `Semaphore` 限连 + `soTimeout` 防本地滥用。
- **文件轮询兜底（长期保留）**：`poll()` 每 500ms 扫描各自 `BRIDGE_DIR`，`claimFile` 重命名锁防重复消费；socket 不可用时自动回退。
- **路由**：`BridgeRegistry` 按桥名分发到对应 `*BridgeService.dispatch`。
- **四桥**：`Notify`/`Shizuku`/`Build`/`Deploy`；前两者同步返回结果，后两者仅落盘 `req-<id>.json` 并返回 `"accepted"`，由既有轮询流程异步处理。（原 `Crash` 桥已移除：崩溃仅由 `CrashGuard` 落本地文件供人类排查。）

## 5. 构建与部署（人类驱动）

- `BuildBridgeService` / `DeployBridgeService`：编译与部署黑盒，状态经 `agent-tasks.json` 单一真源回流。
- 唯一构建入口为终端命令 `aidev-build-request --project <路径>`（App「编译」按钮即向终端会话写入该命令）；构建失败仅把完整日志落到 `logs/<project>/last-build-failure.log`，不自动改写工程文件、不写 AI 回流。
- `agent/AgentTaskRunner`（`object` 单例）：执行 shell 命令，带超时与 `destroyForcibly()` 强制取消，防挂死。

## 6. 关键约束

- 无 Hilt、无 Retrofit、单模块；改动纯加法。
- 无相机/麦克风/联系人/短信/定位权限。
- 危险命令经 `SafeCommandGuard` 拦截（非交互上下文直接失败）。
