---
description: 创建新的 Android 项目骨架
---

你可以使用 `aidev-create-android-project <应用名> <包名>` 快速创建 Android 项目。

例如: `aidev-create-android-project MyApp com.example.myapp` 创建到 `/workspace/MyApp`。

**重要：项目默认建在 `/workspace/` 下，宇宙B 才能编译。不要建到别处。**

生成内容：
  - settings.gradle.kts / 根 build.gradle.kts / app/build.gradle.kts（已开启 Jetpack Compose）
  - AndroidManifest.xml（启动 Activity + Application 配置）
  - MainActivity.kt（Jetpack Compose）
  - res/values/strings.xml / themes.xml（Material 亮色无 ActionBar）
  - proguard-rules.pro / .gitignore / AGENTS.md（项目级构建约束）
  - Git init + initial commit

黄金版本（勿改）：AGP 8.7.3 + Kotlin 2.0.21 + Gradle 9.1.0 + compileSdk/targetSdk 36 + minSdk 26。

创建后编译安装：`aidev-build-request --project /workspace/<应用名>`。

当用户需要新建 Android 项目时使用此命令。
