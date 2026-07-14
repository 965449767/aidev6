#!/bin/sh
# aidev-precache — 预缓存 AIDev 开发基线依赖（离线构建保障）
#
# 用法:
#   aidev-precache                          # 预缓存「模板基线」依赖（compose-bom 2024.12.01 + material-icons-extended 等）
#   aidev-precache <project-dir>            # 预缓存指定项目的实际依赖（解析其 gradle 配置并下载）
#   aidev-precache --gradle-home <DIR>      # 把缓存落点指定为 <DIR>（即 GRADLE_USER_HOME），用于给宇宙 B 等独立缓存预热
#   aidev-precache --universe-b             # 强制把基线缓存同步到宇宙 B 的 gradle 缓存目录（filesDir/home/gradle-cache）
#
# 原理: 把依赖解析结果下载进 Gradle 缓存，使后续离线构建不缺包。
# 关键: 宇宙 B（编译器 rootfs）构建用的 GRADLE_USER_HOME 是 /host-home/gradle-cache，它在宿主真实路径是
#       filesDir/home/gradle-cache。认准这个目录预热，宇宙 B 才能离线构建。本脚本会【自动探测】该目录并同步。
# 注意: 本工具只在「联网时」有意义；离线运行会明确报告无法获取。

set -u

AIDEV_GRADLE="/root/projects/aidev6/gradlew"

GRADLE_HOME_OVERRIDE=""
FORCE_UB=0
MODE_PROJECT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --gradle-home) GRADLE_HOME_OVERRIDE="$2"; shift 2 ;;
        --universe-b) FORCE_UB=1; shift ;;
        -h|--help)
            echo "用法: aidev-precache [选项] [project-dir]"
            echo "  (无参数)            预缓存模板基线依赖到宿主 ~/.gradle"
            echo "  <project-dir>       预缓存指定项目的真实依赖"
            echo "  --gradle-home <DIR> 把缓存落点指定为 <DIR>（GRADLE_USER_HOME），用于给宇宙 B 等独立缓存预热"
            echo "  --universe-b        强制把基线缓存同步到宇宙 B 的 gradle 缓存目录"
            echo "  -h|--help           显示帮助"
            exit 0 ;;
        *) MODE_PROJECT="$1"; shift ;;
    esac
done

GRADLE_BIN=""
if [ -x "$AIDEV_GRADLE" ]; then
    GRADLE_BIN="$AIDEV_GRADLE"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN=gradle
else
    echo "✖ 未找到 gradle（AIDev 的 gradlew 或系统 gradle）" >&2
    exit 1
fi

