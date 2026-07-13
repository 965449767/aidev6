# 当前任务：安全审计修复 + 代码质量全面整治

> 🎯 目标：修复项目审计发现的 98 个问题（8 Critical / 24 High / 39 Medium / 27 Low），
> 按阶段逐一修复并验证，每次构建增量验证可编译 + 对应测试通过。
> 审计日期：2026-07-13

---

## 审计概览

| 严重度 | 数量 | 关键领域 |
|--------|------|----------|
| **CRITICAL** | 8 | 命令注入 / SSL 校验缺失 / 过时库 CVE / ProGuard 包名错 / 线程竞争 |
| **HIGH** | 24 | 线程安全 / 内存泄漏 / 资源泄漏 / 生命周期 / 脚本注入 / APK 膨胀 |
| **MEDIUM** | 39 | 架构 / 反射 / 静态分析 / 构建配置 / 测试覆盖 |
| **LOW** | 27 | 配置 / 编码风格 / 日志 |

---

## Phase 1 — 安全漏洞修复（Critical）

> 每个修复后执行 `./gradlew :app:assembleDebug --no-daemon` 验证编译通过。
> 验证：无注入风险、依赖已升级、SSL 校验已启用。

### P1-01 JSch 升级（CVE-2023-48795）
- **问题**: `com.jcraft:jsch:0.1.55` 已停维，存在 Terrapin SSH 攻击漏洞
- **文件**: `app/build.gradle.kts:232`
- **修复**: 替换为 `com.github.mwiede:jsch:0.2.18`（API 兼容，drop-in 替换）
- **验证**: `./gradlew :app:assembleDebug` 编译通过

### P1-02 Ubuntu rootfs 下载加固
- **问题**: `curl -k` 禁用 SSL 校验下载 Ubuntu rootfs；下载后无 SHA-256 校验解压
- **文件**: `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt:388,425-458`
- **修复**: 去掉 `-k`；添加 SHA-256 校验
- **验证**: 单元测试 `./gradlew :app:testDebugUnitTest` 通过

### P1-03 Gradle wrapper JAR 下载加固
- **问题**: `curl -fsSL -k` 下载 Gradle wrapper JAR 时 SSL 校验关闭；`|| true` 静默失败
- **文件**: `app/src/main/assets/scripts/aidev-create-android-project.sh:376,402`；`gradlew:17`
- **修复**: 去掉 `-k`；去掉 `|| true` 改为显式失败处理
- **验证**: Shell 测试 `bash app/src/test/sh/run.sh` 通过

### P1-04 命令注入修复（Shell）
- **问题**: `uninstallapp.sh:12`、`aidev-install.sh:35` 中 `$1`（包名）直接拼入 Shizuku exec 命令
- **文件**: `app/src/main/assets/scripts/uninstallapp.sh:12`；`app/src/main/assets/scripts/aidev-install.sh:35`
- **修复**: 添加包名校验 `^[a-zA-Z0-9._]+$`；单引号转义
- **验证**: Shell 测试通过

### P1-05 命令注入修复（Kotlin）
- **问题**: `DeployBridgeService.kt:171,230` 中 apk/pkg 拼接到 shell 命令字符串；`DialogHost.kt:59-62` 用户脚本直接 exec
- **文件**: `app/src/main/java/com/aidev/six/DeployBridgeService.kt:171,230`；`app/src/main/java/com/aidev/six/navigation/DialogHost.kt:59-62`
- **修复**: 改用数组式 exec（`ProcessBuilder` 传参数组避免 shell 解释）；DialogHost 脚本加白名单校验
- **验证**: 单元测试通过 + 构建通过

### P1-06 脚本其他 SSL/注入修复
- **问题**: `setup-dev-env.sh:137` curl \| sh pipe-to-shell；`aapt2` curl \| bash 同模式
- **文件**: `app/src/main/assets/scripts/setup-dev-env.sh:137`；`app/src/main/assets/scripts/install-aitool.sh:26`
- **修复**: 改为下载到临时文件 → SHA-256 校验 → 执行
- **验证**: Shell 测试通过

---

## Phase 2 — 线程安全与崩溃修复（Critical + High）

> 验证：`./gradlew :app:testDebugUnitTest` 单元测试通过 + 构建通过

### P2-01 BridgeService 线程安全
- **问题**: `scope` 和 `appCtx` 是 `var` 无同步；`scope!!.launch` 在 `stop()` 并发可 NPE
- **文件**: `app/src/main/java/com/aidev/six/BridgeService.kt:15-52`
- **修复**: `scope`/`appCtx` 加 `@Volatile`；捕获局部 `val`；`stop()` 加 CAS 互斥
- **验证**: 编译通过

