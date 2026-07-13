# Error Journal

Use this file to record repeated failures, non-obvious bugs, and lessons learned.

## 2026-06-14 - Shell functions lost after `exec sh -i`

### Symptom

`ubuntu` returned `inaccessible or not found`.

### Root Cause

The shell loaded functions before `exec sh -i`. The exec replaced the process and lost previously sourced functions.

### Fix

Set `ENV` to `.aidevrc` so the interactive shell loads the functions itself.

### Prevention

Do not source shell functions before replacing the shell process.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Permission denied for app-private scripts

### Symptom

`ubuntu` existed in `dev-env/bin` but direct execution returned `Permission denied`.

### Root Cause

Android app private directories do not allow direct script execution.

### Fix

Define shell functions that call scripts through `/system/bin/sh`.

### Prevention

Never depend on executable bits for scripts under app private storage.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Ubuntu Base hardlink extraction failure

### Symptom

`tar` failed while linking files such as `usr/bin/perl5.38.2` and `usr/bin/perl`.

### Root Cause

Android `tar` and app private storage do not reliably support hardlink creation.

### Fix

Allow extraction to continue when core rootfs files exist, then replace known hardlinks with symlinks.

### Prevention

Preserve PRoot `--link2symlink` and rootfs symlink fallback logic.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Multiple terminal entries caused inconsistent behavior

### Symptom

Different pages could enter different terminal implementations.

### Root Cause

The project retained both old `MainActivity` terminal navigation and the newer embedded terminal.

### Fix

Route terminal navigation to `ShellActivity` and unregister `MainActivity` from the manifest.

### Prevention

Use `AppNav.openTerminal` or `ShellHost.openTerminal` for terminal navigation.

### Related Files

- `app/src/main/java/com/aidev/three/AppNav.kt`
- `app/src/main/AndroidManifest.xml`

## 2026-06-17 - Corrupted file content caused conflicting imports

### Symptom

Compilation failed with hundreds of `Conflicting import` and `imports are only allowed in the beginning of file` errors, with absurd line numbers (e.g., 13001, 3543).

### Root Cause

`BackupRestorePage.kt` and the previous `MenuBottomSheet.kt` had their content repeatedly appended, creating malformed files with multiple `package` and `import` blocks. This likely happened when a prior write operation appended instead of overwriting.

### Fix

Deleted the corrupted `BackupRestorePage.kt` (it was unreferenced and untracked in Git). Overwrote `MenuBottomSheet.kt` with clean content.

### Prevention

Always verify file contents after write operations, especially when reusing file paths from previous sessions. Check file size and first few lines if compilation errors mention impossible line numbers.

### Related Files

- `app/src/main/java/com/aidev/three/BackupRestorePage.kt` (deleted)
- `app/src/main/java/com/aidev/three/MenuBottomSheet.kt`

## 2026-07-10 — `/system/bin/` binaries fail inside PRoot with "required file not found"

### Symptom

Commands like `ls`, `mkdir`, `tr`, `sed`, `grep` return `/system/bin/ls: cannot execute: required file not found` inside the PRoot environment.

### Root Cause

PRoot mounts the host `/system` partition into the glibc-based Ubuntu rootfs. Android's bionic-linked binaries at `/system/bin/*` cannot run under glibc. When `PATH` includes `/system/bin/`, the shell finds these incompatible binaries before the rootfs's own `coreutils` versions.

### Fix

Two changes:
1. In `.bashrc` (inside rootfs), strip `/system/*` entries from `PATH` on boot using a pure-bash while-read loop.
2. Install `coreutils` in the rootfs during bootstrap if `/usr/bin/ls` is missing.

### Prevention

Always sanitize `PATH` when entering PRoot. Use pure-bash string manipulation (`while IFS= read`, `case`, parameter expansion) instead of `sed`/`tr`/`grep` in `.bashrc`, because those tools may be from `/system/bin/` and fail.

### Pure-bash PATH cleanup pattern

```bash
_p="$PATH"; PATH=""
while [ -n "$_p" ]; do _e="${_p%%:*}"; case "$_e" in /system/*) ;; *) PATH="${PATH:+$PATH:}$_e" ;; esac; [ "$_p" = "$_e" ] && _p="" || _p="${_p#*:}"; done
unset _p _e
```

### Function override pattern (`/system/bin/sh` → `/bin/sh`)

```bash
__f() { while IFS= read __l; do case "$__l" in */system/bin/sh*) echo "${__l%%/system/bin/sh*}/bin/sh${__l#*/system/bin/sh}";; *) echo "$__l";; esac; done; }
eval "$(declare -f | __f)"
unset -f __f
```

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`

## 2026-07-10 — Duplicate auto-bootstrap triggers cause terminal noise inside PRoot

### Symptom

On first app launch, terminal shows garbled overlapping text, error `cannot open aidev-ubuntu-core: No such file`, and automatic `compiler` command execution after exiting PRoot.

### Root Cause

Three independent paths triggered `aidev-auto-bootstrap` at startup:
1. Shell entry script (`.aidev_shell_entry`) — correct, runs before interactive shell
2. `maybeAutoBootstrapUbuntu()` in `SessionManager.kt` — directly writes to terminal via `currentTerminalSession?.write()`
3. `TerminalCommandBus.post("aidev-auto-bootstrap")` in `ShellActivity.kt` — consumed by `consumePendingCommand()` which also writes to terminal

When path 1 already entered PRoot, paths 2 and 3 wrote raw commands into the PRoot shell. Inside PRoot, `$AIDEV_BIN` pointed to the host path (not `/host-home/`), causing "No such file".

### Fix

Removed `maybeAutoBootstrapUbuntu()` (entire function), its three call sites in `EmbeddedShellPages.kt`, the `autoBootstrapDispatched` flag, and `TerminalCommandBus.post("aidev-auto-bootstrap")` in `ShellActivity.kt`. Shell entry script is now the sole auto-bootstrap path.

### Prevention

- Only one mechanism should manage startup auto-enter.
- `write()` to the terminal session is dangerous — it bypasses the shell entry script and can write into a running PRoot process.
- Shell entry script runs inside the session process and has access to correct paths.

### Race condition analysis (no fix needed)

`TerminalShellAssets.ensure()` runs on `Dispatchers.IO` inside `ensureSession()`. `doCreateSession()` runs **after** `ensure()` completes (on `withContext(Dispatchers.Main)`). So `aidev-ubuntu-core` always exists when the shell entry script checks for it. No actual race exists.

### Related Files

- `app/src/main/java/com/aidev/six/ShellActivity.kt`
- `app/src/main/java/com/aidev/six/EmbeddedShellPages.kt`
- `app/src/main/java/com/aidev/six/terminal/SessionManager.kt`
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`
- `app/src/main/java/com/aidev/six/TerminalCommandBus.kt`

