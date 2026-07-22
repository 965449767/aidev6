# AIDev Android 开发环境

## 构建系统

| 组件 | 版本 |
|---|---|
| Gradle | 9.1.0 |
| AGP | 9.0.1 |
| Kotlin | 2.0.21 |
| JDK | 17 (ARM64) |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |

## 构建命令

```sh
# 标准 Debug 构建
./gradlew :app:assembleDebug

# Kotlin 编译检查
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# 指定 JDK
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug
```

### 产物

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 自动复制到 `/sdcard/AIDev/app-debug.apk`

## 项目结构

```
/workspace/<项目名>/
├── app/
│   ├── build.gradle.kts          # 模块构建配置
│   └── src/main/
│       ├── java/                 # Kotlin/Java 源码
│       ├── res/                  # 资源文件
│       └── AndroidManifest.xml
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
└── gradle/wrapper/               # Gradle 包装器
```

## 环境变量

- `AIDEV_HOME` — 宿主 home 目录（`/host-home`）
- `AIDEV_BIN` — 工具脚本目录（`/host-home/dev-env/bin`）
- `AIDEV_ROOTFS` — PRoot Ubuntu 根文件系统
- `AIDEV_WORKSPACE` — 工作区（`/host-home/workspace`，PRoot 内为 `/workspace`）
- `ANDROID_SDK_ROOT` — Android SDK 路径
- `GRADLE_USER_HOME` — Gradle 缓存

## Android SDK 路径

```
$ANDROID_SDK_ROOT/
├── platforms/android-36/
├── build-tools/<版本>/
├── cmdline-tools/latest/
└── platform-tools/
```

## 可用工具

| 命令 | 说明 |
|---|---|
| `aidev-build-request [--project <路径>]` | 构建入口（`./gradlew` 内部调用） |
| `aidev-autoinstall [--launch] <APK 路径>` | 通过 Shizuku 静默安装 APK |
| `aidev-logcat [--filter <tag>]` | 查看日志 |
| `aidev-shizuku exec <命令>` | Shizuku 提权执行 |
| `aidev-apk-info <APK 路径>` | 查看 APK 信息 |
| `aidev-clean` | 清理构建缓存 |
| `aidev-notify <标题> <内容>` | 发送系统通知 |
| `aidev-doctor` | 环境诊断 |
| `aidev-error-why` | 构建错误分析 |
| `aidev-anr` | ANR 分析 |
| `aidev-tombstone` | Native 崩溃分析 |
| `aidev-crash-why` | 崩溃根因分析 |
| `aidev-dumpsys` | 系统状态查看 |
| `aidev-index <类名|方法名|资源名>` | 代码索引搜索 |
| `aidev-gen <activity|fragment|viewmodel> <名称>` | 组件代码生成 |
| `aidev-bridge status` | 桥接服务状态 |
| `aidev-backup <create|list|restore>` | 环境备份 |
| `create-compose-project <项目>` | 创建新 Compose 项目 |
| `sysnotify [--priority high] <标题> <内容>` | 发送系统通知 |
| `sysclip set <文本>` | 写入剪贴板（读取受 Android 安全限制不支持 `get`） |
| `screencap [输出路径]` | 截图 |
| `volume <media|ring|alarm> [值]` | 音量控制 |
| `brightness [0-255\|auto]` | 亮度控制 |
| `aidev-proxy [start\|stop\|status]` | 代理管理 |

## 构建注意事项

1. **ARM64 架构**：aapt2 通过 QEMU x86_64 包装运行，已自动配置
2. **Gradle 缓存**：位于 `$GRADLE_USER_HOME`，首次构建可能较慢
3. **混淆/压缩**：Debug 构建默认关闭 minification
4. **Compose 编译器**：Kotlin 2.0+ 内建 Compose 编译器插件，无需单独配置版本
5. **构建日志**：每次构建记录在 `$AIDEV_HOME/.aidev-build-logs/` 下

## 常用操作

```sh
# 查看 Android SDK 版本
ls $ANDROID_SDK_ROOT/platforms/

# 查看已安装的 build-tools
ls $ANDROID_SDK_ROOT/build-tools/

# 查看 Gradle 版本
./gradlew --version

# 清理并重新构建
./gradlew clean :app:assembleDebug

# 查看依赖树
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```