### P2-02 OpenCodeMonitorService 数据竞争
- **问题**: `sessionStates` 是 `HashMap` 从 IO 线程和主线程并发访问
- **文件**: `app/src/main/java/com/aidev/six/OpenCodeMonitorService.kt:31-358`
- **修复**: 改用 `ConcurrentHashMap`；`isRunning` guard 改用 `AtomicBoolean.compareAndSet`
- **验证**: 编译通过

### P2-03 关键位置 `!!` 替换
- **问题**: `BridgeService.kt:33` `scope!!`；`BuildRequestTracker.kt:158` `stackArr!!`；`ChatPanel.kt:1162-1198` 多处 `!!`
- **文件**: 多个文件
- **修复**: 替换为 `?: return` / `?.let {}` / 安全调用
- **验证**: 编译通过 + 单元测试通过

### P2-04 ShellActivity onDestroy 修复
- **问题**: `onDestroy()` 为空，Terminal 会话/协程/FileObserver 不清理
- **文件**: `app/src/main/java/com/aidev/six/ShellActivity.kt:134-136`；`app/src/main/java/com/aidev/six/EmbeddedShellPages.kt:280`
- **修复**: `onDestroy()` 调用 `terminalPage.onDestroy(this)` 清理资源
- **验证**: 编译通过

### P2-05 资源泄漏修复
- **问题**: `OpenCodeClient.kt:689,249` BufferedReader 未 `close()`/`use {}`
- **文件**: `app/src/main/java/com/aidev/six/chat/OpenCodeClient.kt:249-272,689`
- **修复**: 换用 `use {}` 自动关闭
- **验证**: 编译通过 + 单元测试通过

### P2-06 GestureFeedbackManager 内存泄漏
- **问题**: 构造函数持有 `Activity` 强引用
- **文件**: `app/src/main/java/com/aidev/six/GestureFeedbackManager.kt:16-17`
- **修复**: 换用 `WeakReference<Activity>` 或 `applicationContext`
- **验证**: 编译通过

### P2-07 AgentTaskStore 并发写文件
- **问题**: `upsertTask` 与 `flush` 可能并发写同一文件损坏数据
- **文件**: `app/src/main/java/com/aidev/six/agent/AgentTaskStore.kt:109-158`
- **修复**: 按 key 粒度的锁或 `FileChannel.lock`；同步 `dirty` 标记写
- **验证**: 单元测试通过

---

## Phase 3 — 构建与发布配置修复

> 验证：`./gradlew :app:assembleDebug` + `./gradlew :app:testDebugUnitTest` 通过

### P3-01 ProGuard 包名修正
- **问题**: `proguard-rules.pro` 中所有 `-keep` 引用 `com.aidev.three`，对当前 `com.aidev.six` 无效
- **文件**: `app/proguard-rules.pro:2-8`
- **修复**: 替换为 `com.aidev.six.*`
- **验证**: `cat app/proguard-rules.pro` 确认包名正确

### P3-02 Release 签名配置 + 开启 minification
- **问题**: Release 无 `signingConfig`；`isMinifyEnabled = false`
- **文件**: `app/build.gradle.kts:120-133`
- **修复**: 添加 release signingConfig；开启 `isMinifyEnabled = true` 和 `isShrinkResources = true`
- **验证**: `./gradlew :app:assembleRelease` 产出已签名的 APK

### P3-03 添加 JSch/CommonMark ProGuard rules
- **问题**: ProGuard 无 JSch/CommonMark 的 keep rules，开启混淆后运行时崩溃
- **文件**: `app/proguard-rules.pro`
- **修复**: 添加 `-keep class com.jcraft.jsch.**` 和 `-keep class org.commonmark.**`
- **验证**: 确认文件包含对应 keep 规则

### P3-04 Debug keystore 从 git 移除
- **问题**: `keystore/debug.keystore` 和 `app/keystore/debug.keystore` 被 git 跟踪
- **修复**: `git rm --cached` 移除跟踪；添加 `keystore/` 和 `app/keystore/` 到 `.gitignore`
- **验证**: `git status` 确认不再跟踪

### P3-05 残留文件清理
- **问题**: `.pre-keyboard` 备份文件在 git 中
- **文件**: `EmbeddedShellPages.kt.pre-keyboard`；`TerminalPanel.kt.pre-keyboard`
- **修复**: `git rm` 删除；添加 `*.pre-keyboard` 到 `.gitignore`
- **验证**: `git status` 确认

