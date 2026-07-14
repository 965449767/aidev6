#!/bin/sh
# aidev-create-android-project: 从模板创建新 Android 项目
# 自动匹配当前环境 AGP/Kotlin 版本
# 用法: aidev-create-android-project <应用名> <包名> [输出目录]

set -e

APP_NAME="${1:-}"
PACKAGE="${2:-}"
# 默认输出目录必须是共享 workspace（宇宙B 只能看见 /workspace），否则编译请求找不到项目。
# 优先 /workspace（宇宙A 内绑定点），回退 $AIDEV_WORKSPACE / /host-home/workspace。
if [ -n "${3:-}" ]; then
    OUTPUT_DIR="$3"
elif [ -d /workspace ]; then
    OUTPUT_DIR="/workspace"
elif [ -n "${AIDEV_WORKSPACE:-}" ]; then
    OUTPUT_DIR="$AIDEV_WORKSPACE"
else
    OUTPUT_DIR="/host-home/workspace"
fi

if [ -z "$APP_NAME" ] || [ -z "$PACKAGE" ]; then
    echo "用法: aidev-create-android-project <应用名> <包名> [输出目录]"
    echo ""
    echo "示例:"
    echo "  aidev-create-android-project MyApp com.example.myapp"
    echo "  aidev-create-android-project MyApp com.example.myapp /workspace"
    echo ""
    echo "注意: 项目必须建在 /workspace 下，宇宙B 才能编译。默认已指向 /workspace。"
    exit 1
fi

if ! echo "$PACKAGE" | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'; then
    echo "错误: 包名格式无效: $PACKAGE"
    echo "正确格式: com.example.myapp"
    exit 1
fi

PROJECT_DIR="${OUTPUT_DIR}/${APP_NAME}"
PACKAGE_PATH=$(echo "$PACKAGE" | tr '.' '/')

if [ -d "$PROJECT_DIR" ]; then
    echo "错误: 目标目录已存在: $PROJECT_DIR"
    exit 1
fi

# AGP / Kotlin / Gradle 黄金版本（宇宙B 唯一支持且已验证；勿改，见工作区 AGENTS.md）
AGP_VERSION="8.7.3"
KOTLIN_VERSION="2.0.21"
GRADLE_VERSION="9.1.0"

# 从构建脚本所在目录上溯查找宿主项目（AdvTerminal）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT=""
for candidate in "$SCRIPT_DIR" "$SCRIPT_DIR/.." "$SCRIPT_DIR/../.." "$SCRIPT_DIR/../../.." "$SCRIPT_DIR/../../../.."; do
    candidate=$(cd "$candidate" 2>/dev/null && pwd || true)
    [ -n "$candidate" ] && [ -f "$candidate/build.gradle.kts" ] && [ -f "$candidate/settings.gradle.kts" ] && { PROJECT_ROOT="$candidate"; break; }
