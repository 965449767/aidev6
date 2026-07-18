# Error Journal

Use this file to record repeated failures, non-obvious bugs, and lessons learned.

## 2026-07-18 - enter_ubuntu 报 `/system/bin/grep: cannot execute: required file not found`

**Symptom**：执行 `enter_ubuntu`(进入宇宙A)后,打印「已进入宇宙 A(OpenCode)」并紧跟
`bash: /system/bin/grep: cannot execute: required file not found`。进入仍成功,但提示难看且
`ensure_android_groups` 的 group 补齐逻辑失效。

**Root Cause**：`enter_ubuntu` 在**宿主侧(宇宙H,非 PRoot)**依次执行 `has_ubuntu` →
`ensure_android_groups` → `ensure_ubuntu_helpers`,再 `exec` PRoot 进宇宙A。`ensure_android_groups`
(`UbuntuBootstrapScripts.kt`)用 `grep -q "^[^:]*:[^:]*:$gid:"` 检查 rootfs 的 `/etc/group`,而宿主
PATH 含 `/system/bin`,`/system/bin/grep` 是 **x86_64 架构二进制**(aarch64 宿主上
`required file not found`)。报错来自宿主 bash 执行该坏 grep。

**Fix**：`ensure_android_groups` 改为**纯 POSIX shell** 逐行读取 `/etc/group` 判断 gid 是否已存在,
完全不依赖外部 `grep`(规避宿主坏架构 grep 的同类问题)。宿主侧其他 `enter_ubuntu` 调用链
(`has_ubuntu`/`install_ubuntu`/`ensure_ubuntu_helpers`)经核查无 grep 依赖。

**⚠️ 第一轮查错位置偏差**：改完 `ensure_android_groups` 后用户复现报错仍在。真正触发点是
**`.aidevrc` 尾部的历史净化逻辑**(`TerminalShellAssets.kt` 旧 `grep -qE '\(\) \{|aidev\[A\]'`)
在**宿主交互 shell 启动**(经 `ENV` source)及进宇宙A(`/bin/bash -l` 再 source 一次)时执行,
每次都调宿主坏 `/system/bin/grep` → 报错。与 `enter_ubuntu` 函数体无关。

**Fix(v2, 真正修复)**：`.aidevrc` 历史净化改为**纯 shell 逐行扫描 HISTFILE**(检测 `*'() {'*` /
`*'aidev[A]'*`),彻底不调用外部 grep。配合 `ensure_android_groups` 纯 shell 化,
宿主侧启动路径已无任何 grep 依赖。

**交付教训**：修复"报错"类问题必须定位**真正触发点**(看报错出现的精确时机/调用栈),而非
只修第一个可疑的同类调用。`enter_ubuntu` 打印后的报错其实来自紧随其后的 `exec proot ... /bin/bash -l`
启动宇宙A 时 source `.aidevrc` 的 grep,而非 `enter_ubuntu` 函数体本身的 `ensure_android_groups`。

**Prevention**：在宿主侧(宇宙H)执行的 aidev 脚本函数,**不得依赖 `/system/bin/grep|awk|sed`**
等可能为坏架构二进制的外部命令;需用文本处理时优先纯 shell 循环,或先 `command -v` 探测可用实现。


