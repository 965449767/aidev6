# aidev6 实机初始化操作手册（闭环验证前置）

> 目标：让新装的 aidev6（`com.aidev.six.dev`）具备跑通**人类驱动构建闭环**的环境（终端 + 宇宙B 编译 + 可选 OpenCode 写码，无自动改码）。
> 适用：在手机上打开 aidev6，按本手册初始化；**全部为设备侧手动操作，agent 无法代做**。
> 架构基准（代码写死，见 `PathConfig.kt` / `BuildBridgeService.kt`）：
> - `aidevHome` = `/data/data/com.aidev.six.dev/files/home`
> - 宇宙A（OpenCode 宿主）= `home/ubuntu-rootfs`
> - 宇宙B（编译器）= `home/compiler_rootfs`（独立于 ubuntu-rootfs）
> - 共享工作区 = `home/workspace`（两边都 bind 进各自 `/workspace`）
> - 编译时 `ANDROID_SDK_ROOT=/host-home/android-sdk`、`GRADLE_USER_HOME=/host-home/gradle-cache`

---

## 一、必须就绪的 4 件事

| # | 组件 | 位置 | 怎么来 | 是否自动 |
|---|------|------|--------|----------|
| 1 | 宇宙A rootfs（含 OpenCode） | `home/ubuntu-rootfs` + `.aidev-rootfs-ready` | 进终端首次下载 ubuntu-base 24.04（清华镜像），再 `setup-dev-env`（核心层）+ `install-aitool` | 半自动 |
| 2 | 宇宙B 编译器 rootfs（JDK17+gradle） | `home/compiler_rootfs` + `.aidev-rootfs-ready` | 首次提交构建请求时 `BuildBridgeService.ensureCompilerRootfs` 自动装（≤600s×2，静默） | 自动 |
| 3 | Android SDK（宇宙A/B 共享） | `home/android-sdk`（宇宙A 内 = `/host-home/android-sdk`） | **无自动下载**，宇宙A 内 `setup-dev-env --android` 一键装（见步骤 3） | 手动 |
| 4 | 工作区项目 | `home/workspace/MyAndroidProject` | 缺 `gradlew` 时 `BuildBridgeService.scaffoldProject` 自动建模板（**已含 mipmap 图标，H15 bug 已修**） | 自动 |

> 结论：**你只需手动做"进终端初始化宇宙A" + "确认 Android SDK 在位"**；宇宙B 编译器与项目模板在第一次提交构建请求时会自动补齐（但耗时长、无进度条，属已知长尾 B8）。

---

## 二、手机端分步操作

### 步骤 0 — 打开 aidev6 并保活
1. 打开 **aidev6**（包名 `com.aidev.six.dev`，就是刚静默装好的那个；versionCode 随构建自增，当前 ≥20）。
2. 若弹出 Shizuku 授权 → 授予。
3. 设置 → 应用 → aidev6 → 电池 → 设为**不受限制**（避免 HyperOS 杀进程打断长编译）。

### 步骤 1 — 初始化宇宙A rootfs（进终端）
1. 在 aidev6 内打开**终端**（Tab）。
2. 首次进入会触发 ubuntu-base 24.04 下载解包（清华镜像，几十 MB，需联网）。
3. 等终端可用（出现 shell 提示符）。此时 `home/ubuntu-rootfs/.aidev-rootfs-ready` 已生成。

### 步骤 2 — 安装开发工具
在终端执行：
```sh
setup-dev-env          # 默认只装核心层：node/python3/git/npm + 基础工具（~350MB）
check-dev-env          # 复检
```
> `setup-dev-env` 为**分层按需**（`-h` 查看）：默认核心层**不含** JDK/Android SDK/NDK/Rust。
> - `--build`：build-essential/cmake/ninja  ·  `--android`：SDK + headless JDK  ·  `--ndk`：NDK 27  ·  `--rust`：Rust 全家桶  ·  `--all`：全部
> Android SDK 供编译用，见步骤 3。

### 步骤 3 — Android SDK 就位（供宇宙B 编译，关键）
`BuildBridgeService` 编译时硬编码 `ANDROID_SDK_ROOT=/host-home/android-sdk`，**无自动下载**，必须预置。
该目录（宇宙A 内 = `/host-home/android-sdk`，即 `home/android-sdk`）由**宇宙A 与宇宙B 共享**：在宇宙A 内装好，宇宙B 编译时直接读取。
按本仓库 `build.gradle.kts`：`compileSdk=36`、`buildToolsVersion=36.1.0`、`targetSdk=36`；另因 36.1.0 的 aapt2 在 qemu 下损坏，编译用 `-Pandroid.aapt2FromMavenOverride=.../build-tools/34.0.0/aapt2`，故还需 `build-tools;34.0.0`。

**3.0 首选：一条命令搞定（推荐）**
```sh
setup-dev-env --android
```
> 它会装 headless JDK + cmdline-tools + platform-tools + `platforms;android-36` + `build-tools;36.1.0` + `build-tools;34.0.0` 到 `/host-home/android-sdk`。
> 完成后直接跳到 **3.4 校验**。下面 3.1–3.3 是手动等价步骤（`--android` 失败时的排障备选）。