done
HAS_HOST=false
if [ -n "$PROJECT_ROOT" ] && [ -f "$PROJECT_ROOT/build.gradle.kts" ]; then
    HAS_HOST=true
    FOUND_AGP=$(grep "com.android.application" "$PROJECT_ROOT/build.gradle.kts" 2>/dev/null | sed -n 's/.*version[[:space:]]*"\([^"]*\)".*/\1/p')
    [ -n "$FOUND_AGP" ] && AGP_VERSION="$FOUND_AGP"
    FOUND_KOTLIN=$(grep -E "^(plugins|id.*kotlin)" "$PROJECT_ROOT/build.gradle.kts" 2>/dev/null | grep -oP '"[0-9]+\.[0-9]+\.[0-9]+"' | head -1 | tr -d '"')
    [ -n "$FOUND_KOTLIN" ] && KOTLIN_VERSION="$FOUND_KOTLIN"
    FOUND_GRADLE=$(grep '^distributionUrl' "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null | grep -oP 'gradle-\K[0-9.]+(?=-bin\.zip)')
    [ -n "$FOUND_GRADLE" ] && GRADLE_VERSION="$FOUND_GRADLE"
fi

# 从宿主提取仓库配置
HOST_PLUGIN_REPOS=""
HOST_DEP_REPOS=""
HOST_DIST_URL="distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
HOST_PROPS_EXTRA=""
if [ "$HAS_HOST" = true ]; then
    HOST_PLUGIN_REPOS=$(awk '/^pluginManagement {/,/^}/' "$PROJECT_ROOT/settings.gradle.kts" 2>/dev/null | sed '1,/repositories {/d; /^}/,$d')
    HOST_DEP_REPOS=$(awk '/^dependencyResolutionManagement {/,/^}/' "$PROJECT_ROOT/settings.gradle.kts" 2>/dev/null | sed '1,/repositories {/d; /^}/,$d')
    HOST_DIST_URL=$(grep '^distributionUrl' "$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null || echo "$HOST_DIST_URL")
    # 提取项目级 gradle.properties（排除注释/空行/全局配置/模板已有项）
    HOST_PROPS_EXTRA=$(grep -v '^#' "$PROJECT_ROOT/gradle.properties" 2>/dev/null \
        | grep -v '^$' \
        | grep -v 'android\.aapt2\|android\.suppress\|systemProp\.' \
        | grep -v '^org\.gradle\.jvmargs\|^android\.useAndroidX\|^kotlin\.code\.style\|^android\.nonTransitiveRClass' || true)
fi

echo ""
echo "═══════════════════════════════════════════"
echo "  创建 Android 项目"
echo "═══════════════════════════════════════════"
echo "  应用名:    ${APP_NAME}"
echo "  包名:      ${PACKAGE}"
echo "  AGP:       ${AGP_VERSION}"
echo "  Kotlin:    ${KOTLIN_VERSION}"
echo "  Gradle:    ${GRADLE_VERSION}"
echo "  输出:      ${PROJECT_DIR}"
echo ""

mkdir -p "$PROJECT_DIR"
cd "$PROJECT_DIR"

# ─── settings.gradle.kts（仓库配置从宿主继承） ───
if [ -n "$HOST_PLUGIN_REPOS" ]; then
    # 从宿主提取的完整仓库列表
    printf 'pluginManagement {\n    repositories {\n' > settings.gradle.kts
    echo "$HOST_PLUGIN_REPOS" >> settings.gradle.kts
    printf '    }\n}\n\ndependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {\n' >> settings.gradle.kts
    echo "$HOST_DEP_REPOS" >> settings.gradle.kts
    printf '    }\n}\n\nrootProject.name = "%s"\ninclude(":app")\n' "${APP_NAME}" >> settings.gradle.kts
else
    # 宿主不可用时使用内置默认值
    cat > settings.gradle.kts <<'SETTINGS_EOF'
pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://jitpack.io") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://jitpack.io") }
    }
}
SETTINGS_EOF
    echo "rootProject.name = \"${APP_NAME}\"" >> settings.gradle.kts
    echo 'include(":app")' >> settings.gradle.kts
fi

# ─── 根 build.gradle.kts ───
cat > build.gradle.kts <<EOF
plugins {
    id("com.android.application") version "${AGP_VERSION}" apply false
    id("org.jetbrains.kotlin.android") version "${KOTLIN_VERSION}" apply false
}
EOF

# ─── SDK 探测（local.properties 与 aapt2 覆盖共用） ───
SDK_DIR=""
if [ -d "/Android/platforms" ]; then
    SDK_DIR="/Android"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    SDK_DIR="$ANDROID_HOME"
