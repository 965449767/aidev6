#!/bin/sh
# create-compose-project: 从本地模板创建 Jetpack Compose Android 项目（离线优先）
# 用法: create-compose-project [options] <ProjectName>
#
# 版本锁定（与 AGENTS.md「版本锁定」表格同步）:
#   AGP ${AGP_VERSION} / Kotlin ${KOTLIN_VERSION} / Compose BOM ${COMPOSE_BOM}
#   Gradle ${GRADLE_VERSION} / compileSdk ${COMPILE_SDK} / targetSdk ${TARGET_SDK} / minSdk ${MIN_SDK}
#
# Options:
#   -p, --package PACKAGE  包名（默认 com.example.<项目名小写>）
#   -n, --app-name NAME    应用显示名（默认 <ProjectName>）
#   -o, --output DIR       输出父目录（默认 /workspace）
#   -f, --force            覆盖已存在的项目目录
#   -h, --help             显示帮助

set -e

# ─── 版本锁定 — 与 AGENTS.md 保持同步 ─────────────────
AGP_VERSION="9.0.1"
KOTLIN_VERSION="2.0.21"
COMPOSE_BOM="2024.12.01"
# activity-compose 属 androidx.activity 组，不在 Compose BOM（仅管 androidx.compose.*）
# 约束范围内，必须显式版本，否则报 Could not find androidx.activity:activity-compose:.
# 版本与宿主 aidev6 app/build.gradle.kts 保持一致。
ACTIVITY_COMPOSE_VERSION="1.9.3"
GRADLE_VERSION="9.1.0"
COMPILE_SDK="36"
TARGET_SDK="36"
MIN_SDK="26"

# ─── 默认值 ──────────────────────────────────────────────
OUTPUT_DIR="/workspace"
APP_NAME=""
PACKAGE=""
FORCE=false

# ─── 路径常量 ────────────────────────────────────────────
TEMPLATE_DIR="${HOME}/.gradle/template-wrapper"
CACHE_PARENT_DIR="${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

# ─── 帮助 ────────────────────────────────────────────────
show_help() {
    cat <<'HELP'
用法: create-compose-project [options] <ProjectName>

从本地模板创建 Jetpack Compose Android 项目（离线优先）。

位置参数:
  <ProjectName>              项目名称（必填，支持字母数字下划线和连字符）

选项:
  -p, --package PACKAGE      包名（默认 com.example.<项目名小写>）
  -n, --app-name NAME        应用显示名（默认 <ProjectName>）
  -o, --output DIR           输出父目录（默认 /workspace）
  -f, --force                覆盖已存在的项目目录
  -h, --help                 显示此帮助

示例:
  create-compose-project MyApp
  create-compose-project -p com.company.tool -n "我的工具" WorkspaceApp
  create-compose-project -o ~/dev --force MyApp
HELP
    exit 0
}

# ─── 参数解析 ────────────────────────────────────────────
PROJECT_NAME=""
while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) show_help ;;
        -p|--package) shift; PACKAGE="$1" ;;
        -n|--app-name) shift; APP_NAME="$1" ;;
        -o|--output) shift; OUTPUT_DIR="$1" ;;
        -f|--force) FORCE=true ;;
        -*)
            echo "错误: 未知选项: $1"
            echo "使用 -h 查看帮助"; exit 1 ;;
        *)
            if [ -z "$PROJECT_NAME" ]; then
                PROJECT_NAME="$1"
            else
                echo "错误: 多余的参数: $1"; exit 1
            fi ;;
    esac
    shift
done

# ─── 校验参数 ────────────────────────────────────────────
if [ -z "$PROJECT_NAME" ]; then
    echo "错误: 缺少项目名称"
    echo "用法: create-compose-project [options] <ProjectName>"
    echo "使用 -h 查看帮助"; exit 1
fi

# 项目名允许字母数字下划线和连字符（目录名和 Gradle 均支持）
if ! echo "$PROJECT_NAME" | grep -qE '^[A-Za-z_][A-Za-z0-9_-]*$'; then
    echo "错误: 项目名只能包含字母、数字、下划线和连字符: $PROJECT_NAME"
    exit 1
fi

# 拒绝以 '-' 开头的名字（避免把 --launch 等选项误当项目名，历史曾出现脏目录）
if echo "$PROJECT_NAME" | grep -q '^-'; then
    echo "错误: 项目名不能以 '-' 开头: $PROJECT_NAME"
    exit 1
fi

