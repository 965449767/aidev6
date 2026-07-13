# AIDev 工作区规范（OpenCode 必读）

你是在 **AIDev 双宇宙环境（宇宙A）** 里为一台真实 Android 手机写代码。你写的项目会被
**宇宙B（离线编译器）** 用固定工具链编译成 APK 并静默安装到手机。**必须严格遵守以下约束，否则宇宙B 编译必失败。**

## 工作目录

- 你的默认工作目录是 **`/workspace`**（进终端即落于此）；`HOME` 是 `/root`（opencode 自身配置在此，勿动）。
- **所有项目都建在 `/workspace/` 下**，用绝对路径或就在 `/workspace` 内创建。**只有 `/workspace` 里的文件宇宙B 才能编译**——建到 `/root`、`~/` 等别处 = 宇宙B 看不见 = 无法编译。

## 铁律（违反 = 编译失败）

1. **项目必须建在 `/workspace/` 下**（例如 `/workspace/MyApp`）。建在别处（如 `~/`、`/root/`）宇宙B 完全看不见，无法编译。
2. **新建项目一律用命令**：`aidev-create-android-project <应用名> <包名>`（默认建到 `/workspace/<应用名>`）。不要手写 gradle 脚手架。
3. **不要在模块级 `app/build.gradle.kts` 里写 `repositories { }` 块**。仓库统一在 `settings.gradle.kts`（用阿里云镜像），开了 `FAIL_ON_PROJECT_REPOS`，模块级仓库会硬报错。
4. **不要改版本号**。下列版本是宇宙B 唯一支持且已验证可编译的组合，照抄：

   | 组件 | 固定版本 |
   |---|---|
   | Gradle | `9.1.0` |
   | AGP (com.android.application) | `8.7.3` |
   | Kotlin (org.jetbrains.kotlin.android) | `2.0.21` |
   | compileSdk / targetSdk | `36` |
   | minSdk | `26` |
   | Java / jvmTarget | `17` |

5. **不要改 `gradle-wrapper.properties` 的 Gradle 版本、`local.properties`（`sdk.dir=/Android`）、`settings.gradle.kts` 的仓库块、`gradle.properties` 里的 `android.aapt2FromMavenOverride`**。这些由环境托管，改了会崩。宇宙B 每次构建会自动刷新 `gradlew`/wrapper/settings，你手写的这些会被覆盖。

## 依赖约束

- 只能用能从 **阿里云镜像 / google / mavenCentral / jitpack** 解析到的依赖。冷门 Maven 仓库解析不到会失败。
- 默认可用（脚手架已带）：`androidx.core:core-ktx`、`androidx.appcompat:appcompat`、`com.google.android.material:material`、`androidx.activity:activity-ktx`、`androidx.constraintlayout:constraintlayout`。
- 用 **Jetpack Compose** 时必须完整配置：根 `build.gradle.kts` 加 `org.jetbrains.kotlin.plugin.compose version "2.0.21" apply false`，模块加该插件 + `buildFeatures { compose = true }` + `implementation(platform("androidx.compose:compose-bom:2024.06.00"))`。少任何一项都编不过。缺经验时优先用 XML 布局 + AppCompat（脚手架默认）。

## 构建与验证

- 让宇宙B 编译安装：`aidev-build-request --project /workspace/<应用名>`（或在 App「服务器中心」点提交构建请求）。
- 只产 **debug** APK（`assembleDebug`），无需配签名。
- 构建日志会导出到手机 `/sdcard/AIDev/last-build.log`，失败时先看它定位根因。

### ⚠ 必须用「执行命令」方式触发构建（铁律）

当用户要求你"构建 / 编译 / 打包 / 出 APK / 安装到手机"时，**你必须在终端真正执行下面的 shell 命令**，而**不是**只在回复里写"已交给宇宙B构建""正在为你构建"这类话。

```sh
aidev-build-request --project /workspace/<应用名>
```

正确做法：
```
> 我帮你提交构建请求。
（执行）aidev-build-request --project /workspace/MyApp
（输出）已提交构建请求：project=MyApp install=true launch=true (id=1700000000000)
```

错误做法（仅文本、不执行，导致 App 里完全看不到进度）：
```
> 已交给宇宙B构建，请稍候。   ← 这条等于没做，宿主不会收到任何请求
```

执行后请确认输出包含"已提交构建请求"一行；只有出现这行，宿主的 BuildBridge 才会认领并在聊天里实时显示"准备→编译→安装→拉起"进度。若你不确定项目名，先用 `ls /workspace` 列出可用项目。

## 代码约定

- 包名小写、`com.xxx.yyy` 形式；`namespace`、`applicationId`、源码 `package`、目录结构必须一致。
- Kotlin 优先；不引入 Hilt/Retrofit 等重型框架，除非用户明确要求。
- 不申请相机/麦克风/联系人/短信/定位权限。