### Related Files
- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`（ensure_android_groups 纯 shell 化）

## 2026-07-18 - 终端方向键历史回显乱码（`aidev[A]` 的 `[A]`）

**Symptom**：AIDev 终端按上下方向键调历史命令时，提示符出现 `aidev[A]:`（`[A]` 是上方向键
转义序列 `ESC[A` 被原样回显），且正在输入的命令行与历史行交叉"格式变乱"；logcat 输出
（如 `RecentsTaskLoader: reloadTasksData...`）混进命令行。

**Root Cause**：终端 PTY 启动链 `/system/bin/sh` → `.aidev_shell_entry` → `exec sh -i`
（`TerminalShellAssets.kt:299`）。该 `sh` 是 Android mksh，但生成体**未启用行编辑/历史**
（`set -o emacs` / `set -o history` / `HISTFILE` 缺失），导致方向键转义序列不被行编辑器接管、
被原样打印。logcat 混入是 `aidev-logcat --follow` 持续刷屏到 PTY 与输入冲突（PTY 单流固有
问题，非 bug）。

**Fix（v1，不完整）**：
1. `.aidevrc` 生成体：`set -o emacs` + `set -o history` + `HISTFILE` 等。
2. `.aidev_shell_entry`：`exec sh -i` 前同样配置。
3. 文档引导 `--follow` 用项目页隔离 logcat。

**回归（v2，函数定义污染历史）**：v1 把 `set -o history` 放在 rc **函数定义段之前**，导致 mksh
交互启动默认 history 开启、source rc 时把**全部函数体**（`aidev-crash-why() { ... }` 等）写入历史，
方向键翻出函数定义文本，且旧文件 `~/.aidev_sh_history` 被污染。

**Fix（v2，正确）**：
1. rc **顶部** `set +o history`（source 期间关闭记录，函数/alias 不进历史）。
2. rc **末尾**（所有函数定义之后）才 `export HISTFILE` + `set -o emacs` + `set -o history`。
3. 末尾启用前检测并净化被污染的 HISTFILE（含 `() {` / `aidev[A]` 行则清空重建），一次性去除旧污染。
4. `.aidev_shell_entry` 改为 `set +o history`（与 rc 顶部一致），仅 export HISTFILE。

**Prevention**：mksh 交互 shell source rc 时默认记录历史，凡生成含大量函数定义的 rc，必须
`set +o history` 包裹定义段、`set -o history` 留到末尾，且历史文件需防函数体污染。

### Related Files
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`（.aidevrc 顶部+末尾历史配置、entry set +o history）
- `docs/non-android-guide.md`（§五 终端使用注意）


### Related Files
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`（.aidevrc + .aidev_shell_entry 历史配置）
- `docs/non-android-guide.md`（§五 终端使用注意）

## 2026-07-18 - 闭环回归报告残留点：dispatch 漏参 / dash shift 崩溃 / 构建锁误判

第三份"纯净回归版"闭环报告确认三项脚手架缺陷已全修、开箱即用闭环达成。顺带核查报告
列的非阻断改进点，发现并修复以下问题：

### 1. aidev-shizuku 在真机 dash 下无参直接崩溃（真 bug，最严重）

**Symptom**：`aidev-shizuku`（无参）或期望显示帮助时，真机 PRoot（/bin/sh=dash）下无任何
输出、rc=2，帮助分支永远到不了。

**Root Cause**：`SUBCOMMAND="${1:-}"; shift 2>/dev/null || true`。`shift` 是 shell **特殊内建
命令**，无参时失败，在 `set -e` 下 dash 会直接终止脚本，且 `2>/dev/null || true` **无法**兜住
（bash 能兜住，故 bash 测试与本机测试都漏掉）。修复：`if [ "$#" -gt 0 ]; then shift; fi`。

**Prevention**：涉及 `shift`/`set`/`eval` 等特殊内建命令时，别依赖 `|| true` 兜错；shell 测试
的 no-args/help 用例必须用 **dash 实跑**（helpers 里已有 dash 优先约定），bash 会掩盖此类坑。

### 2. aidev-ubuntu-core dispatch 再次漏传 "$@"（apk-info / verify-run）

**Symptom**：`aidev-apk-info <apk>`、`aidev-verify-run <target>` 在宇宙A 收不到参数。

**Root Cause**：`UbuntuBootstrapScripts.kt` 的 dispatch 中这两条漏了 `"${'$'}@"`（与 2026-07-17
同类回归）。且 `ubuntu_core_dispatch_test.sh` 的 ARG_CMDS 未覆盖 apk-info，还把 verify-run
错列进 NOARG_CMDS，故护栏没拦住。修复：补 `"${'$'}@"`，并把两者移入 ARG_CMDS。

### 3. aidev-deploy 位置参数被静默丢弃

`aidev-deploy /path.apk`（位置参数）此前落到 `*) shift` 被丢弃 → 误报 `missing --apk`。
改为首个位置参数视为 APK 路径，兼容 `--apk` 显式写法。

### 4. 构建锁跳过被客户端误判为失败（报告问题1）

宇宙A 客户端超时中断、宇宙B 仍后台编译时重复提交，宿主 ProjectTaskLock 正确持有锁并
让后续请求"跳过"，但 `aidev-build-request` 把该 result（success=false）当普通编译失败打印，
误导用户重试。**锁行为本身正确**（编译结束即释放），问题在客户端提示。修复：识别
"已有任务进行中"消息，改印"构建跳过（已有构建进行中）"+ 引导用 `aidev-build-log` 跟踪，
退出码 3（区别于失败 1 / 超时 2）。

### 附：安装弹窗澄清

报告中 `aidev-deploy` 返回 installed=false 是宇宙A 无设备直连、无法二次校验所致（设计分工）；
实际经宿主 + Shizuku 能装成功。用户确认：安装时的弹窗是 **Shizuku 自身的授权确认弹窗**，
确认后 APK 经 Shizuku（`pm install -r -d --user 0`）真实安装成功——安装链路健全，无需改动。

### Related Files

- `app/src/main/assets/scripts/aidev-shizuku.sh`（shift 保护）
- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt:553-555`（补 "$@"）
- `app/src/test/sh/ubuntu_core_dispatch_test.sh`（ARG_CMDS 补 apk-info/verify-run）
- `app/src/main/assets/scripts/aidev-deploy.sh`（位置参数）
- `app/src/main/assets/scripts/aidev-build-request.sh`（锁跳过提示 + 退出码 3）
- `app/src/test/sh/aidev-build-request_test.sh`（对齐"强制 --project"契约）

## 2026-07-18 - 评估报告指出优化项落地（JDK 悬空路径 / deploy 回传 / 环境边界）

结合《AIDev 开发环境评估与补充建议》报告，处理本仓库可落地的优化点。

### 1. PRoot 启动 env 注入悬空 JDK 路径（真 bug，已修）

**Symptom**：真机 PRoot 会话里 `which java` → MISSING；报告 §3.1 结论成立。
**Root Cause**：`UbuntuBootstrapScripts.kt:368`（旧）`proot_common_env` 把 PATH 写死
`/usr/lib/jvm/java-17-openjdk-arm64/bin`，但该目录在 rootfs **不存在**；真实 JDK 在
`/opt/jdk-17.0.19+10` 且未进 PATH。第363行 bind 用 `[ -e ... ]` 探测，但 PATH 不随探测变化。
**Fix**：新增 `aidev_resolve_jdk`（探测 `/opt/jdk-17*` → `/usr/lib/jvm/*`），PATH 与 bind 均用
真实 JDK；`.bashrc` 模板补 JDK 兜底（缺失时自动补 JAVA_HOME/PATH）。
**Prevention**：凡引用 JDK 路径一律动态探测，禁止写死目录；PATH 段与 bind 段必须同源。

### 2. aidev-deploy 安装结果"黑洞"（not verified）

**Symptom**：宿主 Shizuku 确认弹窗后真装成功，但 `aidev-deploy` 永远 `install not verified`。
**Root Cause**：deploy 第二步用 `aidev-shizuku exec "pm list packages"` 做二次校验，而该命令
经桥在 A 侧常拿不到输出（设备不可见）。`aidev-install` 经 `aidev-shizuku install` 的退出码
已是宿主侧 Shizuku 安装真相（桥会回传 pm install 结果），deploy 却没信它。
**Fix**：`aidev-deploy.sh` 以 `aidev-install` 退出码为 installed 真相；`pm list packages` 降级为
可选软校验，失败仅告警不致命。无需改 Kotlin 桥（`aidev-shizuku install` 已正确回传）。
**Prevention**：A 侧自验设备状态的命令不可作为安装成败的致命判据；信任桥侧命令的真实返回码。

### 3. 报告其余项归属判定（明确不做 / 文档化）

- **make/cmake/file/Go（P1-3/4/5）**：属 rootfs 预置层，受"不要 apt 装到系统根"铁律约束，
  由宿主打包阶段处理，本仓库不 `apt-get install`。
- **x86_64 NDK 加载器/qemu（P1-6/7）**：rootfs 环境层，不在本仓库；文档明确 A 侧不支持 NDK 原生编译。
- **aidev-apk-info Socket 转发（P0-2）**：脚本已有 unzip+strings 降级模式，转发改动大收益低，不做。
- **/workspace 预置 aidevX 的 target/ 带 immutable 属性删不掉（P2-12）**：经代码核查
  `chattr +i`/`immutable` **不在本仓库**（`setReadOnly` 仅用于脚本只读位，非 workspace）。
  该属性是宿主 rootfs 打包阶段所设，**本 git 仓库不可直接改**。建议：① 宿主打包阶段去掉
  `target/` 的 `+i`；② `aidev-clean` 增加 `chattr -i` 提权尝试；③ 文档说明该目录不可删。
  若后续 `lsattr` 实测定位到本仓库某脚本误设，再修。

### 4. 文档补齐

新增 `docs/non-android-guide.md`：《非安卓开发指南》——各语言运行时边界 + 宿主侧工具清单
（aapt2 完整解析 / NDK 原生编译 / anr 抓取 / deploy 结果) + JDK 路径坑说明。

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`（aidev_resolve_jdk + .bashrc 兜底）
- `app/src/main/assets/scripts/aidev-deploy.sh`（installed 以 aidev-install 退出码为真相）
- `docs/non-android-guide.md`（新增）
- `docs/error-journal.md`（本条目）


## 2026-07-18 - 脚手架项目全部编译失败：AGP 9 built-in Kotlin 与 kotlin.android 撞名

### Symptom

`create-compose-project`（及其兼容别名 `aidev-create-android-project`）生成的**任何**新项目
在宇宙B 编译都失败：

```
An exception occurred applying plugin request [id: 'org.jetbrains.kotlin.android']
> Failed to apply plugin 'org.jetbrains.kotlin.android'.
   > Cannot add extension with name 'kotlin', as there is an extension already registered with that name.