## 2026-07-10 — Kotlin `${'$'}` escaping for shell code in triple-quoted strings

### Symptom

Kotlin compiler errors like `Unresolved reference '__l'` on lines containing shell variable references like `$__l`.

### Root Cause

In Kotlin `"""..."""` strings, `$` followed by an identifier or `${}` starts a template expression. Shell code like `"$__l"` causes Kotlin to look for a Kotlin variable named `__l`.

### Fix

Replace each `$` that must appear literally in the output with `${'$'}`. Examples:
- `$__l` → `${'$'}__l`
- `${__l%%pattern*}` → `${'$'}{__l%%pattern*}`
- `$PATH` → `${'$'}PATH`

### Prevention

When writing shell code inside Kotlin `"""..."""` strings, always use `${'$'}` for every `$` sign. This applies to both simple variables (`$var`) and complex expressions (`${var:-default}`).

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`

## Deferred — `LinearProgressIndicator(float)` deprecated

### Symptom

Compile warning at `BackupRestoreDialog.kt:90` and `SFtpPanel.kt:105`:
`'fun LinearProgressIndicator(progress: Float, ...)' is deprecated. Use the overload that takes progress as a lambda.`

### Fix

`SFtpPanel.kt:105` fixed during Phase 3 SFTP work. `BackupRestoreDialog.kt:90` still uses the float overload.

### Deferred

Not a blocker — warning only. Fix when touching the file next.

## Deferred — Compose UI tests require Android runtime

### Symptom

`createComposeRule()` from `androidx.compose.ui:ui-test-junit4` throws `NullPointerException` in JVM unit tests (needs `ComposeUiTest` Android runtime). Four new test files (`SftpConnectionTest`, `TransferTaskTest`, `AuthTypeTest`, `DialogTypeTest`) had to fall back to pure JUnit data class tests.

### Root Cause

Compose UI tests need either:
- `androidTest` source set (device/emulator instrumentation tests), or
- Robolectric for JVM unit tests (not yet configured)

### Related Files

- `app/src/test/java/com/aidev/three/SftpConnectionTest.kt`
- `app/src/test/java/com/aidev/three/TransferTaskTest.kt`
- `app/src/test/java/com/aidev/three/AuthTypeTest.kt`
- `app/src/test/java/com/aidev/three/navigation/DialogTypeTest.kt`

## Deferred — Minor warnings (no functional impact)

1. **`GitStateTest.kt:27,33`** — `@OptIn(ExperimentalCoroutinesApi::class)` missing
2. **`DialogTypeTest.kt:46`** — `is DialogType` check always true (warning only)
3. **`AndroidManifest.xml:32`** — `android:extractNativeLibs` should move to build config per AGP 9

## 2026-07-11 — PRoot 库在 Android 16 上未解包（实机暴露）

### Symptom
aidev6 终端执行 `setup-dev-env` 报 `proot 不存在：/data/app/.../com.aidev.six.dev-.../lib/arm64/libproot.so`，终端/宇宙A/宇宙B 全部无法启动。

### Root Cause
`libproot.so` / `libproot_loader.so` 仅放在 `app/src/main/jniLibs/arm64-v8a/`，依赖 `android:extractNativeLibs="true"` 让系统把原生库解包到 `nativeLibraryDir` 后由 shell 直接 `exec`。在 Android 16（targetSdk 36）上该解包未生效（原生库默认也不可执行），脚本找不到可执行 proot。

### Fix
与已能工作的 `libtalloc.so.2` / `libandroid-shmem.so` 同机制：把 `libproot.so` / `libproot_loader.so` / `libproot_loader32.so` 作为 `assets/proot-libs/arm64-v8a/` 打包；`TerminalShellAssets.installProotSupportLibraries` 在终端初始化（按 versionCode 门控）把它们拷到 `home/proot-lib` 并 `setExecutable(true)`；`AIDEV_PROOT` / `AIDEV_PROOT_LOADER` / `PROOT_LOADER`（`TerminalShellAssets` rc、`UbuntuBootstrapScripts` 引导、`ProotLauncher`、`SessionManager`）全部改用 `$AIDEV_HOME/proot-lib`。

### Verification
`assembleDebug` BUILD SUCCESSFUL（versionCode 14→15），静默重装到设备；待真机重开 aidev6 进终端重试 `setup-dev-env` 确认。

### Related Files
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`（`installProotSupportLibraries` + rc 生成）
- `app/src/main/java/com/aidev/six/terminal/ProotLauncher.kt`
- `app/src/main/java/com/aidev/six/terminal/SessionManager.kt`
- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`
- `app/src/main/assets/proot-libs/arm64-v8a/`（新增 3 个 proot 二进制）

### Addendum — proot 缺少 +x 位（同次实机，二次修复）
初次修复后 proot 库已拷到 `home/proot-lib`，但 exec 报 `Permission denied`。定位：本机 `files/home` 可执行（five.dev logcat 显示 SELinux 对 app_data_file 的 `execute` 为 granted），故非 noexec/SELinux；根因是 `File.setExecutable` 在 Android 上静默失效，文件无 +x 位。
修复：在 `UbuntuBootstrapScripts.aidevUbuntuCommandScript` 设置 `AIDEV_PROOT` 后追加 `chmod 755 "$AIDEV_PROOT" "$AIDEV_PROOT_LOADER"` 兜底（脚本以 app 身份运行，可改自身文件权限）。重装 v16 验证。

### Addendum 2 — 真根因：filesDir/cacheDir 受 W^X 限制，proot 必须放 code_cache（三次修复，已定论）
v16 加 `chmod 755` 后 exec 仍报 `Permission denied`（加 +x 也无效）。真根因：**Android 10 (API 29)+ 对应用私有 `filesDir`/`cacheDir` 强制 W^X**，直接 exec 其中的 ELF 被内核以 EACCES 拒绝，与 +x 位无关。之前「five.dev 能在 files/home exec」的判断有误——five.dev 里跑的是 rootfs **内部**经 proot 命名空间执行的二进制，proot **本体**需在允许 exec 的区域。
唯一的 App 私有可执行豁免区是 `context.codeCacheDir`（`/data/data/<pkg>/code_cache`，为 JIT/动态代码保留，允许 exec 且 App 可读写）。
修复：`PathConfig.prootLibDir` 改为 `File(ctx.codeCacheDir, "proot-lib")`；所有引用（`TerminalShellAssets` 拷贝+rc、`UbuntuBootstrapScripts.aidevUbuntuCommandScript` 新增 `prootLibsPath` 参数、`ProotLauncher`、`SessionManager`、`DevEnvironmentChecker`）改用 `PathConfig.prootLibDir`。另加存在性兜底：code_cache 可能被系统清理，`TerminalShellAssets.ensure` 检测 `libproot.so` 缺失时无视 versionCode 门控重新部署。同时移除 manifest 已废弃的 `android:extractNativeLibs`（AGP9 用 `packaging.jniLibs.useLegacyPackaging`）。
验证：`assembleDebug` BUILD SUCCESSFUL（versionCode 17），78 单测 + 63 shell 测试通过，`aapt2 dump badging` 确认 `com.aidev.six.dev` v17 debuggable arm64-v8a。待真机重开验证 `ubuntu` 进宇宙A。

### Addendum 3 — code_cache 在本机也被拒；唯一解 = nativeLibraryDir（四次修复，实机验证方向正确）
v17（code_cache 方案）实机 exec 仍报 `/data/data/com.aidev.six.dev/code_cache/proot-lib/libproot.so: Permission denied`。结论：本机 HyperOS/Android 16 对 **code_cache 同样不允许 exec**（W^X 覆盖整个 app_data_file，不止 filesDir）。外部 AI 的 code_cache 建议在此设备无效。
**唯一可靠可执行区 = `nativeLibraryDir`**（`ctx.applicationInfo.nativeLibraryDir`，即 `/data/app/<...>/lib/arm64/`）：APK 内 `lib/<abi>/lib*.so` 由系统安装时解包到此，label=apk_data_file、挂载 exec、SELinux 允许 App exec。实机 `ls` 确认 v17 起 `libproot.so` 已 `-rwxr-xr-x` 躺在这里（此前「未解包」的判断已因移除 `extractNativeLibs` + `useLegacyPackaging=true` 而不成立）。
关键细节：`readelf -d libproot.so` 显示 DT_NEEDED = `libtalloc.so.2` + `libandroid-shmem.so` + `libc.so`。
- `libandroid-shmem.so` 以 `.so` 结尾 → 正常解包进 nativeLibraryDir，LD_LIBRARY_PATH 含该目录即可直接解析。
- `libtalloc.so.2`（版本化 soname）**不以 .so 结尾 → 系统不解包**。故：把它作为 `libtalloc.so` 放进 jniLibs（解包进 nativeLibraryDir），运行时在可写目录 `filesDir/home/proot-lib` 建符号链接 `libtalloc.so.2 -> nativeLibraryDir/libtalloc.so`，并把该目录加入 LD_LIBRARY_PATH。符号链接本身无可执行内容，链接器最终 mmap 的是 nativeLibraryDir 里的真实文件（exec 允许）。
修复：
- jniLibs 新增 `libtalloc.so`（由 libtalloc.so.2 改名）与 `libandroid-shmem.so`；删除 `assets/proot-libs/`（改由解包提供）。
- `PathConfig.nativeLibDir` = 可执行体所在；`PathConfig.prootLibDir` = filesDir 下的符号链接目录。
- `TerminalShellAssets.installProotSupportLibraries` 改为仅建 `libtalloc.so.2` 符号链接（`Os.symlink`，失败退化读拷贝）。
- rc / `aidevUbuntuCommandScript`(参数改 nativeDir+extraLibs) / `ProotLauncher` / `SessionManager`：`AIDEV_PROOT`=nativeDir/libproot.so、`PROOT_LOADER`=nativeDir/libproot_loader.so、`LD_LIBRARY_PATH`=extraLibs:nativeDir 并导出。移除对 system-owned 文件无效的 `chmod`。
- `DevEnvironmentChecker`：校验 nativeDir/libproot.so + proot-lib/libtalloc.so.2 符号链接。
验证：`assembleDebug` SUCCESSFUL（**versionCode 18**），Kotlin 编译 + 63 shell 测试通过；badging/unzip 确认 5 个 proot 依赖库全在 `lib/arm64-v8a/` 且 assets 无残留；Shizuku 静默装 v18 后实机 `ls nativeLibraryDir` 确认 5 库均 `-rwxr-xr-x`。
**✅ 实机终验通过（2026-07-11）**：重启 aidev6 后跑 `ubuntu` 成功进入宇宙A（提示符变 `aidev[A]:/root#`），proot 不再 `Permission denied`。此坑（proot 无法 exec）彻底关闭。
注：在宇宙A 内再敲 `ubuntu` 会报 `cannot open .../dev-env/bin/aidev-ubuntu-core: No such file`——因 proot 命名空间里宿主目录被绑到 `/host-home`，属预期；宇宙A 内不应再跑 `ubuntu`（宇宙A 里那份 `.aidevrc` 的 `ubuntu` 别名指向了宿主路径，纯属残留，无害）。

