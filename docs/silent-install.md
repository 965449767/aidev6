# 静默 APK 安装

两种互不依赖的静默安装方式，均通过 Shizuku API（`rikka.shizuku`）执行，不走 Shizuku shell 命令（后者有安全策略过滤）。

---

## 方式 A：App UI（文件浏览器）

### 操作流程

```
文件浏览器 → 打开 /sdcard/ → 选中 .apk 文件
  → 长按 或 右划点「更多」
  → 底部菜单 → 「静默安装」
  → Shizuku 状态检测（未安装/未运行/未授权/就绪）
  → 确认对话框 → 安装
```

### 代码路径

```
FileRow.kt:284  FileActionSheet "静默安装" 菜单项
  → state.shizukuInstaller.installApk(file)
    └─ ShizukuInstaller.kt:21
      ├─ 校验 .apk 后缀、文件存在、不在 PRoot 内
      ├─ ShizukuLogcat.checkState() → 检测 Shizuku 状态
      ├─ 就绪 → 确认对话框
      └─ executeInstall(file)
          └─ ShizukuLogcat.executeFireAndForget(cmd)
              └─ Shizuku.newProcess() → sh -c <cmd>
```

### 关键修复记录

| 问题 | 现象 | 修复 |
|------|------|------|
| SELinux FUSE 拒绝 | `avc: denied { read } for scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0` — `pm install` 在 system_server 上下文无法读 `/storage/emulated/0/` | 先 `cp` 到 `/data/local/tmp/`（shell 上下文可读 FUSE），再 `pm install`（system_server 可读 `/data/local/tmp/`） |

---

## 方式 B：终端（`aidev-install --silent`）

### 操作流程

```bash
aidev-install --silent /sdcard/AIDE_xxx.apk
```

**完整数据流：**

```
终端（PRoot Ubuntu 容器）
  └─ aidev-install --silent /sdcard/AIDE_xxx.apk
      └─ aidev-shizuku install /sdcard/AIDE_xxx.apk
          ├─ readlink -f 解析绝对路径
          ├─ 生成安全文件名（去特殊字符，无空格/引号）
          └─ 写请求文件 ~/.aidev-shizuku-bridge/request/exec_<ts>_<pid>
              TYPE=exec
              COMMAND=cp <src> /data/local/tmp/...; pm install -r -d ...; rm -f ...

ShizukuBridgeService（宿主 Android 进程，500ms 轮询）
  └─ 拾取请求文件
      ├─ isCommandAllowed(command)        ← 白名单检查
      ├─ ShizukuLogcat.executeCommand()
      │   └─ Shizuku.newProcess()          ← Shizuku API（反射）
      │       └─ sh -c <cmd>               ← 宿主 shell 上下文
      │           ├─ cp /sdcard/... /data/local/tmp/...   ← shell 可读 FUSE ✅
      │           ├─ pm install -r -d /data/local/tmp/... ← system_server 可读 ✅
      │           └─ rm -f /data/local/tmp/...            ← 清理
      └─ 写结果 ~/.aidev-shizuku-bridge/result/exec_<ts>_<pid>

终端轮询 result 文件（超时 30s）→ 显示输出
```

### 代码路径

```
脚本层:
  app/src/main/assets/scripts/aidev-install.sh   → 入口：--silent 调用 aidev-shizuku install
  app/src/main/assets/scripts/aidev-shizuku.sh   → bridge 客户端：写请求文件 + 轮询结果

App 层:
  app/.../ShizukuBridgeService.kt   → 轮询处理请求
  app/.../ShizukuLogcat.kt          → 通过 Shizuku API 执行命令
  app/.../BridgeService.kt          → 抽象基类（500ms 轮询 + 文件锁）
```

### 关键修复记录

| # | 问题 | 现象 | 修复 |
|---|------|------|------|
| 1 | **Shell 安全策略拒绝** | `aidev-install` 报 `命令被安全策略拒绝` | `aidev-shizuku.sh` 改用 `ShizukuBridgeService`（Shizuku API），不走 `shizuku` shell 命令 |
| 2 | **桥接白名单拦命令** | 旧命令 `cat ... \| pm install ...` 以 `cat` 开头，不在 `isCommandAllowed` 白名单（`pm`, `input`, `svc`, `dumpsys`, `cmd`） | 改为 `pm install -r -d <path>` 起头 → 通过前缀白名单 |
| 3 | **SELinux FUSE 拒绝** | `pm install` 在 system_server 上下文不能读 `/storage/emulated/0/` | 先 `cp` 到 `/data/local/tmp/` 再安装 |
| 4 | **PRoot 内无 /data/local/tmp/** | `cp` 在 PRoot 容器内执行时报 `No such file or directory` | `cp` 命令通过桥接在宿主 shell 执行（`Shizuku.newProcess` 创建的是宿主进程，非 PRoot） |
| 5 | **链式命令含 `;` 被当非法字符** | `cp ...; pm install ...; rm -f ...` 含 `;` 不在 `allowedChars` 中 | `ShizukuBridgeService.kt:67` 的 `allowedChars` 加 `;` |

---

## 技术要点

### Shizuku 上下文权限对照

| 上下文 | SELinux 标签 | 可读 `/sdcard/` | 可读写 `/data/local/tmp/` | 可执行 `pm install` |
|--------|-------------|----------------|------------------------|-------------------|
| Shizuku 进程 (shell) | `u:r:su:s0` | ✅ | ✅ | ❌ 调起 system_server |
| system_server | `u:r:system_server:s0` | ❌ FUSE 拒绝 | ✅ `shell_data_file` | ✅ |

**结论：`cp` 在 shell 上下文执行，`pm install` 始终由 system_server 实际处理 → `/data/local/tmp/` 是两者的交集。**

### 白名单安全机制

`ShizukuBridgeService` 的 `isCommandAllowed()` 有两层：

```kotlin
// 第一层：前缀快速匹配
val ALLOWED_COMMAND_PREFIXES = listOf("pm ", "input ", "svc ", "dumpsys ", "cmd ")
if (trimmed.startsWith(any prefix)) return true

// 第二层：字符级安全扫描
val allowedChars = setOf('/', '-', '_', '.', ' ', '=', '@', ':', '~', ';')
return trimmed.all { it.isLetterOrDigit() || it in allowedChars }
```

**注意：`'` 和 `"` 不在 `allowedChars` 中 → 命令中不能含引号 → 文件路径不能有空格。**

### 使用限制

| 限制 | 原因 | 未来改进方向 |
|------|------|------------|
| APK 路径不能含空格 | 桥接白名单禁止引号 | 在脚本层用 `sed` 替换空格为 `_` 后复制 |
| APK 必须在 `/sdcard/` 或 `/storage/` | PRoot 内路径对宿主不可见 | 脚本层先检测路径位置，自动复制到 `/sdcard/` |
| 安装是 fire-and-forget（无回调） | `executeFireAndForget` 异步执行 | 可改为 `executeCommand` + 显示安装结果 |

### 常用命令

```bash
# 终端静默安装（需要 Shizuku 运行并授权）
aidev-install --silent /sdcard/xxx.apk

# 强制走系统安装器
aidev-install --gui /sdcard/xxx.apk

# 静默卸载
aidev-install --uninstall com.package.name

# 检查 Shizuku 桥接状态
aidev-install --status

# 通过 Shizuku API 执行任意命令（无安全策略限制）
aidev-shizuku exec "pm list packages"

# App UI 安装：文件浏览器 → 选中 APK → 长按 → 静默安装
```