# 填充默认值
[ -z "$APP_NAME" ] && APP_NAME="$PROJECT_NAME"
[ -z "$PACKAGE" ] && PACKAGE="com.example.$(echo "$PROJECT_NAME" | tr '[:upper:]-' '[:lower:]_')"

# 校验包名格式
if ! echo "$PACKAGE" | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'; then
    echo "错误: 包名格式无效: $PACKAGE"
    echo "正确格式: com.example.myapp"; exit 1
fi

PROJECT_DIR="${OUTPUT_DIR}/${PROJECT_NAME}"
PACKAGE_PATH=$(echo "$PACKAGE" | tr '.' '/')

# 输出目录必须位于 /workspace（AGENTS.md 铁律：否则编译环境不可见）
# 兼容写法：/workspace、/workspace/xxx、/host-home/workspace
case "$OUTPUT_DIR" in
    /workspace|/workspace/*|/host-home/workspace|/host-home/workspace/*) ;;
    *)
        echo "错误: 输出目录必须位于 /workspace 下（铁律：否则编译环境不可见、无法编译）。"
        echo "      当前: $OUTPUT_DIR"
        echo "      建议: create-compose-project -o /workspace <ProjectName>"
        exit 1 ;;
esac

# 目录冲突处理
if [ -d "$PROJECT_DIR" ]; then
    if [ "$FORCE" = true ]; then
        rm -rf "$PROJECT_DIR"
        echo "  覆盖已有目录: ${PROJECT_DIR}"
    else
        echo "错误: 项目目录已存在: $PROJECT_DIR"
        echo "使用 --force 覆盖"; exit 1
    fi
fi

# ─── JDK 说明（终端环境不负责编译，仅提示，不阻断）────────────
# 实际编译在终端环境内进行，终端环境无需本地 Java。
# 此处仅做信息提示，避免因找不到 java 而产生"环境损坏"的误判。
if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -1)
    if ! echo "$JAVA_VERSION" | grep -qi '17\.'; then
        echo "  提示: 终端环境检测到 JDK 非 17 (${JAVA_VERSION})，但编译在隔离环境进行，不影响构建。"
    fi
else
    echo "  提示: 终端环境未安装 Java（符合设计：编译在隔离环境进行，终端环境不负责编译）。"
fi

# ─── 模板校验 ────────────────────────────────────────────
if [ ! -d "$TEMPLATE_DIR" ]; then
    echo "错误: 模板目录不存在: $TEMPLATE_DIR"
    echo "请先执行: mkdir -p $TEMPLATE_DIR/gradle/wrapper"
    echo "并将 gradlew、gradle-wrapper.jar 放入对应位置"
    exit 1
fi
if [ ! -f "$TEMPLATE_DIR/gradlew" ] || [ ! -f "$TEMPLATE_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "错误: 模板目录缺少必要文件"
    echo "需要: gradlew, gradle/wrapper/gradle-wrapper.jar"
    echo "模板路径: $TEMPLATE_DIR"; exit 1
fi

# ─── 动态检测本地 Gradle 缓存 ────────────────────────────
CACHE_PATH=""
CACHE_HASH=""
if [ -d "$CACHE_PARENT_DIR" ]; then
    for _subdir in "$CACHE_PARENT_DIR"/*/; do
        [ -d "$_subdir" ] || continue
        _zip="${_subdir}gradle-${GRADLE_VERSION}-bin.zip"
        if [ -f "$_zip" ]; then
            CACHE_HASH=$(basename "$_subdir")
            CACHE_PATH="$_zip"
            break
        fi
    done
fi
USE_LOCAL=false
[ -n "$CACHE_PATH" ] && USE_LOCAL=true

# ─── 架构检测 (ARM64/QEMU) ──────────────────────────────
ARCH=$(uname -m)
IS_ARM64=false
[ "$ARCH" = "aarch64" ] && IS_ARM64=true

# ─── 自动检测 SDK ───────────────────────────────────────
SDK_DIR=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/platforms" ]; then
    SDK_DIR="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}/platforms" ]; then
    SDK_DIR="$ANDROID_SDK_ROOT"
else
    for _candidate in "/host-home/android-sdk" "/Android" "${HOME}/Android/Sdk"; do
        if [ -d "$_candidate/platforms" ]; then
            SDK_DIR="$_candidate"
            break
        fi
    done
fi