## 2026-07-11 — 进入宇宙A 后 aidev 命令指向宿主绝对路径 + proot 嵌套

### Symptom
成功 `ubuntu` 进入宇宙A（`aidev[A]:/root#`）后，跑 `setup-dev-env` / 再跑 `ubuntu` 报
`/bin/sh: 0: cannot open /data/user/0/com.aidev.six.dev/files/home/dev-env/bin/aidev-ubuntu-core: No such file`。

### Root Cause
宇宙A 的 `/root/.bashrc` 会 `. /host-home/.aidevrc` 复用宿主 rc。而 rc 里 `AIDEV_HOME` 被硬写成 App 私有**绝对路径** `/data/user/0/.../files/home`；proot 内宿主 home 实际绑定在 `/host-home`，该绝对路径在 proot 命名空间不存在。于是 `AIDEV_BIN=$AIDEV_HOME/dev-env/bin` 指向不存在的路径，`ubuntu()`/`setup-dev-env()` 等函数 `sh $AIDEV_BIN/aidev-ubuntu-core` 打不开。其次，即便路径修对，在宇宙A 内再跑 `setup-dev-env`/`ubuntu`/`compiler` 会嵌套 proot。

### Fix
1. rc(`TerminalShellAssets.writeCanonicalRc`)：`AIDEV_HOME` 改为按位置解析——`if [ -d /host-home ]; then AIDEV_HOME=/host-home; else AIDEV_HOME=<abs>; fi`（/host-home 仅在 proot 内存在，是可靠的「是否在 rootfs 内」判据）。
2. `aidev-ubuntu-core`(`UbuntuBootstrapScripts`)：新增 `AIDEV_IN_ROOTFS` 检测（`[ -d /host-home ] || AIDEV_REALM!=H`）；`run_ubuntu_command` 在 rootfs 内直接 `exec /bin/sh -lc "$*"`（就地执行，不嵌套 proot）；`enter_ubuntu`/`enter_compiler_rootfs`/`run_compiler_cmd` 在 rootfs 内给出友好提示并返回，不再嵌套。