BUILD FAILED
```

`ToolChainTest` / `ChainTest` / `DebugTest` 等均如此。工具链测试报告一度将根因误判为
"三插件（kotlin.android + kotlin.plugin.compose）天生冲突、脚手架系统性缺陷"。

### Root Cause

**误判澄清**：三插件组合（`com.android.application` + `kotlin.android` +
`kotlin.plugin.compose`）是 Kotlin 2.0 官方标准写法，`kotlin.plugin.compose` 只注册
compose 编译器扩展，**不**注册 `kotlin` extension，与 `kotlin.android` 不冲突。宿主
aidev6 自身正是这套写法且能正常出 APK，直接证伪"三插件冲突"说。

**真正根因**：AGP 9.0 默认开启 built-in Kotlin，会**自动注册 `kotlin` extension**；此时再
apply `org.jetbrains.kotlin.android` 就撞名报错（Google Issue Tracker 438711106 / 官方
「迁移到内置 Kotlin」文档）。宿主 aidev6 能编译，是因为 `gradle.properties` 里有
`android.builtInKotlin=false`（+ `android.newDsl=false`）关掉了内置 Kotlin；而
`create-compose-project.sh` 生成的 `gradle.properties` **漏了这两行**，导致脚手架项目
built-in Kotlin 默认开启 → 撞名 → 100% 编译失败。

### Fix

- `create-compose-project.sh` 的 gradle.properties 生成块补入 `android.builtInKotlin=false`
  与 `android.newDsl=false`，与宿主保持一致（保留标准三插件写法，不删 kotlin.android）。
- `create-compose-project_test.sh` 新增产物级断言：实跑生成一个项目，校验其
  `gradle.properties` 含 `android.builtInKotlin=false`，堵住"只测参数、不测产物能否编译"的漏洞。

### Prevention

- **对照基准排查法**：脚手架产物出问题时，先与宿主 aidev6 自身的对应配置文件逐行对照
  （尤其 `gradle.properties` 的 AGP 开关），差异往往就是根因。
- 报错信息 `Cannot add extension with name 'kotlin'` 在 AGP 9 语境下几乎必为
  built-in Kotlin 撞名，**不是**插件本身冲突；两条正规出路：① 关 built-in Kotlin
  （本项目选此，改动最小）；② 删 `kotlin.android` 拥抱内置 Kotlin。
- 脚手架测试必须至少有一条"实跑生成 + 校验关键产物"的用例，纯参数校验测不出编译级缺陷。
- `aidev-create-android-project` 只是 `create-compose-project` 的 3 行兼容封装
  （`exec create-compose-project`），并非第二套脚手架；改脚手架只需改 create-compose-project。

### 追加缺陷（2026-07-18 二次闭环报告）：activity-compose 缺显式版本

第二份闭环测试报告另发现脚手架生成的 `app/build.gradle.kts` 中
`implementation("androidx.activity:activity-compose")` **无版本号**，构建报
`Could not find androidx.activity:activity-compose:.`。

根因：`androidx.activity:activity-compose` 属 **androidx.activity 组**，**不在
Compose BOM（仅约束 androidx.compose.* 组）管理范围**，因此 BOM 不会为它提供版本，
必须显式声明。宿主 aidev6 `app/build.gradle.kts:253` 正是显式写 `1.9.3`（再次印证
"对照宿主基准"排查法）。已在脚手架加 `ACTIVITY_COMPOSE_VERSION="1.9.3"` 并引用，
测试补断言 `activity-compose:1.9.3`。

注：该报告主张的"移除 kotlin.android + 删 kotlinOptions"是另一条修复路（拥抱 built-in
Kotlin）；本项目已选"关 built-in Kotlin 保留三插件"路，与宿主一致，故报告的问题 1、2
在本项目修复路径下不复现，无需再改；仅问题 3（activity-compose 版本）需独立修复。

### Related Files

- `app/src/main/assets/scripts/create-compose-project.sh`（gradle.properties 生成块 + activity-compose 版本）
- `app/src/main/assets/scripts/aidev-create-android-project.sh`（兼容别名，委托前者）
- `app/src/test/sh/create-compose-project_test.sh`（新增产物级断言：builtInKotlin + activity-compose 版本）
- 对照基准：`gradle.properties` 与 `app/build.gradle.kts:253`（宿主，含 builtInKotlin=false 与 activity-compose:1.9.3）

### Bonus（顺带修复的护栏失真）

排查中发现 `repo_guard_test.sh` 仍检查 `BuildBridgeService.kt` 的 JDK `aidev-repo decide`
分支，但该逻辑先前已重构迁至 `BuildEnvironmentSetup.kt`，护栏因此失真变红。已把断言目标
改指向 `BuildEnvironmentSetup.kt`，恢复护栏作用。教训：重构搬迁代码时，须同步更新
指名道姓检查文件路径的静态护栏测试。

## 2026-07-17 - aidev-ubuntu-core dispatch 漏传 "${@}" 导致命令参数全丢

### Symptom

`aidev-create-android-project MyApp com.example.myapp /workspace` 只打印用法，
不创建项目。`aidev-apk-info <apk>`、`aidev-gen`、`aidev-logcat` 等带参命令同样
收不到参数（脚本里 `$1`/`$2` 为空 → 判空分支触发）。

### Root Cause

`UbuntuBootstrapScripts.kt` 生成 `aidev-ubuntu-core` 时，case 分支里需要位置参数的命令
（create-android-project / gen / error-why / index / android-sh / clean / backup /
logcat / anr / tombstone / crash-why / dumpsys）调用 `run_ubuntu_command` 时**漏写
`"${@}"`**，只有 `install`/`deploy`/`setup-dev-env` 等少数几个带了。`aidev-ubuntu-core`
收到用户参数后没往下传，目标脚本自然看到空 `$1`。

### Fix

给所有带参 dispatch 补 `"${'$'}@"`；新增 `app/src/test/sh/ubuntu_core_dispatch_test.sh`
静态断言 Kotlin 源里每个带参命令的 dispatch 行都以 `"${'$'}@" ;;` 结尾，防止回归。

### Prevention

- `aidev-ubuntu-core` 是 Kotlin 生成的，不在 assets/scripts，shell 测试需直接 grep
  生成源（`UbuntuBootstrapScripts.kt`）才能拦住此类回归。
- 新增任何带参命令时，必须在 case 分支同步 `"${@}"`。

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt:530-549` (run_ubuntu_command dispatch)
- `app/src/test/sh/ubuntu_core_dispatch_test.sh`

