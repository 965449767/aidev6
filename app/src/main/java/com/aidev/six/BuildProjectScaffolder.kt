package com.aidev.six

import android.content.Context
import java.io.File

/**
 * 项目模板脚手架：在 projectDir 下创建完整的 Android 项目结构，
 * 使 BuildBridgeService 可以在没有用户手动操作的情况下编译出可安装的 APK。
 */
internal object BuildProjectScaffolder {

    /** 从 assets 拷贝 gradle-wrapper.jar，缺失才写入。宇宙 B 无 curl/wget，禁止网络下载。 */
    fun ensureWrapperJar(projectDir: File, ctx: Context): Boolean {
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        if (wrapperJar.isFile && wrapperJar.length() > 0) return true
        wrapperJar.parentFile?.mkdirs()
        return runCatching {
            ctx.assets.open("scripts/gradle-wrapper.jar").use { input ->
                wrapperJar.outputStream().use { output -> input.copyTo(output) }
            }
            wrapperJar.isFile && wrapperJar.length() > 0
        }.getOrElse {
            AIDevLogger.e("BuildBridge", "拷贝 gradle-wrapper.jar 失败", it)
            false
        }
    }

    /** 由项目目录名推导合法包名，避免脚手架硬编码 com.example.myandroidproject 与用户源码冲突。 */
    fun derivePackage(projectName: String): String {
        val slug = projectName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "app" }
        val safe = if (slug.first().isDigit()) "a$slug" else slug
        return "com.aidev.app.$safe"
    }

    /** 由项目目录名推导合法主题名后缀（仅字母数字）。 */
    fun deriveThemeSuffix(projectName: String): String =
        projectName.filter { it.isLetterOrDigit() }.ifBlank { "App" }

    fun scaffoldProject(projectDir: File, ctx: Context) {
        projectDir.mkdirs()
        val projectName = projectDir.name.ifBlank { "MyAndroidProject" }
        val pkg = derivePackage(projectName)
        val pkgPath = pkg.replace('.', '/')

        val gradlewDest = File(projectDir, "gradlew")
        runCatching {
            ctx.assets.open("scripts/gradlew").use { input ->
                gradlewDest.outputStream().use { output -> input.copyTo(output) }
            }
            gradlewDest.setExecutable(true)
        }.onFailure {
            AIDevLogger.e("BuildBridge", "Failed to copy gradlew from assets", it)
        }

        File(projectDir, "gradle/wrapper").mkdirs()
        File(projectDir, "gradle/wrapper/gradle-wrapper.properties").writeText(
            """distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists"""
        )

        ensureWrapperJar(projectDir, ctx)

        File(projectDir, "build.gradle.kts").writeText(
            """plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}"""
        )

        writeSettingsGradle(projectDir)

        File(projectDir, "gradle.properties").writeText(
            """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true"""
        )

        File(projectDir, "local.properties").writeText("sdk.dir=/Android")

        File(projectDir, ".gitignore").writeText(
            """.gradle/
build/
/local.properties
*.iml
.idea/
.navigation/
captures/
.externalNativeBuild/
.cxx/"""
        )

        File(projectDir, "app").mkdirs()
        val appDir = File(projectDir, "app/src/main")
        File(projectDir, "app/build.gradle.kts").writeText(
            """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "$pkg"
    compileSdk = 35

    defaultConfig {
        applicationId = "$pkg"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose")
    debugImplementation("androidx.compose.ui:ui-tooling")
}"""
        )

        File(projectDir, "app/proguard-rules.pro").writeText("# Proguard rules\n")

        appDir.mkdirs()
        File(appDir, "AndroidManifest.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>"""
        )

        File(appDir, "java/$pkgPath").mkdirs()
        File(appDir, "java/$pkgPath/MainActivity.kt").writeText(
            """package $pkg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Hello $projectName!")
        }
    }
}"""
        )

        val resDir = File(appDir, "res")
        File(resDir, "values").mkdirs()
        File(resDir, "drawable").mkdirs()
        File(resDir, "mipmap-anydpi-v26").mkdirs()

        File(resDir, "values/strings.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$projectName</string>
</resources>"""
        )

        File(resDir, "drawable/ic_launcher_foreground.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0F2E22"
        android:fillAlpha="0.65"
        android:pathData="M54,2a52,52 0 1,0 0.001,0z" />
    <path
        android:fillColor="#EAF7EE"
        android:strokeColor="#00E676"
        android:strokeWidth="3"
        android:strokeLineJoin="round"
        android:pathData="M30,40 a8,8 0 0 1 8,-8 h32 a8,8 0 0 1 8,8 v32 a8,8 0 0 1 -8,8 h-32 a8,8 0 0 1 -8,-8 z" />
    <path
        android:fillColor="@android:color/transparent"
        android:strokeColor="#00C853"
        android:strokeWidth="5"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:pathData="M44,50 L54,58 L44,66" />
    <path
        android:fillColor="#00C853"
        android:pathData="M60,50 h4 v16 h-4 z" />
    <path
        android:fillColor="#69F0AE"
        android:pathData="M82,22 C82,27.6 87.6,30 90,30 C87.6,30 82,32.4 82,38 C82,32.4 76.4,30 74,30 C76.4,30 82,27.6 82,22 Z" />
</vector>"""
        )

        File(resDir, "drawable/ic_launcher_background.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#07131E"
        android:pathData="M0,0h108v108H0z" />
</vector>"""
        )

        File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>"""
        )

        File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>"""
        )
    }

    /**
     * 写入健壮的 settings.gradle.kts：阿里云 public(聚合) + google 镜像优先，
     * 官方 google()/mavenCentral() 兜底——任一镜像 502/被禁用时仍可从其它仓库解析。
     */
    fun writeSettingsGradle(projectDir: File) {
        val settingsFile = File(projectDir, "settings.gradle.kts")
        val existingName = runCatching {
            if (settingsFile.isFile) {
                Regex("""rootProject\.name\s*=\s*"([^"]+)"""").find(settingsFile.readText())?.groupValues?.get(1)
            } else null
        }.getOrNull()
        val projectName = existingName?.takeIf { it.isNotBlank() }
            ?: projectDir.name.ifBlank { "MyAndroidProject" }
        settingsFile.writeText(
            """pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { setUrl("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}
rootProject.name = "$projectName"
include(":app")"""
        )
    }
}