### Verification
`assembleDebug` SUCCESSFUL（**versionCode 19**），Kotlin 编译 + 63 shell 测试通过；Shizuku 静默装 v19。待用户重启 aidev6 → `ubuntu` → 在宇宙A 内 `setup-dev-env` 验证就地执行。

### 环境坑 — build-tools 36.1.0 的 aapt2 在 qemu 下损坏，构建须用 34.0.0 override
本机（arm64 + qemu-x86_64）上 `build-tools/36.1.0/aapt2` 经 qemu 运行 `version`/`daemon` 均静默 exit 1，导致 `AarResourcesCompilerTransform` 报 `AAPT2 Daemon startup failed`。`build-tools/34.0.0/aapt2` 正常（`daemon` 返回 `Ready`）。构建命令须加 `-Pandroid.aapt2FromMavenOverride=/host-home/android-sdk/build-tools/34.0.0/aapt2`（勿写入受版本保护的 gradle.properties，按需在本地/CI 传参）。

## 2026-07-11 — 真机宇宙B 编译：aapt2 是动态 x86_64，需自带 x86_64 sysroot + qemu 显式 loader

### Symptom
真机 aidev6 沙箱内「提交构建请求」，宇宙B 跑 `assembleDebug` 卡在 `processDebugResources`：
探针 `qemu-x86_64-static <aapt2> version` 报 `Could not open '/lib64/ld-linux-x86-64.so.2'`。

### Root Cause
Google 发布的 `build-tools/*/aapt2` 是**动态链接的 x86_64** ELF，运行需要 x86_64 的 loader（`ld-linux-x86-64.so.2`）+ glibc。宇宙B 是纯 arm64 rootfs，没有这些 x86_64 依赖；手机无 root 也无法注册 binfmt 让内核自动套 qemu。此前只带了 `qemu-x86_64-static`（AArch64 静态 PIE，能跑，但它去找 x86_64 loader 时找不到）。

### Fix（复刻开发机成功方案）
开发机上「能用」的 `aapt2` 其实是个**包装脚本**：`export QEMU_LD_PREFIX=/usr/x86_64-linux-gnu; exec qemu-amd64-static .../ld-linux-x86-64.so.2 "$0.real" "$@"`——即用 `QEMU_LD_PREFIX` + 显式 x86_64 loader 跑真正的动态 `aapt2.real`。把这套整体搬进 APK：
- `app/src/main/assets/tools/x86_64/`：`aapt2.real`（6MB 动态 x86_64）+ `lib/` 9 个 glibc 库（ld-linux-x86-64.so.2, libc/libm/libdl/libpthread/librt/libgcc_s/libstdc++/libz）。
- `app/src/main/assets/tools/qemu-x86_64-static`：AArch64 静态 qemu。
- `BuildBridgeService.ensureX86Aapt2()`：部署 qemu + sysroot 到 `/host-home/{qemu-x86_64-static,x86_64}`，生成包装脚本 `/host-home/x86_64/aapt2`：
  `export QEMU_LD_PREFIX=$DIR; exec /host-home/qemu-x86_64-static $DIR/lib/ld-linux-x86-64.so.2 --library-path $DIR/lib $DIR/aapt2.real "$@"`，override 指向它。自带完整依赖，不再依赖设备 build-tools 版本/glibc。