elif [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then
    SDK_DIR="$ANDROID_SDK_ROOT"
elif [ -f "$PROJECT_ROOT/local.properties" ]; then
    SDK_DIR=$(grep "^sdk.dir\|^sdk" "$PROJECT_ROOT/local.properties" 2>/dev/null | head -1 | cut -d= -f2 || true)
fi

# 自动挑选最新 build-tools，注入 aapt2 覆盖：
# arm64 上 AGP 自带的 aapt2 是 x86_64，会 Daemon startup failed；
# 本环境用 qemu 包装的 ARM 版 aapt2，必须显式覆盖才能编译资源。
AAPT2_OVERRIDE=""
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/build-tools" ]; then
    BT_VER=$(ls -1 "$SDK_DIR/build-tools" 2>/dev/null | grep -E '^[0-9]+(\.[0-9]+)+$' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
    if [ -n "$BT_VER" ] && [ -e "$SDK_DIR/build-tools/$BT_VER/aapt2" ]; then
        AAPT2_OVERRIDE="$SDK_DIR/build-tools/$BT_VER/aapt2"
    fi
fi

# ─── gradle.properties（基础设置 + 宿主继承项 + aapt2 覆盖） ───
cat > gradle.properties <<EOF
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
$(echo "$HOST_PROPS_EXTRA")
$([ -n "$AAPT2_OVERRIDE" ] && echo "android.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
EOF

# ─── local.properties (SDK 路径) ───
if [ -n "$SDK_DIR" ]; then
    echo "sdk.dir=$SDK_DIR" > local.properties
    echo "  SDK:       $SDK_DIR"
    [ -n "$AAPT2_OVERRIDE" ] && echo "  aapt2:     $AAPT2_OVERRIDE"
else
    echo "  警告: 未找到 Android SDK，需要手动创建 local.properties"
    echo "         echo 'sdk.dir=/Android' > local.properties"
fi

# ─── Gradle Wrapper（distributionUrl 从宿主继承） ───
mkdir -p gradle/wrapper
cat > gradle/wrapper/gradle-wrapper.properties <<EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
${HOST_DIST_URL}
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

# ─── app/build.gradle.kts ───
mkdir -p app
cat > app/build.gradle.kts <<EOF
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "${PACKAGE}"
    compileSdk = 36

    defaultConfig {
        applicationId = "${PACKAGE}"
        minSdk = 26
        targetSdk = 36
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
EOF

# ─── AndroidManifest.xml ───
mkdir -p "app/src/main"
cat > app/src/main/AndroidManifest.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.${APP_NAME}">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.${APP_NAME}">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
EOF

# ─── Kotlin 源码 ───
SRC_DIR="app/src/main/java/${PACKAGE_PATH}"
mkdir -p "$SRC_DIR"

cat > "${SRC_DIR}/MainActivity.kt" <<EOF
package ${PACKAGE}

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
EOF

# ─── 资源文件 ───
mkdir -p app/src/main/res/layout
mkdir -p app/src/main/res/values
mkdir -p app/src/main/res/mipmap-hdpi
mkdir -p app/src/main/res/mipmap-anydpi-v26
mkdir -p app/src/main/res/drawable

# 自适应启动图标（避免 manifest 引用 mipmap/ic_launcher 缺失导致资源链接失败）
cat > app/src/main/res/drawable/ic_launcher_foreground.xml <<'IC_FG_EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#6200EE" android:pathData="M0,0h108v108h-108z"/>
</vector>
IC_FG_EOF

cat > app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml <<'IC_LA_EOF'
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/white"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
IC_LA_EOF

cat > app/src/main/res/layout/activity_main.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hello ${APP_NAME}!"
        android:textSize="24sp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
EOF

cat > app/src/main/res/values/strings.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${APP_NAME}</string>
</resources>
EOF

cat > app/src/main/res/values/themes.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.${APP_NAME}" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
        <item name="colorPrimary">#6200EE</item>
        <item name="colorPrimaryVariant">#3700B3</item>
        <item name="colorOnPrimary">#FFFFFF</item>
        <item name="colorSecondary">#03DAC5</item>
        <item name="colorSecondaryVariant">#018786</item>
        <item name="colorOnSecondary">#000000</item>
    </style>
</resources>
EOF

# ─── proguard-rules.pro ───
cat > app/proguard-rules.pro <<EOF
# ProGuard rules for ${APP_NAME}
EOF

# ─── 项目级 AGENTS.md（把宇宙B 构建约束钉在项目里，OpenCode 后续改码必读） ───
cat > AGENTS.md <<EOF
# ${APP_NAME} — 构建约束（OpenCode 必读）

本项目由 AIDev 宇宙B 离线编译。**改码时严格遵守，否则编译失败：**

- 版本锁定（勿改）：Gradle ${GRADLE_VERSION} / AGP ${AGP_VERSION} / Kotlin ${KOTLIN_VERSION} / compileSdk 36 / targetSdk 36 / minSdk 26 / Java 17
- **不要在 app/build.gradle.kts 写 repositories { } 块**（settings.gradle.kts 已开 FAIL_ON_PROJECT_REPOS，统一用阿里云镜像）
- 依赖只用能从 阿里云/google/mavenCentral/jitpack 解析到的
- 不要改 gradle-wrapper.properties / local.properties / settings.gradle.kts 仓库块 / gradle.properties 的 aapt2 覆盖（环境托管，会被覆盖）
- namespace / applicationId / 源码 package 保持一致（本项目为 ${PACKAGE}）
- 编译安装：aidev-build-request --project ${PROJECT_DIR}
EOF

# ─── 下载 Gradle Wrapper ───
echo "  下载 Gradle Wrapper..."
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
WRAPPER_JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"
if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_JAR_URL" 2>/dev/null || true
elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$WRAPPER_JAR" "$WRAPPER_JAR_URL" 2>/dev/null || true
fi

cat > gradlew <<'GRADLEW_EOF'
#!/bin/sh
GRADLEW_EOF

cat > gradlew <<'GRADLEW_SCRIPT'
#!/bin/sh
#
# Gradle start up script for POSIX generated by Gradle.
# 简化版：自动下载 Gradle wrapper jar

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DIRNAME=$(dirname "$0")

# 如果 wrapper jar 不存在，尝试从 Gradle 分布下载
JARFILE="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JARFILE" ]; then
    echo "下载 Gradle wrapper..."
    GRADLE_VERSION=$(grep "distributionUrl" "$DIRNAME/gradle/wrapper/gradle-wrapper.properties" | sed 's/.*gradle-\([0-9.]*\)-bin.zip.*/\1/')
    JAR_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$JARFILE" "$JAR_URL" || true
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$JARFILE" "$JAR_URL" || true
    fi
fi

if [ ! -f "$JARFILE" ]; then
    echo "错误: 无法下载 Gradle wrapper jar"
    echo "请手动下载并放置到: $JARFILE"
    exit 1
fi

exec java -classpath "$JARFILE" org.gradle.wrapper.GradleWrapperMain "$@"
GRADLEW_SCRIPT

chmod +x gradlew

# ─── 初始化 Git ───
echo "  初始化 Git..."
git init 2>/dev/null || true
git checkout -b main 2>/dev/null || true

cat > .gitignore <<EOF
.gradle/
build/
/local.properties
*.iml
.idea/
.navigation/
captures/
.externalNativeBuild/
.cxx/
EOF

git add -A 2>/dev/null || true
git commit -m "Initial commit: ${APP_NAME}" --allow-empty 2>/dev/null || true

# 构建代码搜索索引
echo "  构建索引..."
aidev-index 2>/dev/null || true

echo ""
echo "═══════════════════════════════════════════"
echo "  项目创建完成!"
echo "═══════════════════════════════════════════"
echo "  目录: ${PROJECT_DIR}"
echo "  构建: cd ${PROJECT_DIR} && aidev-build --full"
echo "  解析 APK: aidev-apk-info app/build/outputs/apk/debug/app-debug.apk"
echo "═══════════════════════════════════════════"
