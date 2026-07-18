import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun git(vararg args: String): String {
    return try {
        val proc = ProcessBuilder("git", *args)
            .directory(File("."))
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }
}

fun gitCount(): Int? {
    return try {
        git("rev-list", "--count", "HEAD").toIntOrNull()
    } catch (_: Exception) {
        null
    }
}

fun gitDescribe(): String {
    return try {
        val description = git("describe", "--tags", "--dirty", "--always")
        description.ifBlank { "local" }
    } catch (_: Exception) {
        "local"
    }
}

// Build counter persisted across builds so every APK gets a unique, monotonic version
// (lets Shizuku pm install upgrade correctly and tells rebuilds apart in the self-evolution loop).
// Seed from git commit count so existing installs (versionCode = gitCount) still upgrade.
val buildCounterFile = file("build-counter.properties")
val buildCounterSeed = gitCount() ?: 1
val buildCounterLast = runCatching {
    if (buildCounterFile.isFile) {
        Properties().apply { load(FileInputStream(buildCounterFile)) }
            .getProperty("buildCount")?.toIntOrNull()
    } else null
}.getOrNull() ?: buildCounterSeed
val buildNumber = buildCounterLast + 1

// Allow CI or local build scripts to override generated version values.
// Example: ./gradlew :app:assembleDebug -PversionCode=100 -PversionName=1.0.0
val versionCodeOverride = findProperty("versionCode")?.toString()?.toIntOrNull()
val versionNameOverride = findProperty("versionName")?.toString()
val generatedVersionName = "1.0.0-b$buildNumber-${gitDescribe()}"

fun File.sha256(): String {
    val md = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { b -> String.format("%02x", b.toInt() and 0xFF) }
}

val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let {
    Properties().apply { load(FileInputStream(it)) }
}

android {
    namespace = "com.aidev.six"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.aidev.six"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: buildNumber
        versionName = versionNameOverride ?: generatedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // Retain legacy JNI packaging for the existing arm64 native libraries.
            // Remove this option if the project migrates to the default Android Gradle plugin packaging.
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        getByName("debug") {
            keystoreProps?.let { props ->
                storeFile = rootProject.file(
                    props.getProperty("storeFile") ?: error("keystore.properties: storeFile required")
                )
                storePassword = props.getProperty("storePassword") ?: error("keystore.properties: storePassword required")
                keyAlias = props.getProperty("keyAlias") ?: error("keystore.properties: keyAlias required")
                keyPassword = props.getProperty("keyPassword") ?: error("keystore.properties: keyPassword required")
            }
        }
        create("release") {
            keystoreProps?.let { p ->
                storeFile = rootProject.file(
                    p.getProperty("releaseStoreFile")
                        ?: p.getProperty("storeFile") ?: error("keystore.properties: releaseStoreFile or storeFile required")
                )
                storePassword = p.getProperty("releaseStorePassword")
                    ?: p.getProperty("storePassword") ?: error("keystore.properties: releaseStorePassword or storePassword required")
                keyAlias = p.getProperty("releaseKeyAlias")
                    ?: p.getProperty("keyAlias") ?: error("keystore.properties: releaseKeyAlias or keyAlias required")
                keyPassword = p.getProperty("releaseKeyPassword")
                    ?: p.getProperty("keyPassword") ?: error("keystore.properties: releaseKeyPassword or keyPassword required")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // P1-4：启用 Lint 并冻结历史问题到基线；后续仅对新引入的问题报错。
        // 首次运行会自动生成 lint-baseline.xml（不阻断构建），之后增量校验。
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = false
        // 关闭与稳定性/安全无关的本地化排版检查，聚焦实质问题
        disable += setOf("MissingTranslation", "ExtraTranslation", "TypographyEllipsis", "TypographyDashes")
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-reports")
}

tasks.register("downloadCurlMusl") {
    val targetDir = file("src/main/assets/tools")
    val targetFile = file("$targetDir/curl")
    val sha256File = file("$targetDir/curl.sha256")
    val version = "8.21.0"
    val archiveUrl = URI("https://github.com/stunnel/static-curl/releases/download/$version/curl-linux-aarch64-musl-$version.tar.xz").toURL()
    val archiveFile = file("$temporaryDir/curl-musl.tar.xz")

    doLast {
        targetDir.mkdirs()

        if (targetFile.exists() && sha256File.exists()) {
            val expected = sha256File.readText().trim()
            val actual = targetFile.sha256()
            if (actual == expected) {
                logger.lifecycle("✓ curl-musl 已存在，SHA256 匹配，跳过下载")
                return@doLast
            }
            logger.lifecycle("→ curl 内容可能已变更，重新下载...")
        }

        logger.lifecycle("→ 下载 curl-musl v$version ...")
        temporaryDir.mkdirs()
        archiveUrl.openStream().use { input ->
            archiveFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        logger.lifecycle("→ 下载完成 (${archiveFile.length()} bytes)，解压中...")

        val extractDir = File(temporaryDir, "extracted")
        extractDir.mkdirs()
        val pb = ProcessBuilder("tar", "-xJf", archiveFile.absolutePath, "-C", extractDir.absolutePath)
        pb.inheritIO()
        val proc = pb.start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("tar 解压失败，exit code=$exitCode")
        }

        val extractedCurl = extractDir.listFiles()?.firstOrNull { it.name == "curl" }
            ?: throw RuntimeException("在 tar 归档中未找到 curl 二进制文件")
        extractedCurl.copyTo(targetFile, overwrite = true)
        targetFile.setExecutable(true)
        val sha256 = targetFile.sha256()
        sha256File.writeText(sha256)
        logger.lifecycle("✓ curl-musl 部署完成: ${targetFile.length()} bytes, SHA256=$sha256")
    }
}

val shouldDownloadCurlMusl = findProperty("downloadCurlMusl")?.toString()?.toBoolean() ?: false
if (shouldDownloadCurlMusl) {
    tasks.named("preBuild") {
        dependsOn("downloadCurlMusl")
    }
}

// `downloadCurlMusl` is an optional asset preparation task.
// Enable it with -PdownloadCurlMusl=true when your environment needs the bundled curl binary.
// This avoids forcing a network-dependent download on every Gradle build.

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // P1-6：LeakCanary（仅 debug 构建，自动经 ContentProvider 安装，监测 Activity/Fragment/ViewModel 内存泄漏）
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")

    implementation("org.commonmark:commonmark:0.21.0")

    testImplementation("junit:junit:4.13.2")
    implementation("com.github.mwiede:jsch:0.2.18")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.json:json:20231013")
    // 仪表化测试（androidTest）：P6-04 ShellActivity 生命周期需在真实设备上运行
    // （Robolectric 在本环境的 Aliyun central 镜像无法拉取 shadows-framework/icu4j/android-all）
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Shell tests: run directly via "bash app/src/test/sh/run.sh"
// Gradle integration skipped — ProcessBuilder hangs under QEMU arm64

// Persist the build counter only after a successful assembleDebug so failed
// builds don't consume a version number.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        buildCounterFile.writer().use { w ->
            Properties().apply { setProperty("buildCount", buildNumber.toString()) }
                .store(w, "Auto-incremented per assembleDebug")
        }
        logger.lifecycle("→ build counter -> $buildNumber  (versionName $generatedVersionName)")
    }
}
