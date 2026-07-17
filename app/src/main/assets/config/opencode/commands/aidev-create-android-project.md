---
description: 创建新的 Android 项目骨架（委托 create-compose-project）
---

你可以使用 `aidev-create-android-project <应用名> <包名>` 快速创建 Android 项目。
该命令是 `create-compose-project` 的兼容封装，版本矩阵与脚手架由其统一生成。

例如: `aidev-create-android-project MyApp com.example.myapp` 创建到 `~/projects/MyApp`。

**注意：项目需建在 `/workspace/` 下，宇宙B 才能编译（默认输出 `~/projects`，提交构建时请用 `/workspace/<应用名>`）。**

生成内容（由 create-compose-project 统一产出）：
  - settings.gradle.kts / 根 build.gradle.kts / app/build.gradle.kts（已开启 Jetpack Compose）
  - AndroidManifest.xml（启动 Activity + Application 配置）
  - MainActivity.kt（Jetpack Compose）
  - res/values/strings.xml / themes.xml（Material 亮色无 ActionBar）
  - proguard-rules.pro / .gitignore / AGENTS.md（项目级构建约束）
  - Git init + initial commit

黄金版本（勿改，与宿主一致）：AGP 9.0.1 + Kotlin 2.0.21 + Gradle 9.1.0 + compileSdk/targetSdk 36 + minSdk 26。

创建后编译安装：`aidev-build-request --project /workspace/<应用名>`。

当用户需要新建 Android 项目时使用此命令。