## 2026-07-17 - 宇宙A(PRoot)内 create-compose-project 缺 JDK/模板

### Symptom

进 `aidev[A]`(宇宙A/PRoot) 后跑 `aidev-create-android-project ...` 报：
`java: not found` 且 `错误: 模板目录不存在: /root/.gradle/template-wrapper`。

### Root Cause

`create-compose-project.sh` 设计为在**宿主**(宇宙H)运行——依赖宿主 `/root` 的
`$HOME/.gradle/template-wrapper` 和 `/usr/lib/jvm/java-17-openjdk-arm64`。
但 PRoot(宇宙A) 的内视图是隔离的：`$HOME=/root` 指向 rootfs 自身的空 `/root`，
`AIDEV_HOME=/host-home`（bind 的宿主 AIDEV_HOME，里面没有 `.gradle`），
且 `proot_common_binds` 没挂宿主的 `/root/.gradle` 和 JDK，PATH 也没含 JDK bin。
所以脚本在 PRoot 内既找不到 `java` 也找不到模板。

### Fix

`UbuntuBootstrapScripts.kt`:
- `proot_common_binds` 增加 `-b /root/.gradle -b /usr/lib/jvm/java-17-openjdk-arm64`
  （把宿主 Gradle 模板+缓存、JDK 挂进 PRoot）。
- `proot_common_env` 的 PATH 前置 `/usr/lib/jvm/java-17-openjdk-arm64/bin`。
这样 `$HOME/.gradle/template-wrapper` 与 Gradle 缓存在宇宙A 内直接可用，`java` 命中。

### Prevention

- 创建项目可在宿主(宇宙H)或宇宙A 跑；若走 PRoot，必须保证 JDK + Gradle 模板/缓存
  已被 bind 进 PRoot 且 JDK 在 PATH。