**3.1 先确认 JDK 在（`setup-dev-env --android` 已装 headless JDK）：**
```sh
java -version          # 应看到 openjdk 17
```

**3.2 装 cmdline-tools（含 sdkmanager），用清华镜像：**
```sh
export ANDROID_SDK_ROOT=/host-home/android-sdk
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
cd /tmp
command -v unzip >/dev/null 2>&1 || apt-get install -y -qq unzip   # 缺 unzip 先装
curl -fL -o cmdtools.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip  # 腾讯云镜像；失败可换 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdtools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
```
> 若版本号 `11076708` 失效，到 `https://mirrors.tuna.tsinghua.edu.cn/android/repository/` 查最新 `commandlinetools-linux-*.zip`，或换官方 `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`。

**3.3 用镜像装 SDK 包：**
```sh
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  --proxy=http --proxy-host=mirrors.tuna.tsinghua.edu.cn --proxy-port=80 \
  "platforms;android-36" "build-tools;36.1.0" "platform-tools"
# 接受协议
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --proxy=http --proxy-host=mirrors.tuna.tsinghua.edu.cn --proxy-port=80 "platforms;android-36"
```

**3.4 校验：**
```sh
ls "$ANDROID_SDK_ROOT/build-tools/36.1.0/aapt2"   # 应存在
ls "$ANDROID_SDK_ROOT/build-tools/34.0.0/aapt2"   # aapt2 override 用，应存在
ls "$ANDROID_SDK_ROOT/platforms/android-36"        # 应存在
```
> 若网络走不通镜像，可改用 `REPO_OS_OVERRIDE=linux` + 官方 `dl.google.com`；或参见第四节风险 1 的 (a)/(c) 备选。
> 装完即具备编译条件；编译本身还会联网拉 Gradle 分发（gradle 8.14.5）与阿里云 Maven 依赖（见 `settings.gradle.kts`）。

### 步骤 4 — 提交第一次构建请求（触发宇宙B 自动初始化）
1. 底部**上滑**进「服务器中心」。
2. 点 **提交构建请求**（项目默认 `MyAndroidProject`）。
3. 盯着任务流看 4 阶段：
   - 准备宇宙B（首次会静默装 compiler_rootfs + JDK17，最长 ~20 分钟，UI 可能长时间无进展 → 耐心）
   - 编译（`./gradlew assembleDebug`）
   - 安装（`pm install -r -d`）
   - 拉起（`am start`）
4. 全绿 = 宇宙B 闭环打通。失败看任务流日志（实时写 `home/.aidev-build-bridge/logs/build-<id>.log`）。

---

## 三、自进化（宇宙A 改码）的前置拓扑（务必注意）

> 本手册描述的早期"自我进化闭环"（`崩溃 → 自动改码`）已在 2026-07-17 重构中移除：**不再有任何自动改码守护**。
> OpenCode 仅作为**人类驱动的写代码工具**——你可以在 aidev6 终端（宇宙A rootfs）内自行 `opencode serve` 并用它改码，但改码与构建完全由你掌控，宿主不会自动调用 OpenCode 做任何事。
> 构建统一用 `aidev-build-request --project /workspace/<应用名>`（App「编译」按钮等价），失败日志在 `logs/<项目>/last-build-failure.log`，由你人工排查。

---

## 四、风险与未决项（真机实测时盯紧）

1. **Android SDK 无自动安装**（最高优先）：`BuildBridgeService` 硬编码 `ANDROID_SDK_ROOT=/host-home/android-sdk`，无下载逻辑。aidev6 全新安装时该目录大概率为空。
   - **首选**：宇宙A 内跑 `setup-dev-env --android`（一键装 cmdline-tools + `platforms;android-36` + `build-tools;36.1.0` + `build-tools;34.0.0` + `platform-tools` + headless JDK 到共享的 `/host-home/android-sdk`）。
   - 手动等价：见第二节步骤 3.1–3.3（清华镜像）。
   - 备选：
     a. 从同机的 `com.aidev.five.dev` 复制其 `home/android-sdk` / `home/gradle-cache` 到 six.dev 的同路径（更快，参考 `dev-backup.sh`）；
     c. 改 `BuildBridgeService` 编译 env 指向设备已有 SDK（如 `/Android`，需给 PRoot 加 `-b /Android` 绑定）。
2. **宇宙B 首次静默无进度**（长尾 B8）：最长 ~20 分钟无任何 UI 反馈，易被误判卡死。耐心等，或看 `home/.aidev-build-bridge/logs/`。
3. **守护/OpenCode 拓扑错位**：见第三节，必须从 aidev6 沙箱内起，不能外置。
4. **桥接被杀**：长编译期间若 Shizuku/HyperOS 中断，重连后重试。

---

## 五、与 current-task.md 的对应
- 本手册解决 **C 组（首启保活）+ 宇宙A/B 环境就绪** 的手动部分。
- **D 组（人工提交构建看 4 阶段）** = 本手册步骤 4。
- **E/F 组（OpenCode serve + 守护 + 自治开关）** 必须在 aidev6 沙箱内按第三节执行，不能直接复用 agent 环境的进程。
- 完成上述并真机跑通一次完整闭环后，再回写 current-task.md 的 H14–H17 / A–G。
