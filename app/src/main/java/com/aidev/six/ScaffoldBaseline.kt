package com.aidev.six

/**
 * 单一真相源：在 AIDev 里脚手架/生成的 Android 项目所使用的依赖基线。
 *
 * - 这是「模板栈」的权威定义；`ProjectScaffoldState.generateScript()` 与
 *   `/usr/local/bin/create-compose-project` 都必须与此保持一致（见 docs/dev-workflow.md）。
 * -  deliberately 与 AIDev 宿主栈（AGP 9.0.1 / Kotlin 2.0.21 / compileSdk 36）不同：
 *   模板是「目标 App」，采用保守黄金组合（AGP 8.7.0 / Kotlin 2.0.20 / compileSdk 35）。
 * - 宿主 `app/build.gradle.kts` 的 BOM 保持不变（2024.12.01 与之同款），编译环境不受影响。
 */
object ScaffoldBaseline {
    const val COMPOSE_BOM = "2024.12.01"
    const val MATERIAL3_VERSION = "1.3.1" // 由 COMPOSE_BOM 决定；仅用于能力说明
    const val MATERIAL_ICONS_EXT = "1.7.6"
    const val AGP_VERSION = "8.7.0"
    const val KOTLIN_VERSION = "2.0.20"
    const val COMPILE_SDK = 35
    const val TARGET_SDK = 35
    const val MIN_SDK = 26

    /** 脚手架 App 的默认 Compose 依赖（离线基线，含图标库）。 */
    val dependencies: List<String> = listOf(
        "implementation(platform(\"androidx.compose:compose-bom:${COMPOSE_BOM}\"))",
        "implementation(\"androidx.compose.ui:ui\")",
        "implementation(\"androidx.compose.material3:material3\")",
        "implementation(\"androidx.compose.material:material-icons-extended\")",
        "implementation(\"androidx.activity:activity-compose\")",
    )

    /** 脚手架将生成的文件骨架（用于开发前「项目结构树」预览）。<package> 占位由调用方替换。 */
    val structure: String = """
        <project>/
        ├─ settings.gradle.kts
        ├─ build.gradle.kts
        ├─ gradle/
        │  └─ wrapper/
        │     └─ gradle-wrapper.properties
        └─ app/
           ├─ build.gradle.kts
           └─ src/main/
              ├─ AndroidManifest.xml
              ├─ java/<package>/
              │  └─ MainActivity.kt
              └─ res/values/strings.xml
    """.trimIndent()

    /**
     * 该基线下的「能力 & 限制」说明（开发前提示，避免运行时才发现用不了）。
     * 与 docs/compose-capabilities.md 同源。
     */
    val capabilityNotes: List<String> = listOf(
        "material3 $MATERIAL3_VERSION（BOM $COMPOSE_BOM）：用 Button 而非 FilledButton（后者 1.4.0+ 才有）",
        "图标库 material-icons-extended:$MATERIAL_ICONS_EXT 已内置，离线可用；未加的图标名会编译失败",
        "compileSdk/TARGET = $COMPILE_SDK，minSdk = $MIN_SDK（覆盖 Android 8.0+）",
    )

    /**
     * 在 AIDev 设备内开发环境里通常受限/需谨慎的权限（仅提示，非强制禁止）。
     * 来源：AGENTS.md 对敏感权限的约束；用户 App 若确实需要，应在 Manifest 声明并自测。
     */
    val restrictedPermissions: List<Pair<String, String>> = listOf(
        "android.permission.CAMERA" to "摄像头：本环境无相机硬件，相关功能无法自测",
        "android.permission.RECORD_AUDIO" to "麦克风：同上，无法自测",
        "android.permission.READ_CONTACTS" to "通讯录：敏感权限，安装/上架需说明",
        "android.permission.SEND_SMS" to "短信：敏感权限，谨慎使用",
        "android.permission.ACCESS_FINE_LOCATION" to "定位：敏感权限，需前台声明",
    )
}
