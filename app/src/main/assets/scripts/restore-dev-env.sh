#!/bin/bash
# ═══════════════════════════════════════════════════════════
#  从本地备份恢复 Android 开发环境
#  用法: bash restore.sh [目标目录前缀]
#  默认: ANDROID_SDK_ROOT=/host-home/android-sdk (宿主 App 构建环境)
#           GRADLE_USER_HOME=/host-home/gradle-cache
#           JDK 目标 = /usr/lib/jvm/java-17-openjdk-arm64
# ═══════════════════════════════════════════════════════════
set -Euo pipefail

BACKUP_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DEST="${SDK_DEST:-/host-home/android-sdk}"
GRADLE_DEST="${GRADLE_DEST:-/host-home/gradle-cache}"
JDK_DEST="${JDK_DEST:-/usr/lib/jvm/java-17-openjdk-arm64}"
LOG_FILE="/tmp/restore-dev-env.log"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "=============================================="
echo "  开发环境恢复工具"
echo "  备份源: $BACKUP_DIR"
echo "  SDK →  $SDK_DEST"
echo "  Gradle → $GRADLE_DEST"
echo "  JDK →  $JDK_DEST"
echo "  $(date)"
echo "=============================================="

confirm() {
    echo ""
    echo -n "  继续? [Y/n] "
    read -r ans
    case "$ans" in
        [Nn]*) echo "  已取消"; exit 0 ;;
        *) return 0 ;;
    esac
}

# ─── Android SDK ─────────────────────────────────────────
restore_sdk() {
    local src="$BACKUP_DIR/android-sdk"
    if [ ! -d "$src/platforms" ]; then
        echo "  ⚠ SDK 备份不存在，跳过"
        return 1
    fi

    echo ""
    echo "[1/4] 恢复 Android SDK → $SDK_DEST ..."

    mkdir -p "$SDK_DEST/platforms" "$SDK_DEST/build-tools" "$SDK_DEST/cmdline-tools" "$SDK_DEST/platform-tools" "$SDK_DEST/licenses"

    for subdir in platforms build-tools cmdline-tools platform-tools licenses; do
        if [ -d "$src/$subdir" ]; then
            echo "  → $subdir/"
            cp -a "$src/$subdir/." "$SDK_DEST/$subdir/"
        fi
    done
    [ -f "$src/.knownPackages" ] && cp "$src/.knownPackages" "$SDK_DEST/"

    echo "  ✓ SDK 恢复完成"
}

# ─── Gradle ──────────────────────────────────────────────
restore_gradle() {
    local src="$BACKUP_DIR/gradle"
    if [ ! -d "$src/wrapper" ]; then
        echo "  ⚠ Gradle 备份不存在，跳过"
        return 1
    fi

    echo ""
    echo "[2/4] 恢复 Gradle → $GRADLE_DEST ..."

    mkdir -p "$GRADLE_DEST"

    for subdir in wrapper native; do
        if [ -d "$src/$subdir" ]; then
            echo "  → $subdir/"
            mkdir -p "$GRADLE_DEST/$subdir"
            cp -a "$src/$subdir/." "$GRADLE_DEST/$subdir/"
        fi
    done

    if [ -d "$src/caches" ]; then
        echo "  → caches/ (依赖缓存, 较大)..."
        mkdir -p "$GRADLE_DEST/caches"
        for subdir in modules-2 jars-9 8.14.5 9.1.0; do
            if [ -d "$src/caches/$subdir" ]; then
                echo "    → caches/$subdir"
                mkdir -p "$GRADLE_DEST/caches/$subdir"
                cp -a "$src/caches/$subdir/." "$GRADLE_DEST/caches/$subdir/"
            fi
        done
    fi

    [ -f "$src/gradle.properties" ] && cp "$src/gradle.properties" "$GRADLE_DEST/"
    echo "  ✓ Gradle 恢复完成"
}

# ─── JDK ─────────────────────────────────────────────────
restore_jdk() {
    local src="$BACKUP_DIR/jdk"
    if [ ! -d "$src/bin" ]; then
        echo "  ⚠ JDK 备份不存在，跳过"
        return 1
    fi

    echo ""
    echo "[3/4] 恢复 JDK → $JDK_DEST ..."

    mkdir -p "$JDK_DEST"
    mkdir -p "$(dirname "$JDK_DEST")"

    # sdcard FAT32 不支持符号链接，JDK 内大量 symlink
    # 用 tar 打包 + 解包保留 symlink
    local tmp_tar="/tmp/jdk-restore.tar"
    cd "$src"
    # 打包为 tar 再释放到目标目录，保留 symlink
    tar cf "$tmp_tar" .
    cd "$JDK_DEST"
    tar xf "$tmp_tar"
    rm -f "$tmp_tar"
    cd "$BACKUP_DIR"

    echo "  ✓ JDK 恢复完成 ($(du -sh "$JDK_DEST" | cut -f1))"
}

# ─── dev-env ─────────────────────────────────────────────
restore_dev_env() {
    local src="$BACKUP_DIR/dev-env"
    if [ ! -d "$src/bin" ]; then
        echo "  ⚠ dev-env 备份不存在，跳过"
        return 1
    fi

    echo ""
    echo "[4/4] 恢复 dev-env → /host-home/dev-env ..."

    mkdir -p /host-home/dev-env
    cp -a "$src/." /host-home/dev-env/
    echo "  ✓ dev-env 恢复完成"
}

# ════════════════════════════ 主流程 ═════════════════════════════════════
echo ""
echo "即将恢复以下组件:"
[ -d "$BACKUP_DIR/android-sdk/platforms" ] && echo "  • Android SDK"
[ -d "$BACKUP_DIR/gradle/wrapper" ] && echo "  • Gradle"
[ -d "$BACKUP_DIR/jdk/bin" ] && echo "  • JDK 17"
[ -d "$BACKUP_DIR/dev-env/bin" ] && echo "  • dev-env 工具"

confirm

restore_sdk
restore_gradle
restore_jdk
restore_dev_env

echo ""
echo "=============================================="
echo "  恢复完成！"
echo "  日志: $LOG_FILE"
echo "=============================================="
echo ""
echo "验证:"
echo "  java -version"
echo "  ls $SDK_DEST/platforms/"
echo "  ls $GRADLE_DEST/wrapper/dists/"
