# aidev6 实机启动 PRoot 失败 · 完整诊断文档

> 导出时间：2026-07-11
> 目的：把当前问题、已尝试的修复、代码上下文、未决假设整理清楚，供其他 AI / 人工排查。
> 设备：Xiaomi 14 Pro / HyperOS / **Android 16（API 36）** / arm64-v8a
> 目标 App：`com.aidev.six.dev`（aidev6，本仓库 `/root/projects/aidev6`）
> 构建：AGP 9.0.1 / Gradle 9.1.0 / Kotlin 2.0.21 / Java 17

---

## 0. TL;DR（给接手者的速读）

aidev6 在手机上启动 PRoot（进终端 `ubuntu` 命令）时，最后一步 exec proot 二进制报
`Permission denied`，**无论文件是否有 +x 位都报同样的错**。

当前 proot 二进制位于 `home/proot-lib/libproot.so`（app 私有数据区 `/data/data/com.aidev.six.dev/files/home/proot-lib/`）。
该目录所在挂载点**禁止直接执行二进制（noexec）**：shell 脚本能运行（走 `/system/bin/sh` 解释器），但 proot 这种被直接 exec 的 ELF 被内核拒绝。

根本未决问题：**proot 二进制必须放在一个「app 可写 + 挂载点允许 exec」的位置**，目前没找对。

---

## 1. 报错现场（用户实机复制）

```
$ ubuntu
已进入宇宙 A（OpenCode）
[1/4] 自动下载 Ubuntu Base 24.04 arm64...
...
[4/4] 初始化 apt 源、DNS 和完成标记...
Ubuntu 初始化完成。
/data/user/0/com.aidev.six.dev/files/home/dev-env/bin/aidev-ubuntu-core: \
  /data/user/0/com.aidev.six.dev/files/home/proot-lib/libproot.so: Permission denied
```

- rootfs（ubuntu-base 24.04）已成功下载解包（`home/ubuntu-rootfs/.aidev-rootfs-ready` 已生成）。
- 失败发生在「用 proot 进入 rootfs」这一步，即宿主侧 exec `libproot.so` 时被拒。
- 已多次重装（versionCode 14→15→16），每次都在 exec 这一步失败，报错完全一致。

---

## 2. 架构与路径（代码写实，非猜测）

相关代码：
- `app/src/main/java/com/aidev/six/PathConfig.kt`
  - `aidevHome(ctx)` = `ctx.filesDir/home` → `/data/data/com.aidev.six.dev/files/home`
  - `rootfs` = `home/ubuntu-rootfs`（终端/编译环境）
  - `workspaceDir` = `home/workspace`（共享工作区）
  - `prootLibDir(ctx)` = `home/proot-lib`  ← **当前 proot 二进制所在**
- `app/src/main/java/com/aidev/six/terminal/ProotLauncher.kt`
  - `buildCommand()`：`proot = File(prootLibDir, "libproot.so")` 拼成 proot 命令
  - `setupEnv()`：`PROOT_LOADER = File(prootLibDir,"libproot_loader.so")`；`LD_LIBRARY_PATH = "$prootLibDir:$nativeDir"`
- `app/src/main/java/com/aidev/six/terminal/SessionManager.kt`
  - 终端会话 env：`AIDEV_PROOT=$prootLibDir/libproot.so`、`PROOT_LOADER=$prootLibDir/libproot_loader.so`
- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`
  - `aidevUbuntuCommandScript()` 生成 `aidev-ubuntu-core` 引导脚本，设置
    `AIDEV_PROOT_LIBS=$AIDEV_HOME/proot-lib`、`AIDEV_PROOT=$AIDEV_PROOT_LIBS/libproot.so`，并在 exec 前
    `[ -f "$AIDEV_PROOT" ] && chmod 755 "$AIDEV_PROOT"` 兜底（**已加，但仍失败**）
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`
  - `installProotSupportLibraries()`：终端初始化（按 versionCode 门控）把
    `assets/proot-libs/arm64-v8a/` 下的 `libproot.so`/`libproot_loader.so`/`libproot_loader32.so`
    + 支撑库 `libtalloc.so.2`/`libandroid-shmem.so` 拷到 `home/proot-lib`，并对 proot 系列 `setExecutable(true)`。

proot 二进制来源（两处都有）：
- `app/src/main/jniLibs/arm64-v8a/libproot.so`（原生库，指望系统解包到 `nativeLibraryDir`）
- `app/src/main/assets/proot-libs/arm64-v8a/libproot.so`（新增，运行时拷到 proot-lib）

---

## 3. 已经尝试过、且无效的修复

1. **原设计**：proot 仅在 `jniLibs`，靠 `android:extractNativeLibs="true"` 让系统解包到
   `nativeLibraryDir`（`/data/app/.../lib/arm64/`）后直接 exec。
   → 实测 `nativeLibraryDir/libproot.so` **根本不存在**（系统未解包），首次报 `proot 不存在`。
   原因推测：Android 16 / AGP 9 下 manifest 的 `extractNativeLibs` 已被忽略（error-journal 也记了
   `AndroidManifest.xml:32 — android:extractNativeLibs should move to build config per AGP 9`），
   原生库默认留在 APK 内、不解包。
2. **第一次修**：把 proot 系列二进制作为 assets，运行时拷到 `home/proot-lib`（与已能工作的
   `libtalloc` 同机制），所有引用改用 `proot-lib`。
   → proot 库确实到位了（报错路径变成 `proot-lib/libproot.so`），但 exec 报 `Permission denied`。
3. **第二次修**：在引导脚本 exec 前加 `chmod 755 $AIDEV_PROOT`（Kotlin `setExecutable` 在 Android
   可能静默失效）。
   → **仍报完全相同的 `Permission denied`**。