- 新增依赖宿主资源的脚本时，检查 PRoot binds 是否已暴露对应路径。

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt:341-347` (proot_common_binds/env)
- `app/src/main/assets/scripts/create-compose-project.sh:34-35` (TEMPLATE_DIR/CACHE_PARENT_DIR)

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

## 2026-07-13 — 部署脚本执行路径用宿主绝对路径导致 PRoot 内「文件不存在」（#135, 提交 494a327）

### Symptom
面板点「安装并拉起 / 仅安装」后部署失败；用户在 PRoot 终端 `ls dev-env/bin` 能看到 `aidev-deploy`，但部署仍报文件不存在（脚本执行器找不到入口）。

### Root Cause
`DeployBridgeService` 组装执行命令时，`deployScript = File(PathConfig.aidevHome(ctx), "dev-env/bin/aidev-deploy").absolutePath`，即宿主绝对路径 `/data/user/0/com.aidev.six.dev/files/home/dev-env/bin/aidev-deploy`。而部署在 PRoot（`-r agent rootfs`）内执行，**仅 `/host-home`（→ files/home）被绑定**，宿主 `/data/...` 路径在 PRoot 视图中不可见 → `/bin/sh` 无法 exec 该脚本 → 报"文件不存在"。`validateDeployScript` 走 `/host-home`（v126 已改）与执行路径不一致，属同一类「校验/执行路径锚点不同」问题的后半段。

### Fix（#135, 提交 494a327）
- `deployScript` 改用 PRoot 视图路径 `/host-home/dev-env/bin/aidev-deploy`，与 `AIDEV_HOME=/host-home` 及 `validateDeployScript` 完全一致。
- 配套（#137）：`ensureDeployScripts` 以 bundled assets 为准，MD5 不一致即覆盖脚本并刷新 `.md5`，避免脚本更新后 `.md5` 过期误拦；`aidev-deploy` 的 `pm list packages` 校验改非致命、以能否启动为准；`aidev-shizuku` 请求 ID 加 `$RANDOM`。

### 验证
- 构建 #135/#137/#139 BUILD SUCCESSFUL；用户实测「安装拉起都正常了」。
- 真机复测部署 + `aidev-build-request` 返回值进行中。

### 关键教训
- **PRoot 内执行的任何路径都必须用 PRoot 绑定视图（`/host-home/...`、`/workspace/...`、`/sdcard/...`），绝不能用宿主 `ctx.filesDir` 绝对路径**——两者只在 `/host-home` 这个锚点重合，其余宿主路径在 guest 中不存在。
- 校验路径与执行路径必须用同一个锚点，单改其一（如只改 validateDeployScript）会留下隐性 bug。

## 2026-07-13 — material-icons-extended 离线拉不到（缺包编译失败）

### Symptom
在 AIDev 内置环境用 `Icon(Icons.Filled.Xxx)` 编译报 `Unresolved reference`；或在离线时新建 Compose 项目构建报 `Could not resolve androidx.compose.material:material-icons-extended`。

### Root Cause
- 离线优先 + 阿里云镜像需显式仓库声明；基线（旧 `create-compose-project` 用 BOM 2024.06.00）**未把 `material-icons-extended` 纳入默认依赖**，只有 `material-icons-core` 被缓存过，离线拉不到扩展图标库。
- 根因不是"环境硬封锁"，而是**离线优先 + 基线不全**的偶发状况。联网时阿里云镜像其实返回 200，可拉取。

### Fix
- `ScaffoldBaseline.kt` 把 `material-icons-extended` 纳入模板基线依赖；`create-compose-project` 与 `ProjectScaffoldState.generateScript()` 对齐（BOM 2024.12.01 + 图标库）。
- `aidev-precache` 预缓存基线依赖（含该库），离线自检确认可解析。

### 关键教训
- Compose 图标分 `core`（基础）/ `extended`（全量）；要画非常用图标必须显式依赖 `material-icons-extended`，且离线前必须预缓存。

## 2026-07-13 — aidev-precache 只预热宿主缓存，宇宙B断网仍缺包

### Symptom
`aidev-precache` 跑完后，宿主侧离线构建 OK，但宇宙 B（编译器 rootfs）断网编译仍报 `Could not resolve`。

### Root Cause
真正编译发生在宇宙 B，其 `GRADLE_USER_HOME=/host-home/gradle-cache`（宿主真实路径 `filesDir/home/gradle-cache`）；
而 `aidev-precache` 原先只把依赖缓存进宿主 `~/.gradle`，两套缓存不共享，宇宙 B 仓库里仍是空的。

### Fix
`aidev-precache` 自动探测 `filesDir/home/gradle-cache` 并**同步**基线缓存过去（先下到宿主，再 `cp` 到宇宙 B，避免双重拉取）；
支持 `--gradle-home <DIR>` / `--universe-b` 显式指定。离线自检覆盖两个落点。

### 关键教训
- "预热依赖"必须落进**真正构建用的那个 Gradle 缓存**，不能想当然用宿主默认缓存。

## 2026-07-13 — `./gradlew :app:testShellScripts` 任务不存在（文档过时）

### Symptom
按 AGENTS.md 跑 `./gradlew :app:testShellScripts` 报 `task 'testShellScripts' not found`。

### Root Cause
项目从未注册该 Gradle 任务；正确入口是 `bash app/src/test/sh/run.sh`（65 个 shell 测试，全过）。AGENTS.md 文档过时。

### Fix
AGENTS.md Testing 段改为 `bash app/src/test/sh/run.sh`，并加注勿用 `testShellScripts`。

### 关键教训
- 跑测试前先确认任务真实存在；文档里"应全过"的命令若报 task not found，多半是文档过时而非代码坏。

## 2026-07-14 — PRoot 内 bash 实际不可用，所有 `#!/bin/bash` 脚本被 dash 解析失败

### Symptom
真机 universe A（OpenCode 终端）跑 `aidev-bridge` / `aidev-shizuku` 等脚本报错：
- `aidev-bridge`: `cannot execute: required file not found`（shebang 写成 `#!/system/bin/sh`，PRoot Ubuntu 无此文件）。
- `aidev-shizuku exec ...`: `/host-home/ubuntu-rootfs/usr/local/bin/aidev-shizuku: 14: set: Illegal option -o pipefail`（dash 拒绝 bash 语法）。

### Root Cause
本 PRoot 的 `/bin/sh -> dash`，而 `/bin/bash` 在该环境下实际不可用：
探测显示 `bash --version` 报 `Exec format error`，说明 rootfs 里的 bash 是损坏/不兼容架构；
于是内核按 shebang 启动 bash 失败，回退到 `/bin/sh`(dash) 解析，dash 不认 `set -o pipefail`/`[[`/`<<<`/`read -a` 等 bashism。
结论：assets/scripts 下 **28 个 `#!/bin/bash` 脚本全部存在潜在故障**，只有 `#!/bin/sh` 脚本（aidev-build-request/build/notify 等）正常。

### Fix（本轮范围：桥接路径 5 个脚本转 POSIX sh）
- `aidev-bridge.sh`: shebang `#!/system/bin/sh` → `#!/bin/sh`；`$RANDOM` → `$(date +%s%N)`。
- `aidev-shizuku.sh`: shebang `#!/bin/sh`；`set -eo pipefail` → `set -e`；`$'\n'` → 字面换行；`$RANDOM` → `date +%s%N`。
- `aidev-deploy.sh`: shebang `#!/bin/sh`；`set -uo pipefail` → `set -u`；`[[ =~ ]]` → `grep -E`。
- `aidev-install.sh`: shebang `#!/bin/sh`；`set -eo pipefail` → `set -e`；`[[ =~ ]]` 与 `[[ != && ]]` → `grep -E` / `case`。
- `aidev-logcat.sh`: shebang `#!/bin/sh`；`set -eo pipefail` → `set -e`；`IFS=',' read -ra ... <<<` → `IFS=','; for f in $TAGS; do ...; done`。
- 全部通过 `dash -n` 语法检查。部署无需改 Kotlin（`copyAssetScripts` 每次启动重拷）。

