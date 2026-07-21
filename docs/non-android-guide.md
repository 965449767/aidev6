# 非安卓开发指南（终端环境（PRoot）能力边界）

> 适用：在 AIDev 终端（PRoot Ubuntu 24.04 aarch64）内做安卓以外的开发。
> 核心约束：编译隔离——安卓 APK 由编译环境编译；终端侧只做编辑/提交，**原生交叉编译受限**。

## 一、语言运行时可用性（实测）

| 语言 | 工具 | 状态 | 边界说明 |
|------|------|------|----------|
| Python | python3 3.12 / pip3 | ✅ 可用 | 缺 pipx/uv/poetry/virtualenv，纯脚本与标准库没问题 |
| Node | node v18 / npm 9 | ✅ 可用 | 前端/CLI 可直接跑 |
| Rust | rustc/cargo 1.97 | ✅ 可用 | 已装 4 个 Android target；**但 NDK 链接器为 x86_64 预编译，终端无法跑**（见 §三），仅 host target 可编 |
| C/C++ | gcc 13.3 | ⚠️ 部分 | `make`/`cmake`/`file` **未预装**（rootfs 打包层，非本仓库范围）；单文件 gcc 可编，多文件工程不便 |
| Go | — | ❌ 未预置 | 需自行 `apt-get install`（受 rootfs 约束，建议宿主侧处理） |
| Java | JDK 17 | ✅ 已修 | 真实 JDK 在 `/opt/jdk-17.0.19+10`；PRoot 启动 env 现动态探测注入 PATH，`aidev-apk-info`/`jarsigner` 等可用 |

## 二、JDK 路径（历史坑，已修）

- 旧版 PRoot 启动 env 把 `PATH` 写死为 `/usr/lib/jvm/java-17-openjdk-arm64/bin`，但该目录**不存在**（悬空路径）→ `java` 永远找不到。
- 现 `UbuntuBootstrapScripts.kt` 的 `aidev_resolve_jdk` 动态探测 `/opt/jdk-17*` → 真实 JDK；`.bashrc` 模板另有兜底，缺失时自动补 `JAVA_HOME`/`PATH`。
- 若仍遇到 `which java` 缺失：确认 rootfs 内 `/opt/jdk-17*` 存在；或手动 `export JAVA_HOME=/opt/jdk-17.0.19+10; export PATH=$JAVA_HOME/bin:$PATH`。

## 三、明确「只能在编译环境 / 宿主侧用的能力」

以下因架构分工或环境限制，**终端侧不可用或结果不可验证**，请勿在终端误用：

1. **aapt2 完整解析**：终端环境本地无 `/Android` 的 aapt2，`aidev-apk-info` 自动降级为 unzip+strings 基础模式（仅提取包名等少量信息）。完整解析（权限列表/minSdk/targetSdk 等）需在宿主侧或编译环境。
2. **NDK 原生交叉编译**：NDK（含 clang/cmake）为 linux-x86_64 预编译，rootfs 是 aarch64 且缺 x86_64 加载器 → `clang`/`cmake` 无法执行。原生模块（JNI/Rust-Android）请在编译环境或交给宿主。
3. **anr / 部分 crash 抓取**：受设备安全策略约束（如 `cat /data/anr/*` 被拒），原始命令可能拿不到。以宿主侧 `aidev-doctor` / 日志桥转发为准。
4. **deploy 安装结果**：安装本身经宿主 Shizuku 真装成功（弹窗为 Shizuku 授权，确认即装）；终端 `pm list packages` 软校验常因设备不可见拿不到输出，故 `aidev-deploy` 现已**以 `aidev-install` 退出码为安装真相**，软校验失败仅告警不致命。

## 四、推荐工作流

- Python/Node/Rust(host)/单文件 C：直接在 终端编辑+运行。
- 安卓 APK：终端 `aidev-create-android-project` 生成 → `aidev-build-request` 提交编译 → 产物回传终端。
- 原生/JNI/Rust-Android：交编译环境或在宿主侧用 aarch64 NDK 编译。
- 安装自测：终端 `aidev-deploy --apk <apk> --pkg <pkg>`，结果以 JSON 回传（installed 反映宿主 Shizuku 真实结果）。

## 五、终端使用注意

- **方向键历史**：交互 shell 已启用 mksh 行编辑 + 历史（`HISTFILE=~/.aidev_sh_history`）。上下方向键浏览历史；若偶发显示 `[A]`/`[B]` 等转义字符，说明历史文件异常或会话非交互，重启终端即可。
- **持续日志不要占用交互终端**：`aidev-logcat --follow` / `logcatx` 会把日志持续刷到当前 PTY，与正在输入的命令行交叉，导致回显错乱。需要持续监听某应用日志时，**优先用 AIDev 项目页「运行」面板的隔离 logcat**（Compose 渲染，不污染终端 PTY）；终端里跑 `--follow` 时记得 `Ctrl+C` 停止。