# 探测宇宙 B 在宿主侧的 gradle 缓存真实路径（/host-home/gradle-cache ↔ filesDir/home/gradle-cache）
detect_ub_cache() {
    local cand
    for cand in "$AIDEV_FILES_DIR/home/gradle-cache" "$HOME/home/gradle-cache"; do
        [ -n "$cand" ] && [ -d "$cand" ] && { echo "$cand"; return 0; }
    done
    for cand in /data/data/*/files/home/gradle-cache; do
        [ -d "$cand" ] && { echo "$cand"; return 0; }
    done
    return 1
}

UB_CACHE=""
if [ "$FORCE_UB" = 1 ] || [ -z "$GRADLE_HOME_OVERRIDE" ]; then
    UB_CACHE=$(detect_ub_cache 2>/dev/null) || UB_CACHE=""
fi

# ── 模式 1: 指定项目 → 解析其真实依赖（落点由 GRADLE_USER_HOME 控制）──
if [ -n "$MODE_PROJECT" ] && [ -d "$MODE_PROJECT" ]; then
    PROJ="$MODE_PROJECT"
    TARGET_HOME="${GRADLE_HOME_OVERRIDE:-$HOME/.gradle}"
    echo "▶ 预缓存项目依赖: $PROJ  (GRADLE_USER_HOME=$TARGET_HOME)"
    if [ -n "$GRADLE_HOME_OVERRIDE" ]; then export GRADLE_USER_HOME="$GRADLE_HOME_OVERRIDE"; fi
    if [ -x "$PROJ/gradlew" ]; then
        ( cd "$PROJ" && "$PROJ/gradlew" dependencies --no-daemon )
    else
        ( cd "$PROJ" && "$GRADLE_BIN" -p "$PROJ" dependencies --no-daemon )
    fi
    echo "✅ 项目依赖已预缓存: $PROJ"
    exit 0
fi

# ── 模式 2 前置: 优先使用离线仓库 AIDevRepo 的 Maven 基线缓存 ──
DEC=$(aidev-repo decide android-maven-baseline 2>/dev/null || true)
case "$DEC" in
  repo:*)
    REPO_MAVEN=${DEC#repo:}
    echo "▶ 使用离线仓库 Maven 基线缓存: $REPO_MAVEN"
    mkdir -p "$HOME/.gradle/caches"
    cp -r "$REPO_MAVEN/." "$HOME/.gradle/caches/" 2>/dev/null \
        && echo "✅ 已从仓库复制 Maven 基线到宿主缓存" \
        || echo "⚠ 从仓库复制失败，回退网络预缓存"
    if [ -n "$UB_CACHE" ]; then
        mkdir -p "$UB_CACHE/caches"
        cp -r "$REPO_MAVEN/." "$UB_CACHE/caches/" 2>/dev/null \
            && echo "✅ 已同步到宇宙 B 缓存" \
            || echo "⚠ 同步到宇宙 B 缓存失败（可忽略）"
    fi
    if offline_ok "$HOME/.gradle"; then echo "✅ 离线自检通过（宿主缓存）"; else echo "⚠ 离线自检未通过（宿主缓存）"; fi
    if [ -n "$UB_CACHE" ]; then
        if offline_ok "$UB_CACHE"; then echo "✅ 离线自检通过（宇宙 B）"; else echo "⚠ 离线自检未通过（宇宙 B）"; fi
    fi
    exit 0
    ;;
  network)
    echo "ℹ️ 离线仓库无 Maven 基线，回退网络预缓存（可在 AIDevRepo 缓存 android-maven-baseline 以离线）"
    ;;
  deny)
    echo "❌ 离线优先模式：仓库无 Maven 基线，已禁止网络预缓存。请先在 AIDevRepo 缓存该资源，或把「离线优先」开关关闭。" >&2
    exit 1
    ;;
esac

# ── 模式 2: 基线 → 用 java-library 临时工程解析并下载 ──
echo "▶ 预缓存 AIDev 模板基线依赖（compose-bom 2024.12.01 + material-icons-extended）"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/settings.gradle.kts" <<'EOF'
rootProject.name = "aidev-precache"
EOF

cat > "$TMP/build.gradle.kts" <<'EOF'
plugins { id("java-library") }
repositories {
    maven { setUrl("https://maven.aliyun.com/repository/google") }
    maven { setUrl("https://maven.aliyun.com/repository/central") }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose")
}
EOF

# 解析基线依赖到宿主缓存（默认落点）
resolve_to() {
    _home="$1"
    export GRADLE_USER_HOME="$_home"
    mkdir -p "$_home"
    if "$GRADLE_BIN" -p "$TMP" dependencies --no-daemon >/dev/null 2>&1; then
        echo "✅ 基线依赖已预缓存 (GRADLE_USER_HOME=$_home)"
        return 0
    fi
    echo "✖ 预缓存失败（GRADLE_USER_HOME=$_home）：可能处于离线状态，请联网后重试。" >&2
    return 1
}

offline_ok() {
    _home="$1"
    export GRADLE_USER_HOME="$_home"
    "$GRADLE_BIN" -p "$TMP" dependencies --offline --no-daemon >/dev/null 2>&1
}

# 1) 解析基线依赖
OK=1
if [ -n "$GRADLE_HOME_OVERRIDE" ]; then
    # 显式落点：直接解析到该目录
    resolve_to "$GRADLE_HOME_OVERRIDE" || OK=0
    TARGET_HOMES="$GRADLE_HOME_OVERRIDE"
    # 若同时探测到宇宙 B 且不同于落点，把宿主缓存同步过去（避免重复下载）
    if [ -n "$UB_CACHE" ] && [ "$GRADLE_HOME_OVERRIDE" != "$UB_CACHE" ]; then
        echo "▶ 同步宿主缓存到宇宙 B 缓存: $UB_CACHE"
        mkdir -p "$UB_CACHE"
        cp -r "$HOME/.gradle/caches" "$UB_CACHE/caches" 2>/dev/null \
            && echo "✅ 已同步到宇宙 B 缓存" \
            || echo "⚠ 同步到宇宙 B 缓存失败（可忽略：首次构建时宇宙 B 会自行拉取）"
        TARGET_HOMES="$TARGET_HOMES $UB_CACHE"
    fi
else
    # 默认：解析到宿主缓存；若探测到宇宙 B，再同步过去（单次下载，避免双重拉取）
    resolve_to "$HOME/.gradle" || OK=0
    TARGET_HOMES="$HOME/.gradle"
    if [ -n "$UB_CACHE" ]; then
        echo "▶ 同步宿主缓存到宇宙 B 缓存: $UB_CACHE"
        mkdir -p "$UB_CACHE"
        cp -r "$HOME/.gradle/caches" "$UB_CACHE/caches" 2>/dev/null \
            && echo "✅ 已同步到宇宙 B 缓存" \
            || echo "⚠ 同步到宇宙 B 缓存失败（可忽略：首次构建时宇宙 B 会自行拉取）"
        TARGET_HOMES="$TARGET_HOMES $UB_CACHE"
    fi
fi

# ── 离线自检 ──
for H in $TARGET_HOMES; do
    if offline_ok "$H"; then
        echo "✅ 离线自检通过：缓存 (GRADLE_USER_HOME=$H) 可离线解析"
    else
        echo "⚠ 离线自检未通过：$H 缓存可能不完整，建议联网重试 aidev-precache"
    fi
done

[ "$OK" = 1 ]