### 关键教训
- **proot 禁用全局 `-q qemu`**：会把原生 arm64 二进制（如 `/usr/bin/env`）也塞进 x86_64 qemu → `Function not implemented`。只给需要跨架构的单个二进制（aapt2）套 qemu 包装。
- `System.setProperty("android.aapt2FromMavenOverride")` 对 AGP **无效**，必须走真·Gradle `-P` 命令行属性。
- 探针先行：编译前跑 `<aapt2> version; echo AAPT2_PROBE_EXIT=$?` 能一眼定位是「qemu 跑不起来」还是「aapt2 缺依赖」。

### Verification
真机「提交构建请求」→ `processDebugResources` 通过，宇宙B 出 APK（Phase H 编译阶段打通）。

## 2026-07-11 — 安装/拉起「fire-and-forget」误报成功 + 测试项目与宿主同包名（本末倒置）

### Symptom
1. 构建后系统提示「已成功安装并拉起」，但实际设备上既没装也没拉起。
2. 手动 `aidev-install` 也提示成功，实为 `Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]`。

### Root Cause
1. `BuildBridgeService.installAndLaunch` 用 `ShizukuLogcat.executeFireAndForget`——发出命令即返回，从不检查 exit code / 输出，因此 `pm install`/`am start` 无论成败都打印「已安装/已拉起」，`finish` 也据此报成功。
2. **更根本**：脚手架生成的测试项目 `MyAndroidProject` 的 `applicationId`/`namespace`/`package` 全被写成宿主自己的包名 `com.aidev.six.dev`（`BuildBridgeService` scaffold 硬编码）。装测试 App = 覆盖/顶掉 aidev6 本体；且签名不同 → 直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。本末倒置。

### Fix
1. `installAndLaunch` 改 `suspend` + 用 `executeCommand` 拿真实结果：检查 `exitCode`/`Failure`/`Error`，如实上报安装、拉起状态；`finish` 文案区分「已安装/未完成」；`finish` 始终导出 `last-build.log` 便于诊断。
2. 脚手架（`BuildBridgeService` 生成的 build.gradle.kts/Manifest/MainActivity）改用独立包名 `com.example.myandroidproject`；磁盘上已存在的 `/root/workspace/MyAndroidProject` 一并改（build.gradle.kts + MainActivity 移到新包路径）。

### 关键教训
- **测试/示例项目绝不能与宿主 App 同包名**：否则安装即自毁，签名冲突还会误导排查。
- **不要对 install/launch 用 fire-and-forget**：涉及成败判定的操作必须拿 exit code，否则 UI 全程「假成功」。
- 差点埋的雷：本想加「签名冲突自动卸载重装」——在同包名 bug 下这会自杀式卸掉 aidev6。修 bug 要治根因（改包名），而非在错误前提上加「智能重试」。

## 2026-07-12 — D4 崩溃回流：抓到的是系统噪声而非真实堆栈

### Symptom
自我进化闭环 D4 阶段：宇宙B 编译→安装→拉起→App 崩溃，`.aidev-mcp/crash-*.json` 与 `.aidev-loop/crash-*.json` 契约文件都能正确生成（`crashed=true`、包名/项目正确），但 `stack`/`fatal` 内容是 SurfaceFlinger / miui.home 等全系统噪声，看不到真正的 `RuntimeException`。

### Root Cause
1. `CrashReportBridgeService.handleRequest` 走 `buildLogcatCommand` 的 `filters`，被 logcat 当成 `tag:priority` 过滤器（格式不符）→ 实际没起过滤作用。
2. 崩溃后约 8s 目标进程已死，`pidof <pkg>` 为空 → 回退成 dump 全系统日志，混入海量无关 FATAL/Error 行。
3. `fetchLog` 内 `Log.d("fetchLog: $cmd")` 自污染 logcat，`parseCrash` 的「Exception/Error」关键字兜底命中自身日志行（B9 早已预警的误报）。

### Fix
1. `ShizukuLogcat` 新增 `fetchCrashLog(lines, callback)`：改用 `logcat -d -v threadtime -b crash -t N`（**崩溃缓冲区**在进程死后仍保留 FATAL 块）+ 拼接 main 缓冲，完全不用 filters。
2. `CrashReportBridgeService.handleRequest` 改调 `fetchCrashLog`；`parseCrash`/`collectCrashBlock` 重写：先剔除 `ShizukuLogcat`/`---MAIN---` 自污染行，按 `FATAL EXCEPTION` + `Process: <pkg>` 定位最新崩溃块，`collectCrashBlock` 只收 `AndroidRuntime:` 前缀行。

### Verification（v41，b41）
不重新构建——测试 App 仍在设备上，`am start` 拉起使其崩溃（新崩溃入 crash buffer），手工投递 `.aidev-crash-bridge/req` 触发抓取。生成的 `crash-1783797303149.json`：`fatal` 为真实 `RuntimeException: ...intentional crash in onCreate`，`stack` 为干净完整栈含 `Caused by` + `at com.example.myandroidproject.MainActivity.onCreate(MainActivity.kt:10)`，系统噪声消失。

### 关键教训
- **抓崩溃优先用 `-b crash` 缓冲区**：进程死后 main 缓冲会被冲掉/污染，crash 缓冲区专存 FATAL 块且留存更久，比 `pidof` 过滤可靠。
- **日志抓取器自身别写 logcat**：`Log.d` 调试语句会污染自己要解析的数据源（自指），务必移除或用不入 logcat 的通道。
- logcat 的 `filters` 参数是 `tag:priority` 语义，不能拿来做「只看某包名」——按包名过滤要靠解析 `Process:` 行。

