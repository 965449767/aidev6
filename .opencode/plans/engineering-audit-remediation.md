# 工程审计与修复计划（aidev6）

> 状态：计划待批准。用户已确认「先修 ①②③，其余 ④⑤⑥⑦ 记录待续」。
> 审计依据：人工走查 + explore 子代理（7 维度）+ 实测 3 个「预存失败」测试的真实失败原因。

---

## 0. 审计结论（一句话）
对「单人维护的本地设备开发工具」已足够专业；距「生产级稳定交付」还差三块：**自主循环挂死风险**、**未设防桥接 Socket**、**政策红测试套件 + 无 CI**。本轮只做 ①②③（稳定性 + 测试闸门，纯加法可回滚）。

---

## ① AgentTaskRunner 弹性（高风险：自主循环可被永久卡死）

文件：`app/src/main/java/com/aidev/six/agent/AgentTaskRunner.kt`

**问题（F1/F2/F6）**
- `process.waitFor()` 无超时（:139）；进程无输出时挂起，`waitFor` 永不返回。
- `cancelTask`（:163）只设标志位，`execProcess` 读循环仅在读到行时检查标志 → 无输出挂起时取消无效。
- `internal class` 被 `ServerPanel.kt:89` 的 `remember { AgentTaskRunner() }` 反复实例化，`Executors.newSingleThreadExecutor()` 永不 `shutdown()` → executor 泄漏 + cancel 不可靠。

**改动**
1. `internal class AgentTaskRunner` → `internal object AgentTaskRunner`（单例，消除泄漏）。调用点 `ServerPanel.kt:89` 改 `remember { AgentTaskRunner }`（或直接使用 `AgentTaskRunner`）。
2. 新增常量 `private val DEFAULT_EXEC_TIMEOUT_MS = 10 * 60_000L`。
3. 重写 `execProcess`（签名加 `timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS`）：
   - 用独立 daemon 线程读 `inputStream` 并逐行 `onLine`；主线程循环 `process.waitFor(250, TimeUnit.MILLISECONDS)`，每轮检查 `cancellationFlag` 与 `deadline`；命中则 `process.destroyForcibly()` 并跳出。
   - `finally { reader.join(5000) }` 后取 `exitValue()`（已退出取真实码，否则 -1）。
   - 需 import `java.util.concurrent.TimeUnit`。
4. 保留 `cancelTask` 现有逻辑（`destroy()` 仍有效；新逻辑下标志位 + `waitFor` 轮询即可强制结束）。

**验证**：`AgentTaskRunner` 无独立单测（现存），靠编译 + 手动。可在后续补一个 exec 超时单测（非本轮强制）。

---

## ② 桥接 Socket 设防（中高风险：本地 DoS + 无鉴权）

涉及文件：
- `app/src/main/java/com/aidev/six/Constants.kt`（新增 token）
- `app/src/main/java/com/aidev/six/BridgeFrame.kt`（新增 `auth` 字段）
- `app/src/main/java/com/aidev/six/BridgeTransport.kt`（有界池 + soTimeout + 鉴权）
- `app/src/main/java/com/aidev/six/BridgeService.kt`（传入 token）
- `app/src/main/java/com/aidev/six/BridgeSocketServerTest.kt`（新增鉴权拒绝单测，可选）
- `app/src/main/assets/scripts/aidev-bridge.sh`（客户端携带 token）

**问题（F3/F4）**
- 每连接 `Thread{}.start()` 无限线程、无 `soTimeout` → 慢客户端可耗尽线程（本地 slow-loris）。
- `127.0.0.1:14096` 无鉴权 → 任意本机 App 可投递 build/deploy/crash 帧。

**改动**
1. `Constants.kt` 新增：
   ```kotlin
   // 桥接 Socket 静态共享密钥（仅本机 IPC 源认证，防止其他本地 App 注入请求帧）
   const val BRIDGE_SOCKET_TOKEN = "aidev-bridge-2026"
   ```