### 已知遗留（不在本轮）
其余 23 个 `#!/bin/bash` 脚本（aid-boot/aid-run/aid-sh/auto-adb/linux-enable/local-install/python-share-wizard/record-screen/screenshot/server/set-ime/setup-*/shizuku-bridge-test/storage-permission/ubuntu-core/ubuntu-install/windows-*/xdg-open/xfce 等）仍依赖 bash，真机执行会踩同样坑。后续按需逐个转 POSIX sh。

### 关键教训
- 改码后必须**强制停止并重启 AIDev** 才能部署新脚本：`copyAssetScripts` 只在 App 启动时跑，仅安装 APK 不重启进程不会更新 `/host-home/ubuntu-rootfs/usr/local/bin/` 下的脚本。
- PRoot 环境优先用 `#!/bin/sh` + POSIX 写法；bashism（`pipefail`/`[[`/`<<<`/`read -a`/`$RANDOM`）一律视为不可用。

## 2026-07-14 — `aidev-bridge` 函数前向定义在 dash 下报 command not found，导致 Socket 静默回退文件通道

### Symptom
`aidev-bridge status` 显示 ONLINE，但 `aidev-build-request` 仍打印「Socket 不可用，已回退文件通道提交」，
且 `aidev-bridge send build/shizuku` 在构建主机实测报：
`aidev-bridge.sh: line 27: send_via_tcp: command not found` / `send_via_file: command not found`。

### Root Cause
`aidev-bridge.sh` 把 `send_via_tcp` / `send_via_file` 两个函数定义在文件末尾、`case` 调用之后。
bash 在解析期注册函数（前向调用可用），但本 PRoot 的 `/bin/sh` 是 **dash**，函数须在执行到定义行后才注册；
`case` 先于定义执行 → 调用时函数尚不存在 → `command not found`（返回 127）→ `if send_via_tcp` 为假 → 回退 `send_via_file`
（同样失败）→ ACK 为空 → 上层脚本判定「Socket 不可用」。错误经 `2>/dev/null` 被吞，表现成静默回退。

### Fix
将 `send_via_tcp` / `send_via_file` 定义移到 `case` 之前（函数先定义后调用）；并在 socket 失败时向 stderr 打印提示。
复测（直连本机运行中的 AIDev 桥，端口 14096 已监听）：`send build`→`accepted`、`send shizuku`→命令输出，均走 Socket 成功。

### 关键教训
- dash 不像 bash 那样提升函数前向声明；POSIX sh 脚本必须「先定义后调用」，不能依赖 bash 行为。
- 客户端 `2>/dev/null` 会吞掉真实错误，排查时先去掉或改打 stderr。

## 2026-07-14 — 缺少用户态 notify 命令（`aidev-build-request notify` 是误用）

### Symptom
用户跑 `aidev-build-request notify "测试"` 期望发通知，实际被当成构建请求（project=测试）并构建失败。

### Root Cause
`aidev-build-request` 仅有构建请求语义、无 `notify` 子命令；`notify` 落入 `*` 分支变成 project 名。
通知桥（NotifyBridgeService）虽已实现，但**没有对应的用户态脚本**，只能经 `aidev-bridge send notify` 调。

### Fix
新增 `aidev-notify.sh`（POSIX sh，Socket 主用 + 文件兜底），已登记进 `UbuntuBootstrapScripts.copyAssetScripts` 部署清单；
用法 `aidev-notify [-t 标题] [-p low|default|high|max] "消息"`。正确验证命令应为 `aidev-notify "测试"`。

## 2026-07-14 — LeakCanary 2.x 的 `leakcanary-android` 是空 stub，`LeakAssertions` 不可用

### Symptom

P1-6 引入 `com.squareup.leakcanary:leakcanary-android:2.12` 后，仪表化测试 `ShellActivityTest` 引用 `com.squareup.leakcanary.LeakAssertions.assertNoLeaks()` 编译报 `Unresolved reference 'LeakAssertions'`。

### Root Cause

LeakCanary 2.x 把 `leakcanary-android` 拆成**薄壳** AAR（mavenCentral 与 aliyun 镜像均返回 4240 字节，含空 `classes.jar`），真实类落在 `leakcanary-android-core`（546KB，包名 `leakcanary`，`ContentProvider`/`HeapDumpControl` 等在其中）。`leakcanary-android-core` 内仅 `leakcanary/internal/HeapDumpControl$hasLeakAssertionsClass$2` 通过 `Class.forName("com.squareup.leakcanary.LeakAssertions")` **反射探测**该类；该断言类在发布的 2.x 构件中并不随附，故 `import`/`assertNoLeaks()` 不可用。

### Fix

- 保留 `debugImplementation leakcanary-android:2.12`（自动经 ContentProvider 安装并监视泄漏），**移除显式 `LeakAssertions` 调用**；仪表化测试退化为生命周期骨架（启动/销毁 + Tab 可见性），依赖 LeakCanary 自动监视。
- 依赖解析：`settings.gradle.kts` 在 aliyun `central` 前加 `mavenCentral()` 兜底（aliyun 偶发返回损坏构件时回退；本例两镜像均一致返回该 stub，故非镜像问题，是 2.x 发布结构本身）。

### 关键教训

- 2.x 的 `LeakAssertions.assertNoLeaks()` 已不在发布构件中，仪表化测试不要依赖它；改用 LeakCanary 自动检测 + 通知/堆分析。
- 引入第三方依赖前先确认其发布结构与包名（薄壳 AAR + core 分离是常见模式）。

### 相关文件

- `app/build.gradle.kts`、`settings.gradle.kts`、`app/src/androidTest/java/com/aidev/six/ShellActivityTest.kt`

---

## 2026-07-14（续）— 剩余 25 个 #!/bin/bash 脚本已全部转 POSIX sh（完成）