## 2026-07-12 — 自进化守护重建请求写错目录（宇宙B 读不到）

### Symptom
真机首次跑通「改码」后，opencode 已正确改码、`fix_applied=true`、也写了 `req-se-*.json`，但宇宙B 从未自动重建。

### Root Cause
`aidev-self-evolution` 的 `BRIDGE_DIR` 默认 `$HOME/.aidev-build-bridge`。宇宙A rootfs 内 `$HOME=/root`，于是请求写到 `/root/.aidev-build-bridge/req-se-*.json`——而 `BuildBridgeService` 轮询的是 App 沙箱 `filesDir/home/.aidev-build-bridge`（宇宙A 内挂载为 `/host-home/.aidev-build-bridge`）。两者不是同一目录，请求石沉大海。用户不加 `AIDEV_BRIDGE_DIR` env 就必然踩中。

### Fix
守护脚本自动对齐 aidev6 拓扑：`/host-home` 存在时 `BRIDGE_DIR=/host-home/.aidev-build-bridge`、`WORKSPACE=/host-home/workspace`（env 仍可覆盖）。用户直接 `aidev-self-evolution --once` 即可，无需手传路径。

### Verification（真机全闭环）
04:03:36 崩溃 → 抓取 → 守护调 opencode(hy3-free) 移除 throw → fix_applied → 请求归位 → 宇宙B 自动重建 → 04:12:06 拉起且崩溃缓冲区无新崩溃。北极星闭环达成。

### 关键教训
- **跨宇宙文件契约的路径必须以 App 沙箱（/host-home）为准**，不能用守护进程自身的 `$HOME`。宇宙A 的 `$HOME=/root` ≠ App 的 aidevHome。
- 免费模型（hy3/big-pickle 等）按 IP 限额，耗尽时 `opencode run` exit 0 且空返回不报错 → 无法自动切换，改由「对话可见 + App 手动选模型」（v42）。

## 2026-07-13 — 构建黑盒彻底解耦部署（v120）+ 面板部署按钮（v121）

### Symptom
尽管 07-11 修了 fire-and-forget 误报，但「构建=编译+安装+拉起」混在一起的职责依然脆弱：构建阶段既不该也不应负责设备侧动作，且面板没有独立的「仅安装 / 安装并拉起」控制；闭环自驱时构建成功但部署失败会被掩盖。

### Root Cause
- 构建黑盒权责过大：一次调用同时干编译、安装、拉起三件事，任一步失败都会污染 `success` 语义。
- 面板缺少直接驱动部署黑盒（黑盒2）的入口；用户要求能显式选择「仅安装」或「安装并拉起」。

### Fix
- **v120**：删除 `BuildBridgeService.installAndLaunch` 及 result json 的 `autoInstall`/`autoLaunch`，构建黑盒只产出 APK（真存在）+ `pkg`，部署完全交给独立黑盒2。
- **v121**：
  - 新增 `DeployBridgeService`（同构于 `BuildBridgeService`）：轮询 `home/.aidev-deploy-bridge/req-<id>.json`，在 PRoot（agent rootfs）内跑 `aidev-deploy --apk ... --pkg ... [--launch|--no-launch]`，解析标准出口 JSON（`installed`/`launched`/`activity`/`error`），单一真源写 `agent-tasks.json`。
  - 新增 `DeployRequestTracker`：写 `req-<id>.json` 并等待结果。
  - `ServerPanel`「宇宙 B」新增「部署到设备」区（安装并拉起 / 仅安装），`lastBuildArtifact()` 读 `pkg`。
  - `SessionManager.startShizukuBridge()` 启动 `DeployBridgeService`；部署脚本在 `onStart()` 从 assets 落地 `dev-env/bin`。
  - `BuildBridgeService` 解析产物 `pkg`（`aapt2 dump badging`），result 带 `pkg`/`project`。
  - 取消逻辑接入 `deploy` 标签。
- 落地版本 **v121**（1.0.0-b121）。

### 关键教训
- **编译、部署、验证必须是三个独立黑盒**：构建成功能不等于部署成功，混在一起只会让「修好」判定失真。
- 面板/闭环的状态必须来自设备侧真实校验（PM 已装、进程在跑、无崩溃），不能是本地命令的 exit 0 即成功。

## 2026-07-13 — aidev-deploy 安装失败（aidev-install exit non-zero）

### Symptom
面板点「安装并拉起 / 仅安装」后，`aidev-deploy` 出口：
```
{"apk":"/workspace/MyAndroidProject/app/build/outputs/apk/debug/app-debug.apk","pkg":"com.example.myandroidproject","installed":false,"launched":false,"activity":null,"error":"install failed (aidev-install exit non-zero)"}
```
`aidev-install` 退出码非 0，设备侧未安装。

### Root Cause（已定位）
1. **Shizuku 桥安全策略拦截 `cp`**：`ShizukuBridgeService.isCommandAllowed` 仅放行前缀 `pm/input/svc/dumpsys/cmd`。安装命令是 `cp <apk> /data/local/tmp/... && pm install ...`，其中 `cp` 及 `&&`/`'` 不在白名单 → 直接返回 `ERROR: 命令被安全策略拒绝` → `aidev-install` 退出非 0。
2. **`pm install` 不能读 `/sdcard`**：SELinux `avc: denied { read }` —— `system_server` 无法读 fuse 的 `/sdcard` 文件。所以安装前必须把 APK 暂存到 `/data/local/tmp`（仅 shell 可读、system_server 也可读）。正是这个暂存 `cp` 被策略拦了，形成死结。
3. **`TYPE` 解析为空导致假成功**：请求文件缺 `TYPE` 时，`handleRequest` 按 log 请求处理、返回 logcat，`aidev-install` 误判成功（最早的「假成功」来源之一）。