2. `BridgeFrame.kt`：
   - `data class BridgeFrame(val bridge: String, val id: String, val payload: String, val auth: String = "")`。
   - `toJsonString()` 增加 `put("a", auth)`。
   - `parse()` 增加 `auth = o.optString("a", "")`。
   - 既有 `BridgeFrame("x","y","z")` 调用因默认参数仍编译通过；`BridgeFrameTest` 仅校验 b/i/p，不受影响。
3. `BridgeTransport.kt` 的 `TcpBridgeTransport`：
   - 构造参数增加 `authToken: String? = null`、`maxConnections: Int = 16`、`socketTimeoutMs: Int = 30_000`。
   - 新增 `private val executor = Executors.newFixedThreadPool(maxConnections)` 与 `private val semaphore = Semaphore(maxConnections)`。
   - `start`：`accept` 后 `if (!semaphore.tryAcquire()) { sock.close(); continue }`；`executor.execute { handle(sock, handler); semaphore.release() }`（替换 `Thread{}.start()`）。
   - `handle`：开头 `sock.soTimeout = socketTimeoutMs`；读帧后 `if (authToken != null && req.auth != authToken) { AIDevLogger.w(...); return }`。
   - `stop`：`executor.shutdownNow()` + 既有关闭逻辑。
   - `TcpBridgeClient` 构造增加 `authToken: String = ""`，`request(frame)` 发送 `BridgeFrame(frame.bridge, frame.id, frame.payload, authToken)`。
4. `BridgeService.startSocketIfEnabled`：`TcpBridgeTransport(host="127.0.0.1", port=Constants.BRIDGE_SOCKET_PORT, authToken=Constants.BRIDGE_SOCKET_TOKEN)`。
5. `aidev-bridge.sh`：
   - 顶部加 `TOKEN="aidev-bridge-2026"`（注释：须与 `Constants.BRIDGE_SOCKET_TOKEN` 一致）。
   - `send_via_tcp` 的 python 调用改为 `python3 - "$BRIDGE" "$ID" "$PAYLOAD" "$PORT" "$TOKEN" <<'PY'`，python 内 `token=sys.argv[5]`，帧改为 `json.dumps({"b":bridge,"i":mid,"p":payload,"a":token})`。
   - `status` 子命令只建连不发包，不受鉴权影响，无需改。
6. 可选单测：在 `BridgeSocketServerTest` 增加一例「server 设 `authToken`，client 带错 token → 无响应/被拒」。

**注意**：部署后须强制停止并重启 AIDev 才会部署带 token 的新 `aidev-bridge.sh`；重启前旧脚本无 token 会被拒（与既有部署模型一致）。

---

## ③ 测试套件转绿（3 个预存失败 —— 均为真实缺陷/用例笔误，非环境）

### 3.1 BuildDiagnostics（真实漏诊）
文件：`app/src/main/java/com/aidev/six/BuildDiagnostics.kt`
- 失败用例 `resource linking failed detected`（BuildDiagnosticsTest.kt:78 期望 `hints.size >= 2`）。
- 根因：正则 `resource (\S+).*?not found` 要求字面 "resource"，但 Gradle 真实格式为 `color/calc_background not found`（无 "resource" 词）→ 返回 0 条。
- 改动：正则改为覆盖 `type/name not found`（含可选 `resource ` 前缀与 `(aka ...)`）：
  ```kotlin
  val resRe = Regex("""(?:resource )?((?:color|string|layout|drawable|dimen|style|mipmap|id|menu|anim|attr|array|integer|bool|font|raw|xml|navigation|transition)/(?:\S+?))(?:\s*\([^)]*\))?\s+not found""")
  val missingResources = resRe.findAll(log).map { it.groupValues[1] }.distinct().toList()
  ```
  既覆盖 `resource color/calc_background (aka …) not found`，也覆盖 `color/calc_background not found`；且不会对 keystore 路径（无资源类型前缀）误报。