### P3-06 keystore.properties.example 弱密码
- **问题**: 示例密码 `aidev123` 可能被复制到 production
- **文件**: `keystore.properties.example:4-7`
- **修复**: 改为 `CHANGE_ME` 占位符
- **验证**: 文件内容确认

---

## Phase 4 — Shell 脚本加固

> 验证：`bash app/src/test/sh/run.sh` 全部 47 个测试通过

### P4-01 变量引用加固
- **问题**: `aidev-shizuku.sh:42` APK 路径未转义单引号；`aidev-tombstone.sh:17` `$1` 未引号
- **文件**: 多个 shell 文件
- **修复**: 全部变量 `"$var"` 引用；单引号转义
- **验证**: Shell 测试通过

### P4-02 aidev-deploy.sh 出口码修复
- **问题**: 安装/验证失败时 `exit 0` 返回 JSON 但出口码 0 误判成功
- **文件**: `app/src/main/assets/scripts/aidev-deploy.sh:84,92,115`
- **修复**: 失败时 `exit 1`
- **验证**: 模拟失败场景出口码≠0

### P4-03 aidev-build.sh 构建报告 JSON 正确转义
- **问题**: JSON 字符串拼接未转义控制字符
- **文件**: `app/src/main/assets/scripts/aidev-build.sh:216-242`
- **修复**: 使用环境中的 `jq` 或完善转义
- **验证**: 含特殊字符的日志生成有效 JSON

### P4-04 `set +e` 修复 + `#!/bin/sh` 兼容性
- **问题**: `setup-dev-env.sh` 全局 `set +e`；`seq`/`[[ ]]` 在 sh 下不兼容
- **文件**: 多个 shell 文件
- **修复**: 仅在预期失败命令周围 `set +e`；`seq` 换 POSIX while 循环
- **验证**: Shell 测试通过

### P4-05 临时文件可预测 + 清理
- **问题**: `aidev-install.sh:68` 固定临时文件名；`aidev-index.sh` 无 trap 清理
- **文件**: 多个 shell 文件
- **修复**: 改为 `mktemp`；添加 `trap 'rm -rf ...' EXIT`
- **验证**: 并发安装不冲突；中断后临时文件清理

---

## Phase 5 — 代码质量与架构

> 验证：`./gradlew :app:testDebugUnitTest --no-daemon` 全部 78+ 测试通过

### P5-01 `Thread.sleep` 改协程
- **问题**: `BuildRequestTracker.kt:85-109` `Thread.sleep(800)` 忙等；`DeployRequestTracker.kt:49-52` 同样
- **文件**: `app/src/main/java/com/aidev/six/agent/BuildRequestTracker.kt:85-109`；`app/src/main/java/com/aidev/six/agent/DeployRequestTracker.kt:49-52`
- **修复**: 改为协程 `delay()`
- **验证**: 单元测试通过

### P5-02 空 catch block 加日志
- **问题**: `SftpService.kt:39`、`SessionManager.kt:393,402`、`TerminalClientImpl.kt:80` 多处 `catch (_: Exception) {}`
- **文件**: 多个文件
- **修复**: 加 `AIDevLogger.w()` 日志
- **验证**: 编译通过

### P5-03 FileProvider 路径收窄
- **问题**: `file_paths.xml` 中 `<external-path name="sdcard" path="." />` 暴露整个 SD 卡
- **文件**: `app/src/main/res/xml/file_paths.xml:4`
- **修复**: 限制到 `path="AIDev/"`
- **验证**: 编译通过

### P5-04 SFTP 连接凭据加密
- **问题**: SSH 连接信息存放在 SharedPreferences 明文
- **文件**: `app/src/main/java/com/aidev/six/Constants.kt:52` 等
- **修复**: 改用 `EncryptedSharedPreferences`
- **验证**: 编译通过 + 连接可用

### P5-05 OpenCode HTTP API 加简单 token
- **问题**: `http://127.0.0.1:4096` 无认证，同设备任意 App 可访问
- **文件**: `app/src/main/java/com/aidev/six/chat/OpenCodeClient.kt:25-33`
- **修复**: 启动时生成随机 token，请求头 `Authorization: Bearer <token>`
- **验证**: 编译通过

### P5-06 通知 ID 碰撞修复
- **问题**: `AIDevCommandDispatcher.kt:80` 通知 ID 用 `System.currentTimeMillis()` 碰撞
- **修复**: 改用 `AtomicInteger` 单调递增
- **验证**: 编译通过

---

## Phase 6 — 测试补齐

> 验证：`./gradlew :app:testDebugUnitTest --no-daemon` 新增 + 存量全部通过