# ARM64 → 寻找可用的 aapt2（从最新到最旧依次测试）
AAPT2_OVERRIDE=""
if [ "$IS_ARM64" = true ] && [ -n "$SDK_DIR" ]; then
    for _bt in "$SDK_DIR/build-tools"/*/; do
        [ -f "${_bt}aapt2" ] || continue
        # 尝试运行，测试 wrapper 是否有效
        if "${_bt}aapt2" version >/dev/null 2>&1; then
            AAPT2_OVERRIDE="${_bt}aapt2"
            break
        fi
    done
fi

# ─── 打印摘要 ────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════"
echo "  创建 Android Compose 项目"
echo "═══════════════════════════════════════════"
echo "  项目名:    ${PROJECT_NAME}"
echo "  包名:      ${PACKAGE}"
echo "  应用名:    ${APP_NAME}"
echo "  输出:      ${PROJECT_DIR}"
echo ""
echo "  AGP:       ${AGP_VERSION}"
echo "  Kotlin:    ${KOTLIN_VERSION}"
echo "  Compose:   ${COMPOSE_BOM}"
echo "  Gradle:    ${GRADLE_VERSION}"
echo "  SDK:       ${COMPILE_SDK} / ${TARGET_SDK} / ${MIN_SDK}"
echo "  JDK:       编译在隔离环境，终端环境无需本地 JDK"
echo "  架构:      ${ARCH}"
if [ "$IS_ARM64" = true ] && [ -n "$AAPT2_OVERRIDE" ]; then
    echo "  AAPT2:     ${AAPT2_OVERRIDE} (QEMU wrapper)"
fi
echo ""
if [ "$USE_LOCAL" = true ]; then
    echo "  Gradle 缓存: 本地 (file://) ✅  (hash: ${CACHE_HASH})"
else
    echo "  Gradle 缓存: 未命中，将使用 https:// ⚠️"
fi
echo "═══════════════════════════════════════════"
echo ""

# ─── SDK platform 校验 ──────────────────────────────────
if [ -n "$SDK_DIR" ] && [ ! -d "${SDK_DIR}/platforms/android-${COMPILE_SDK}" ]; then
    echo "  警告: SDK platform android-${COMPILE_SDK} 未安装"
    echo "        位置: ${SDK_DIR}/platforms/android-${COMPILE_SDK}"
    echo "        构建时 Gradle 将自动下载（离线环境可能失败）"
    echo ""
fi

# ─── 创建目录 ────────────────────────────────────────────
mkdir -p "$PROJECT_DIR"
cd "$PROJECT_DIR"

# ─── 复制模板 ────────────────────────────────────────────
cp "$TEMPLATE_DIR/gradlew" gradlew
[ -f "$TEMPLATE_DIR/gradlew.bat" ] && cp "$TEMPLATE_DIR/gradlew.bat" gradlew.bat
mkdir -p gradle/wrapper
cp "$TEMPLATE_DIR/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/gradle-wrapper.jar
chmod +x gradlew

# ─── gradle-wrapper.properties ───────────────────────────
# 优先使用离线仓库 AIDevRepo 中已缓存的 Gradle 分发（file:// 直读，无需网络/本地 hash）
DEC=$(aidev-repo decide android-gradle "$GRADLE_VERSION" 2>/dev/null || true)
case "$DEC" in
  repo:*)
    REPO_GRADLE=${DEC#repo:}
    echo "▶ 使用离线仓库 Gradle 分发: $REPO_GRADLE"
    cat > gradle/wrapper/gradle-wrapper.properties << PROPS
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=file\\://$REPO_GRADLE
PROPS
    ;;
  network)
    echo "ℹ️ 离线仓库无 Gradle $GRADLE_VERSION，回退网络/本地缓存（可在 AIDevRepo 缓存以离线）"
    if [ "$USE_LOCAL" = true ]; then
        cat > gradle/wrapper/gradle-wrapper.properties << PROPS
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=file\\://${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${CACHE_HASH}/gradle-${GRADLE_VERSION}-bin.zip
PROPS
    else
        cat > gradle/wrapper/gradle-wrapper.properties << PROPS
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
PROPS
    fi
    ;;
  deny)
    echo "❌ 离线优先模式：仓库无 Gradle $GRADLE_VERSION，已禁止网络下载。请先在 AIDevRepo 缓存该资源，或把「离线优先」开关关闭。" >&2
    exit 1
    ;;
  *)
    # 兜底：DEC 为空或未知（如 aidev-repo 异常）时，绝不能静默跳过写文件，
    # 否则 ./gradlew 报 "Wrapper properties file does not exist"。
    echo "ℹ️ 离线仓库判定不可用（DEC='$DEC'），兜底回退网络/本地缓存分发。"
    if [ "$USE_LOCAL" = true ]; then
        cat > gradle/wrapper/gradle-wrapper.properties << PROPS
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=file\\://${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${CACHE_HASH}/gradle-${GRADLE_VERSION}-bin.zip
PROPS
    else
        cat > gradle/wrapper/gradle-wrapper.properties << PROPS
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
PROPS
    fi
    ;;
esac

# ─── build.gradle.kts（根） ─────────────────────────────
# 注意：此处用未引号 heredoc（EOF 不带引号），让 ${...} 在写出时由 shell 展开为真实版本号。
cat > build.gradle.kts << EOF
plugins {
    id("com.android.application") version "${AGP_VERSION}" apply false
    id("org.jetbrains.kotlin.android") version "${KOTLIN_VERSION}" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "${KOTLIN_VERSION}" apply false
}
EOF

# ─── settings.gradle.kts ─────────────────────────────────
cat > settings.gradle.kts << EOF
pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
    }
}
rootProject.name = "${PROJECT_NAME}"
include(":app")
EOF

# ─── gradle.properties ──────────────────────────────────
cat > gradle.properties << 'GRADLE_PROPS'
org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.daemon=true
android.useAndroidX=true
android.nonTransitiveRClass=true

# AGP 9 默认开启 built-in Kotlin，会自动注册 'kotlin' extension；此时再 apply
# org.jetbrains.kotlin.android 会撞名报错：
#   Cannot add extension with name 'kotlin', as there is an extension already registered with that name.
# 本模板沿用 kotlin.android + kotlin.plugin.compose 的标准三插件写法（与宿主 aidev6 一致），
# 故必须关闭 built-in Kotlin，否则任何新项目都无法编译（见 error-journal / Google Issue 438711106）。
android.builtInKotlin=false
android.newDsl=false
GRADLE_PROPS
# ARM64: 追加 aapt2 override 避免使用 AGP 捆绑的 x86_64 版本
if [ "$IS_ARM64" = true ] && [ -n "$AAPT2_OVERRIDE" ]; then
    echo "android.aapt2FromMavenOverride=${AAPT2_OVERRIDE}" >> gradle.properties
fi

# ─── local.properties ───────────────────────────────────
if [ -n "$SDK_DIR" ]; then
    echo "sdk.dir=${SDK_DIR}" > local.properties
else
    echo "  警告: 未找到 Android SDK，需要手动创建 local.properties"
    echo "         echo 'sdk.dir=/path/to/sdk' > ${PROJECT_DIR}/local.properties"
fi

# ─── app/build.gradle.kts ───────────────────────────────
mkdir -p app
cat > app/build.gradle.kts << EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "${PACKAGE}"
    compileSdk = ${COMPILE_SDK}

    defaultConfig {
        applicationId = "${PACKAGE}"
        minSdk = ${MIN_SDK}
        targetSdk = ${TARGET_SDK}
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
    implementation(platform("androidx.compose:compose-bom:${COMPOSE_BOM}"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:${ACTIVITY_COMPOSE_VERSION}")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
EOF

# ─── proguard-rules.pro ────────────────────────────────
cat > app/proguard-rules.pro << 'PROGUARD'
# ProGuard rules
PROGUARD

# ─── AndroidManifest.xml ────────────────────────────────
mkdir -p app/src/main/res/values
cat > app/src/main/AndroidManifest.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/app_name">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
EOF

# ─── strings.xml ────────────────────────────────────────
cat > app/src/main/res/values/strings.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${APP_NAME}</string>
</resources>
EOF

# ─── themes.xml ─────────────────────────────────────────
cat > app/src/main/res/values/themes.xml << EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.${PROJECT_NAME}" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
EOF

# ─── MainActivity.kt ────────────────────────────────────
MAIN_SRC_DIR="app/src/main/java/${PACKAGE_PATH}"
mkdir -p "$MAIN_SRC_DIR"
cat > "${MAIN_SRC_DIR}/MainActivity.kt" << EOF
package ${PACKAGE}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Hello, ${APP_NAME}!")
        }
    }
}
EOF

# ─── .gitignore ─────────────────────────────────────────
cat > .gitignore << 'EOF'
.gradle/
build/
/local.properties
*.iml
.idea/
.cxx/
EOF

# ─── 完成 ────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════"
echo "  项目创建完成!"
echo "═══════════════════════════════════════════"
echo "  目录: ${PROJECT_DIR}"
echo "  构建: aidev-build-request --project ${PROJECT_DIR}"
echo "        （编译在隔离环境进行，构建成功后可 aidev-deploy 安装）"
echo ""
echo "  提交构建并安装后，即可看到: \"Hello, ${APP_NAME}!\""
echo "═══════════════════════════════════════════"