### 3.2 BuildPreflightSourceTest（用例笔误）
文件：`app/src/test/java/com/aidev/six/BuildPreflightSourceTest.kt`
- 失败用例 `detects missing import class`（:22 期望消息含 `MissingClass`）。
- 根因：用例输入 `import com.example.app MissingClass`（非法 Kotlin，空格代替点）；import 正则 `import\s+([\w.]+)` 只捕获到 `com.example.app`，消息不含 `MissingClass`。功能本身对合法 `import com.example.app.MissingClass` 可正确告警。
- 改动：将用例输入 `import com.example.app MissingClass` 改为 `import com.example.app.MissingClass`。其余 3 个用例不受影响。

### 3.3 BuildRequestTracker（真实缺口：未插入承诺的 RUNNING 记录）
文件：`app/src/main/java/com/aidev/six/agent/BuildRequestTracker.kt`
- 失败用例 `requestRebuildTriggersBuildRequestFile`（:119 期望 `recorded.isNotEmpty()`；:118 的 `req` 实际已找到，说明写文件成功，只是未插入 RUNNING 记录）。
- 根因：`submit()` 写 `req-<id>.json` 后直接进入 `scope.launch` 轮询结果，**从未插入 RUNNING 任务记录并回调 `onUpdate`**；但类注释（:22-24）明确承诺「立即插入一条 RUNNING 记录」。
- 改动：在 `submit()` 写文件成功分支（`writeOk` 为 true）之后、`scope.launch` 之前，构造并插入 RUNNING 记录：
  ```kotlin
  val runningRecord = AgentTaskRecord(
      definition = AgentTaskDefinition(
          id = "build-$id", name = "构建 $project",
          description = "宇宙 B 编译 → 产出 APK（安装/拉起由 aidev-deploy 独立黑盒接力）",
          command = "aidev-build-request --project $project",
          workingDirectory = PathConfig.workspaceDir(appCtx).absolutePath,
          tags = listOf("build", "self-evolution")
      ),
      status = AgentTaskStatus.RUNNING,
      startedAt = startedAt, finishedAt = 0L, exitCode = -1,
      log = "⏳ 已提交构建请求，等待宇宙 B 编译…",
      lastUpdatedAt = System.currentTimeMillis()
  )
  AgentTaskStore.upsertTask(stateFile, runningRecord, limit = 12)
  postToMain { onUpdate(runningRecord) }
  ```
  复用 `writeOk=false` 分支已定义的 `definition` 结构，保持一致。

---

## ④⑤⑥⑦（记录待续，①②③ 合入后执行）

- **④ 最小 CI**：新增 `.github/workflows/ci.yml`，跑 `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` + `bash app/src/test/sh/run.sh`。
- **⑤ 治理漂移**：同步 `AGENTS.md` 版本号到 AGP 9.0.1 / Kotlin 2.0.21 / compileSdk·targetSdk 36 / BOM 2024.12.01；补真正的 App 架构文档（当前 `docs/architecture.md` 是 `opencode-architecture.md` 符号链接）。
- **⑥ SafeCommandGuard**：从大小写敏感子串黑名单改为解析命令模型 + 白名单，封死 `eval`/`sh -c` 包裹与双空格绕过。
- **⑦ 大文件拆分**：`BuildBridgeService`(1166) / `UbuntuBootstrapScripts`(834) / `TerminalShellAssets`(641) 内嵌 shell 外置到 `assets/scripts`，构建流水线拆 coordinator + steps。

---

## 执行顺序与验证
1. ①②③ 按 ① → ② → ③ 顺序改。
2. 编译：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:compileDebugKotlin --no-daemon`
3. 单元：`JAVA_HOME=... ./gradlew :app:testDebugUnitTest --no-daemon`（目标：0 失败，原 3 个转绿，无新增）
4. shell：`bash app/src/test/sh/run.sh`（76 passed/0 failed 保持）
5. 构建交付：`assembleDebug` → 复制 `app-debug.apk` 到 `/sdcard/AIDev/`（用户须强制停止+重启 AIDev 部署新脚本）。
6. 提交：①②③ 各自独立 commit（或合并为 1–2 个），④⑤⑥⑦ 单独 commit 待续。