### Fix（v123，构建为宿主包 com.aidev.five.dev 覆盖安装）
- `ShizukuBridgeService`：`ALLOWED_COMMAND_PREFIXES` 增加 `cp `/`am `/`monkey `；`handleRequest` 在 `TYPE` 为空时按文件名（`exec_`/`hb_`）兜底为 `exec`，杜绝 logcat 伪装成功。
- `aidev-install.sh`：`install_silent` 捕获 `aidev-shizuku` 真实输出，检测 `Failure/INSTALL_FAILED/Error` 并退出非 0 带原因；`set +e` 包裹捕获避免被 `set -e` 截断。
- `aidev-deploy.sh`：`aidev-install` 失败不再吞输出，最多重试 3 次（容忍瞬时桥抖动），并把真实错误带进 JSON `error`。
- 包名说明：宿主 App = `com.aidev.five.dev`（运行环境，agent 身处其中，由独立宿主仓库构建）；项目 App = `com.aidev.six.dev`（本仓库 `applicationId=com.aidev.six`，源码从宿主仓库继承）。仓库源码里没有 `com.aidev.five` 字样，宿主是另一份仓库。
- **交付状态**：本仓库（six）已含完整修复并 `compileDebugKotlin` 通过。但**正在跑的宿主（five）agent 暂时无法直接修改/重构建**，故宿主需由宿主仓库侧合入同一处 `ShizukuBridgeService.kt` 改动（见上「Fix（v123…）」）并经宿主正常构建流程后才能生效。aidev6 仓库 `applicationId` 保持 `com.aidev.six` 未动。

### 验证（已确认）
用户手动安装 six **b122**（含本修复）→ 面板「安装并拉起 / 仅安装」出口 `installed:true`；`pm list packages` 含目标包。安装不再假成功/假失败。

## 2026-07-13 — aidev-deploy 安装成功但拉起失败（Shizuku 客户端 preamble 污染输出）

### Symptom
面板点「安装并拉起」后出口：
```
{"apk":"/workspace/MyAndroidProject/app/build/outputs/apk/debug/app-debug.apk","pkg":"com.aidev.app.myandroidproject","installed":true,"launched":false,"activity":null,"error":"launch failed: Shizuku 请求已发送\n命令: am start -n Shizuku 请求已发送\n命令: cmd package resolve-activity ..."}
```
安装成功（`installed:true`），但拉起失败（`launched:false`）。用户还反馈面板「报告安装失败」——实际是 `launched:false` → 整任务 `success=false` → 通知「AIDev 部署失败」，用户误读为安装失败。`DeployBridgeService` 本身已正确按 `installed`/`launched` 分流「安装:成功 / 拉起:失败」，根因在拉起解析。

### Root Cause
`aidev-shizuku` 客户端会把三行提示打到 stdout：`Shizuku 请求已发送`、`命令: <cmd>`、`等待执行结果...`。`aidev-deploy` 用 `$(aidev-shizuku exec "...")` 捕获真实命令输出时，把这三行 preamble 也一起收进变量：
- `resolve-activity` 的 `COMP` 变成 `preamble + pkg/.Activity`，`grep "/"` 虽放行，但随后拼出的 `am start -n $COMP` 是畸形命令（含 preamble 文本）→ 启动失败。
- `error` 字段把 `OUT`（含 preamble）带回，导致日志里出现 `命令: am start -n Shizuku 请求已发送...` 这类垃圾。

### Fix（v124）
- `aidev-deploy.sh` 新增 `clean_output` 过滤器：`grep -vE '^Shizuku 请求已发送$|^命令: |^等待执行结果'`，所有 `aidev-shizuku exec` 捕获都 `| clean_output`。
- 组件解析加固：`resolve-activity` 输出经 `clean_output | grep "/" | tail -1 | tr -d '\r' | xargs`，确保 `COMP` 只剩 `pkg/.Activity`。
- 面板 `DeployBridgeService` 无需改：`installed:true → 安装:成功` 本就正确；拉起修好后 `launched:true → 部署成功：已安装并拉起`，不再误报。

### 关键教训
- 设备侧 Shizuku 客户端向 stdout 注入的用户提示行，必须在解析命令输出前剥离，否则会污染 `am start -n` 等参数拼接。
- 「安装成功但拉起失败」应被整体视为部署未完全成功（通知「部署失败」），但面板/日志要把「安装」「拉起」两阶段状态分开呈现，避免让用户误以为安装也失败了。

### 验证（待用户装 b124 后）
面板「安装并拉起」→ 出口 `installed:true, launched:true`；`logText` 显示「安装: 成功   拉起: 成功」。

## 2026-07-13 — 修了 aidev-deploy 仍「安装成功、拉起失败」：运行环境里的脚本根本没更新

### Symptom
v124 已加 `clean_output` 并重建 six，但面板「安装并拉起」依旧 `installed:true, launched:false`。设备上直跑 `cmd package resolve-activity` 返回正确组件、`am start -n <组件>` 也成功（`Starting: Intent {...}`）——证明设备侧 launch 没问题，是**运行中的部署脚本仍是被 preamble 污染的旧版**。