### P6-01 OpenCodeClient 单元测试
- 添加 `parseMessage` / `parseEvent` 测试覆盖 SSE 协议解析
- 覆盖：正常事件流、不完整 chunk、错误字段、断线重连

### P6-02 BridgeService 单元测试
- 添加 `BuildBridgeService.handleRequest` 日志解析测试
- 添加 `DeployBridgeService` JSON 请求响应测试
- Mock 文件系统操作

### P6-03 SessionManager 单元测试
- 添加 Tab 生命周期管理测试
- PWD 跟踪测试

### P6-04 ShellActivity 回归测试
- 添加 Activity 创建/销毁生命周期测试
- 验证 `onDestroy` 后资源已释放

---

## 执行顺序

```
Phase 1 (安全 Critical) → 构建验证
    ↓
Phase 2 (线程安全/崩溃) → 单元测试 + 构建验证
    ↓
Phase 3 (构建/发布配置) → 构建验证
    ↓
Phase 4 (Shell 脚本) → Shell 测试验证
    ↓
Phase 5 (代码质量) → 单元测试 + 构建验证
    ↓
Phase 6 (测试补齐) → 全部测试通过
```

## Phase 7 — 部署可靠性 + 终端构建请求返回值（实测修复）

> 实测「服务器中心」安装/拉起与终端 `aidev-build-request` 时发现的真实缺陷，不属原 98 项审计，
> 但直接影响交付可用性，已修复并在 #135/#137/#139 构建验证。

### P7-01 DeployBridgeService 部署脚本 PRoot 路径错误（#135）
- **问题**: `deployScript` 用宿主绝对路径 `files/home/dev-env/bin/aidev-deploy`，但部署在 PRoot 内执行，
  仅 `/host-home` 被绑定，宿主路径在 PRoot 视图不可见 → 脚本"文件不存在"，安装/拉起必失败。
- **文件**: `app/src/main/java/com/aidev/six/DeployBridgeService.kt:193`
- **修复**: `deployScript = "/host-home/dev-env/bin/aidev-deploy"`（与 `AIDEV_HOME=/host-home` 一致）。

### P7-02 aidev-deploy 误报失败 + 等待过久（#137）
- **问题**: `pm list packages` 二次校验为**致命**步骤，Shizuku 单次查询偶发空结果即 `exit 1` 报失败，
  但 App 实际已装好；且重试/sleep 过多导致等待久。
- **文件**: `app/src/main/assets/scripts/aidev-deploy.sh`
- **修复**:
  - 校验改为**非致命**（仅提示），最终判定以"能否启动"为准（启动成功即证明已安装）。
  - 安装重试 3→2、间隔 2s→1s；校验重试 3→2、间隔 2s→1s；启动重试间隔 2s→1s。

### P7-03 ensureDeployScripts 不会刷新变更的脚本（#137）
- **问题**: 脚本与 `.md5` 都在时直接跳过，导致 bundled 脚本修复后 `.md5` 过期 → `validateDeployScript`
  MD5 不匹配误拦部署。
- **文件**: `app/src/main/java/com/aidev/six/DeployBridgeService.kt:83`(`ensureDeployScripts`)
- **修复**: 以 bundled assets 为准，MD5 不一致则覆盖脚本并刷新 `.md5`，保证修复生效且校验永不错拦。

### P7-04 aidev-shizuku 请求 ID 碰撞（#137）
- **文件**: `app/src/main/assets/scripts/aidev-shizuku.sh`
- **修复**: `REQ_ID` 加 `$RANDOM`，避免同 PID 同秒请求复用 result 文件被误读。

### P7-05 aidev-build-request 无返回值（#139）
- **问题**: 仅 fire-and-forget 提交请求，不等待/不返回构建结果。
- **文件**: `app/src/main/assets/scripts/aidev-build-request.sh`、`TerminalShellAssets.kt:274`
- **修复**:
  - 脚本阻塞等待 `result-$ID.json`（≤900s）：成功打印 `构建成功`+APK（exit 0）；
    失败打印消息并 **cat 完整构建日志** `/sdcard/AIDev/logs/<project>/build.log`（exit 1）；超时 exit 2。
  - 终端函数改用 `${AIDEV_ROOTFS}/usr/local/bin/aidev-build-request`（同 aidev-shizuku），
    由 `copyAssetScripts` 每次终端会话刷新，确保新脚本生效。

## 冻结说明

当前任务推进期间，以下内容冻结：
- 新功能开发
- 非安全/非崩溃性的 UI 优化
- 依赖升级（JSch 升级除外）