- 上一节遗留的 23+ 脚本（实际 25 个 `#!/bin/bash` + 2 个误用 `#!/system/bin/sh` 的 aidev-precache/aidev-repo）现已全部转换。
- 处理模式：`#!/bin/sh` 替换；剥 `pipefail`/`-E`、`&>`、`[[ =~ ]]`、bash 数组（`( )`/`${arr[@]}`→空格分隔字符串+未引号展开）、进程替换 `<(...)`/`>(tee)`（dev-backup/restore-dev-env 的 `exec > >(tee)` → 直接去掉 tee 落地；aidev-index/aidev-build 的 `< <()` → 临时文件重定向）。
- 全部 `dash -n` 通过；shell 测试 76 passed/0 failed。APK b162 已交付，需强制停止+重启 AIDev 部署。
- 现状：assets/scripts 下 38 个脚本**全部 `#!/bin/sh` 且 dash 兼容**，PRoot bash 不可用隐患已彻底消除。

---

## Android 开发经验（通用教训）

> 原记录见根 `ANDROID_DEV_EXPERIENCE.md`（已并入本文件）。

### 不要用 FileObserver —— 用协程轮询

- **场景**：监控目录中新出现的文件，处理跨进程请求（如 Ubuntu PRoot → Android 通知）。
- **教训**：`FileObserver` 依赖 Linux `inotify`，而 Android 新设备多用 F2FS 文件系统，`inotify` 在 F2FS 上行为不一致，事件可能根本不触发。调试极难——无错误、无日志、静默失败。
- **替代方案**：`CoroutineScope(Dispatchers.IO)` 内 `while (isActive) { dir.listFiles()?.filter { it.name.startsWith("req-") }?.forEach { ...; file.delete() }; delay(500) }`。每 500ms 轮询，不依赖内核、100% 可靠、无线程安全问题。

### 异步初始化坑：Handler.post 还没执行就开始使用

- **场景**：`homeDir` 在 `ensureSession()` 的 `Handler(Looper.getMainLooper()).post { ... }` 中赋值，但调用方在同一帧直接读取。
- **教训**：`Handler.post` 回调不会在当前帧执行。后续依赖其赋值结果的代码必须加重试机制。
- **模式**：`val dep = dependency ?: run { postDelayed(3000) { initXxx(ctx) }; return }; // 使用 dep`。

### 两点总结

1. Android 上永远别用 FileObserver，用轮询。
2. 异步赋值的字段，永远加 null→重试兜底，不要假设调用时已赋值。

---

## 2026-07-17 — `aidev-install` 静默安装三连崩：dash 兼容 + 通道错位 + 误删 local（已闭环）

### 事实链（按用户实测顺序）

1. `aidev-install app-debug.apk` 首错：`/usr/local/bin/aidev-install: 4: set: Illegal option -o pipefail`。
   → 脚本 shebang `#!/bin/sh`(dash) 却用了 bash 专有 `set -euo pipefail`。脚本在解析期即被 dash 拒绝，根本没执行任何逻辑。
2. 修掉 `set -o pipefail`/`set -euo pipefail`（全 assets/scripts 28 个脚本批量改 `set -e`）后，脚本能跑，但 `aidev-shizuku status` 报 `136: local: not in a function`。
   → `status` 分支在 `case` 顶层（非函数）用了 `local out`；dash 不允许函数外用 `local`。顺带扫出 `aidev-install.sh` 另有 6 处函数外 `local`。
3. 修完 local 后 `aidev-shizuku status` 显示「Shizuku 状态: 正常」，但 `aidev-install` 仍报「Shizuku 未响应/未授权」。
   → **矛盾点**：`aidev-shizuku status` 走 **socket** 通道（`aidev-bridge send` → 127.0.0.1:14096，已验证 ONLINE），成功；而 `aidev-install` 的 `shizuku_heartbeat()` 只用**文件通道**（`hb_*` 请求文件等 `poll()` 回写），该环境文件通道不回写 → 探测失败。同源桥、两条通道、一成一败。
4. 把 `shizuku_heartbeat` 改为优先 `aidev-shizuku status`（socket），文件通道降级兜底；并让 `aidev-shizuku status` 桥不通时 `exit 1`（原始终 `exit 0`，无法被 heartbeat 区分）。重部署 rootfs 后 `aidev-install` 走到 `→ 静默安装 (Shizuku)...` 但崩：`125: out: not found`。
   → **第三步引入的回归**：早前批量「去掉函数外 local」的 sed 把 `install_silent` 里的 `local out rc` 误改成裸词 `out rc`，dash 当成执行 `out` 命令 → `out: not found`，且因 `set -e` 直接退出。此损坏一路带进 b241。
5. 修 `install_silent` 第 125/136 行（`out=""`/`rc=0`/`reason=""`），构建 b242，重部署 rootfs，`aidev-install app-debug.apk` **安装成功**。

### Root Cause（总）

- PRoot 的 `/bin/sh` 是 **dash**，不认 `set -o pipefail`、不允许函数外 `local`；这些 bashism 在解析/执行期直接崩。
- `aidev-install` 与 `aidev-shizuku` 探测走**不同通道**（文件 vs socket），在文件通道不通的环境里表现不一致，误导排查方向。
- 修复过程中用 `sed` 批量去 `local` 时**未区分函数内/外**，把函数内合法 `local out rc` 误改成裸词 `out rc`，引入新崩点且沉默多轮。

### Fix（assets/scripts，纯脚本层，无需改 Kotlin）

- `aidev-install.sh` / `aidev-shizuku.sh` / `aidev-bridge.sh` 及全部 28 个脚本：`set -[a-zA-Z]*o pipefail` → `set -e`。
- `aidev-install.sh`：6 处函数外 `local` 去掉；`shizuku_heartbeat()` 改为 socket 主用 + 文件兜底；`install_silent()` 的 `out rc`/`reason` 裸词改为 `out=""`/`rc=0`/`reason=""`。
- `aidev-shizuku.sh`：`status` 分支 `local out`→`out=""`；桥不通时 `exit 1`（原 `exit 0`）。
- 全部 `dash -n` 通过。部署靠 `UbuntuBootstrapScripts.copyAssetScripts`（每次 `ensureSession` 覆盖写 rootfs `/usr/local/bin`），重部署 rootfs 或重开终端即生效，无需改 Kotlin。
- 验证：b242 `assembleDebug` 成功；真机 `aidev-install` 静默安装 95M APK 成功。