> 结论：加了 +x 位依然被拒 ⇒ 不是文件权限位问题，是**挂载点 noexec**（见第 4 节）。

---

## 4. 根因分析（当前最强假设）

### 假设 A（最可能）：`/data/data/com.aidev.six.dev/files/home` 是 noexec 挂载
- 证据 1：shell 脚本（`aidev-ubuntu-core`，位于 `files/home/dev-env/bin/`）能正常运行 → 脚本走
  `/system/bin/sh` 解释器，不触发 noexec 对「二进制直接 exec」的限制。
- 证据 2：chmod 755 后依旧 `Permission denied` ⇒ 不是缺 +x 位，而是内核在 noexec 挂载点上拒绝
  exec（EACCES）。
- 证据 3：同机的 `com.aidev.five.dev` 能跑 proot（其 logcat 显示 proot 执行 `git`/`bash`，
  SELinux AVC 对 `app_data_file` 的 `execute` 为 **granted**）。注意：five.dev 跑的是 **rootfs 内部**
  经 proot 命名空间的二进制（也都在 `files/home/...` 下，SELinux 允许），而 proot **本体**要能在宿主侧
  exec——five.dev 的 proot 本体大概率放在一个「exec 允许」的位置（见假设 B），不是裸 `files/home/proot-lib`。

### 假设 B：proot 本体必须放在 `nativeLibraryDir` 才允许 exec
- `nativeLibraryDir` = `/data/app/<pkg>-<rand>/lib/arm64/`，由系统管理、SELinux/挂载允许 exec
  （系统自己从 APK 加载 JNI 库就走这里）。
- 问题是该目录**只读、app 不可写**，运行时无法拷贝；只能靠「系统解包」放进去，而解包在当前 AGP9/Android16
  下没发生（见第 3 节原设计失败）。

### 需排除的点
- 是否真 noexec（而非 SELinux type 差异）：five.dev 的 AVC `granted` 已强烈暗示 SELinux 不是主因，
  但可再用 `dmesg`/`logcat` 抓这次 six.dev exec 失败的 AVC 确认（见第 6 节验证手段）。

---

## 5. 待定的正确修复方向（需要接手者拍板/实现）

方向 1 — **让 proot 落到 nativeLibraryDir（exec 允许区）且可被 app 放到**
- 解决「系统不解包」：在 AGP 9 下正确强制原生库解包到 nativeLibraryDir。
  已知 `android.packaging.jniLibs.useLegacyPackaging = true` 已设置，但仍未解包 ⇒
  可能还需保证 .so 在 APK 内 **未压缩且 page-aligned**（否则安装器静默跳过解包）。
  需查：AGP 9 下让应用原生库解包到 nativeLibraryDir 的确切 DSL / 打包配置。
- 风险：nativeLibraryDir 只读，runtime 拷贝不可行，完全依赖构建期解包。

方向 2 — **运行时把 proot 拷到「app 可写且 exec 允许」的目录**
- 候选：`/data/local/tmp`（通常 1777、可执行、app 可写？需验证）；或 app 私有 cache 中某个非 noexec 子区。
- 风险：跨设备/跨 ROM 行为不一致；`/data/local/tmp` 受 SELinux `shell_data_file` 等限制。

方向 3 — **不 exec proot 二进制，改用其他加载方式**
- 例如通过 `dlopen`/包装器、或 memfd + `fexecve` 从 fd 执行（绕开 noexec 文件路径限制）。
- 复杂度高，需改 proot 调用方式。

方向 4 — **参考 five.dev 的实现**
- 同机 `com.aidev.five.dev` 能跑 proot，它的 proot 本体放在哪、怎么被 exec 的，是最有价值的参照。
  建议直接看 five.dev 的对应代码/APK 结构（它和 six.dev 同源，机制应可照搬）。

---

## 6. 建议的验证 / 排查命令（在手机 aidev6 终端或 adb 里跑）

```sh
# 看 proot-lib 文件权限（确认 +x 是否真的加上了）
ls -l $AIDEV_HOME/proot-lib/libproot.so

# 确认 six.dev exec 失败时内核/SELinux 的拒绝记录
logcat -b all | grep -iE "proot|avc|denied|SELinux" | tail -50
# 或直接 dmesg | grep -i denied

# 看 files/home 挂载选项（有没有 noexec）
mount | grep -i "com.aidev.six.dev" 
cat /proc/mounts | grep -i "com.aidev.six.dev"

# 对比：five.dev 的 proot 在哪、其目录挂载选项
pm path com.aidev.five.dev
# 再 mount | grep five.dev 对比

# 测 /data/local/tmp 是否可执行（写个小二进制试 exec）
```

---

## 7. 当前代码已处的状态（避免接手者重复改动）

- `libproot.so`/`libproot_loader.so`/`libproot_loader32.so` 已同时存在于 `jniLibs/arm64-v8a/` 与
  `assets/proot-libs/arm64-v8a/`。
- 所有 proot 路径引用（ProotLauncher / SessionManager / UbuntuBootstrapScripts / TerminalShellAssets rc）
  目前指向 `home/proot-lib`（**这是 noexec 区，正是问题所在**）。
- 若接手者采用「方向 1/2」，应把这些引用改到正确的 exec 允许位置，并相应调整
  `installProotSupportLibraries`（拷贝目标目录）。
- 已安装版本：versionCode=16（最新构建产物 `app/build/outputs/apk/debug/app-debug.apk`）。

## 8. 同机关联包（设备上存在，可作参照）
`com.aidev.six.dev`（目标，坏）、`com.aidev.five.dev`（能跑 proot，参照）、
`com.aidev.terminal` / `com.aidev.terminal.dev` / `com.aidev.opencode` / `com.aidev.four.dev`。