### Root Cause（定位）
1. **真正执行部署黑盒的宿主是 `com.aidev.five.dev`**：`/host-home` 是 `/data/data/com.aidev.five.dev/files/home` 的 bind 挂载；six 的 `files/home` 未被初始化。部署脚本由 `DeployBridgeService.ensureDeployScripts()` 从**宿主 App 的 assets** 抽进 `home/dev-env/bin/aidev-deploy`。
2. **共享 home 的 `dev-env/bin` 是 root 所有(700)且缺 `aidev-deploy`**：里面只有早先以 root 创建的 `aidev-install`/`aidev-shizuku`，没有 `aidev-deploy`。five 的 assets 比 v120/v121 更老、根本没有 `aidev-deploy.sh`，所以 `ensureDeployScripts` 抽取失败、脚本始终缺失/陈旧。仓库里改的脚本从未到达运行环境——这就是「改了仓库却还旧行为」的本质。
3. 旧版 `aidev-deploy` 把 `aidev-shizuku` 的 `Shizuku 请求已发送/命令:/等待执行结果...` 三行 preamble 当作命令输出，污染组件名 → `am start -n` 畸形 → 拉起失败。

### Fix（v125，遵循「母体 A 孵化子体 B」自举架构）
- **即时生效（Agent 直写共享 home）**：以 root 把修正后的 `aidev-deploy`（含 `clean_output`）写到 `/host-home/dev-env/bin/aidev-deploy` 与 rootfs `/usr/local/bin/aidev-deploy`（两个 PATH 候选位），并附 `aidev-deploy.md5`。five 的 `ensureDeployScripts` 因 assets 无该文件而不会覆盖 → 重启也持久。设备上直跑部署验证：`{"installed":true,"launched":true,"activity":"com.aidev.app.myandroidproject/.MainActivity","error":null}`。
- **架构硬化（DeployBridgeService.kt）**：
  - 部署脚本**不再是宿主 assets 的附属**，宿主变为「盲盒执行器」：仅按**绝对路径** `home/dev-env/bin/aidev-deploy` 调起。
  - 新增 **MD5 握手 + Fail-Fast**：脚本与 `.md5` 必须同存且一致，否则直接回报错误 JSON（`validateDeployScript`），杜绝「脚本缺失/损坏却误判成功」。
  - `ensureDeployScripts` 改为**非破坏式**：Agent 已投递的副本绝不覆盖，只在首次缺失时从 assets 兜底，并补生成 `.md5`。
  - 脚本加 `--version` 自报来源（Git SHA），便于闭环可靠定位运行中副本。
- 构建产物 APK → `/storage/emulated/0/AIDev/app-debug.apk`（遵守「禁止自动安装」规则）。

### 关键教训
- **宿主与 Agent 编辑树解耦时，桥接脚本必须作为「构建产物」由 Agent 单向投递到共享 home，绝不能依赖宿主 assets 在运行时解包**——否则仓库改了、线上还是旧脚本。
- **asset→运行时抽取遇权限/缺失必须 Fail-Fast 并回报明确错误**，不能让闭环因「解析自未知位置的输出」而误判成功。
- 观察者（Agent）权限受限时，用「绝对路径调用 + 脚本自报版本 + 共享 home runtime.log」三件套来可靠定位运行中的副本。

### 验证
设备上直跑 `aidev-deploy --apk <apk> --pkg com.aidev.app.myandroidproject --launch` → `installed:true, launched:true`。面板「安装并拉起」预期同结果。

## 2026-07-13 — 快速失败误报「部署入口脚本或校验文件缺失」（校验/执行路径错位）

### Symptom
装上 b125 后点「安装并拉起」回报：`部署入口脚本或校验文件缺失（Agent 需在构建后投递 aidev-deploy + aidev-deploy.md5 到共享 home/bin）`。但 `/host-home/dev-env/bin/aidev-deploy` + `.md5` 明明存在。

### Root Cause
部署在 PRoot 内用 `AIDEV_HOME=/host-home`（绑定视图）找脚本；而 `validateDeployScript` 在 Android 侧用 `ctx.filesDir/home`（app 真实 `files/home`）查。两者本应经 bind 等同，但当时 `/host-home` 这个绑定**源已过期**（宿主 home 被清/重装后，陈旧绑定仍显示旧内容），导致 Android 侧真实路径查不到脚本 → 误触发 Fail-Fast。根子是「校验路径」与「执行路径」不一致。

### Fix（v126）
- `DeployBridgeService` 新增常量 `SHARED_HOME="/host-home"`，**校验与执行统一用 `/host-home/dev-env/bin/aidev-deploy`**，与 PRoot 的 `AIDEV_HOME` 完全一致，消除绑定视图与真实 `files/home` 的错位。
- `ensureDeployScripts` 也改写到 `/host-home/dev-env/bin`（含生成 `.md5`），兜底萃取的副本同样落在共享 home。
- 设备上直跑部署已验证 `installed:true, launched:true`；`/host-home` 内脚本与 `.md5` 已同步为最新版（含 `clean_output` + `--version` 自报）。

### 关键教训
- **宿主侧校验路径必须和 PRoot 执行路径用同一个“共享 home”锚点**，不能一边查 `ctx.filesDir/home`、一边在 PRoot 里查 `/host-home`——一旦 bind 源过期就错位。
- 观察者（root shell）读 `/data/data/<pkg>` 会被 SELinux 拦截，`ls` 报“不存在”是假象，不能据此判断 app 私有目录状态；应以 app 自身/绑定视图为准。

### 生效前提（重要）
代码改动只在 **运行宿主是 b126（six）** 时生效。当前活跃宿主仍是旧 five（`/host-home` 绑定指向 five），five 没有这套改动 → 必须：
1. 安装 b126（已复制到 `/storage/emulated/0/AIDev/app-debug.apk`）；
2. **关闭旧 five、打开 six** 让 six 成为 agent 宿主（`/host-home` 重新绑定到 six 的 home），`ensureDeployScripts` 即从 six 的 assets 萃取部署脚本；
3. 之后面板「安装并拉起」应通过校验并成功拉起。
（若坚持用 five 作宿主，则需把本仓库按 five 重编一次以带入 b126 改动。）