### 关键教训（本条最贵）

1. **脚本统一 dash 基线**：assets/scripts 全部 `#!/bin/sh` 且只用 POSIX 写法；`pipefail`/`[[`/`<<<`/`read -a`/`$RANDOM`/`函数外 local` 一律禁用。`dash -n` 必须作为脚本改动的强制校验门槛。
2. **禁止用 `sed` 批量改 shell 语法**：本案 `sed 's/^ *local /.../'` 把函数内合法 `local` 误伤成裸词，且多轮才暴露。结构性修改（去 local、改 set）应直接用编辑工具精确改，或至少用 `dash -n` + 函数位置校验兜底。
3. **同类命令必须走同一通道**：`aidev-install` 的探测与 `aidev-shizuku status` 本质同义，应复用同一实现（socket），不要自己另写一套文件心跳，否则出现「status 说正常 / install 说不通」的分裂现象。
4. **退出码要区分成功/失败**：状态类命令（`aidev-shizuku status`）桥不通时必须 `exit 1`，否则调用方无法用 `if cmd; then` 判断。
5. **先读代码定位矛盾点再动手**：用户给的「status 正常但 install 不通」是核心线索，直接指向通道不一致，而非继续在 Shizuku 授权上打转。
6. **部署验证链**：`copyAssetScripts` 每次启动覆盖 rootfs 脚本；改完 assets 必须重部署 rootfs（或重开终端）才能看到效果，仅装 APK 不重启进程不会更新。

---

## 2026-07-17（续）— 出厂脚本防护模型落地：dash 测试基线 + 只读 + 用户覆盖层

### 用户诉求与死锁
用户指出：「出厂命令不允许修改」的前提是「出厂脚本百分百零 bug」，但现实是 `aidev-install` 修了多轮仍有 bug，其它脚本也藏雷。若出厂脚本只读且不可改，一旦带 bug 就成死锁（用户无自救通道）。

### 关键工程约束
PRoot 以 `-0`（root）启动（`ProotLauncher.kt:42`），终端内是 root。**root 下 `chmod 555`/`setReadOnly()` 挡不住 root 写入**，故「不可修改」无法靠文件系统权限硬实现，必须靠**约定 + 覆盖层优先 + 版本恢复**等效达成。

### 测试基线错配（根因级）
原 shell 测试用 `bash -n` 校验语法（`helpers.sh:57`），而真机 `/bin/sh` 是 dash。`bash -n` 对 `set -o pipefail`、函数外 `local`、裸词赋值**全部放行** → 本案三类 bug 在测试下全绿、真机全崩。测试在为错误 shell 背书。

### Fix（本轮，跨 assets/scripts + 测试 + Kotlin 部署）
1. **测试基线对齐 dash**（`app/src/test/sh/lib/helpers.sh`）：`assert_syntax_ok` 改用 `dash -n`（无 dash 退 `sh -n`）；新增 `assert_dash_compatible` 静态规则，awk 状态机（跳过引号/heredoc 内 `{}`）精确追踪函数深度，拦 **函数外 `local`** 与 **`pipefail`**（`dash -n` 抓不到这两类，因是选项/运行时错误非结构错误）。`syntax_test.sh` 全脚本调 `assert_dash_compatible`；`aidev-shizuku_test.sh` 改用 `dash` 实跑。
   - **附带抓出真 bug**：`setup-dev-env.sh` 顶层 `if` 内 `local tmp_zip`/`local tmp_dir`（函数外 `local`，dash 下必崩）→ 已改为普通赋值。此前多轮扫描均漏，dash 基线首次命中。
2. **用户覆盖层**（`TerminalShellAssets.kt` + `UbuntuBootstrapScripts.kt`）：`.aidevrc` 新增 `AIDEV_OVERRIDES="$AIDEV_HOME/overrides/bin"`，PATH 最前插入；`run_ubuntu_command` 对命令提取 basename，若 `$AIDEV_OVERRIDES/<base>` 存在且可执行则优先（一处覆盖全部 26 命令）。用户自定义命令放此目录即生效，且重开终端不被冲掉。
3. **出厂脚本只读 + 版本门控**（`UbuntuBootstrapScripts.copyAssetScripts`）：加 `home` 参数；建 `home/overrides/bin`（可写）；用 `home/.script-deploy-code` 版本标记**仅版本升级时**刷新 rootfs 出厂脚本（平时不重写，避免冲掉约定）；复制后 `dst.setReadOnly()`（语义只读标记）。不触碰 overrides 目录。

### 验证
- `bash app/src/test/sh/run.sh`：syntax 58 passed 0 failed（顶层 local/pipefail 全拦）；`aidev-shizuku` 用 dash 实跑通过。（`repo_guard` 2 失败为历史无关项，未动。）
- 全脚本 `dash -n` + 顶层 local 静态扫描：0 FAIL。
- 预期真机冒烟：重部署后 `aidev-shizuku status` 正常；放 `overrides/bin/aidev-install` 打印 `OVERRIDE` → 终端敲 `aidev-install` 命中覆盖层；删除后回落出厂版。

### 关键教训
1. **测试基线必须 = 真机基线**：shell 语法校验用真机同款 shell（dash），否则测试在替错误 shell 背书。本案 `bash -n` 是根因级错配。
2. **`-0` root 下「只读」非硬挡**：防篡改靠覆盖层优先 + 版本恢复，而非 chmod。文档与代码必须明示此约定。
3. **静态规则要精不要宽**：裸词误赋值（`out rc`）与函数调用（`detect "a"`）静态无法区分，硬扫会误报阻断 CI；改由专项测试用 dash 实跑暴露。精准的「函数外 local / pipefail」规则零误报且高价值。
4. **dash 基线顺带挖出旧雷**：`setup-dev-env.sh` 函数外 local 在多轮人工/批量扫描中漏网，dash 静态规则首次自动命中——证明基线对齐能持续提升存量脚本质量。
5. **逃生通道与只读不矛盾**：出厂脚本只读（防智能体篡改安装/部署逻辑）+ 用户覆盖层（用户/智能体在可写区自救），是「防篡改 + 不死锁」的唯一折中。
