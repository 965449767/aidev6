#!/bin/bash
set -Euo pipefail

BACKUP_BASE="${1:-/storage/emulated/0/dev-backup}"
SDK_SRC="${ANDROID_SDK_ROOT:-/Android}"
GRADLE_SRC="${GRADLE_USER_HOME:-/host-home/gradle-cache}"
JDK_SRC="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
LOG_FILE="/tmp/dev-backup.log"

exec > >(tee -a "$LOG_FILE") 2>&1

echo "=============================================="
echo "  开发环境备份工具"
echo "  目标: $BACKUP_BASE"
echo "  SDK:  $SDK_SRC"
echo "  Gradle: $GRADLE_SRC"
echo "  JDK:  $JDK_SRC"
echo "  $(date)"
echo "=============================================="

# 确保目标存在
mkdir -p "$BACKUP_BASE/android-sdk/platforms"
mkdir -p "$BACKUP_BASE/android-sdk/build-tools"
mkdir -p "$BACKUP_BASE/android-sdk/cmdline-tools"
mkdir -p "$BACKUP_BASE/android-sdk/platform-tools"
mkdir -p "$BACKUP_BASE/gradle/wrapper"
mkdir -p "$BACKUP_BASE/gradle/caches"
mkdir -p "$BACKUP_BASE/gradle/native"
mkdir -p "$BACKUP_BASE/jdk"
mkdir -p "$BACKUP_BASE/dev-env"

# ─── Android SDK ─────────────────────────────────────────
echo ""
echo "[1/5] Android SDK ..."

if [ -d "$SDK_SRC/platforms" ]; then
    echo "  → platforms/"
    rsync -a --delete "$SDK_SRC/platforms/" "$BACKUP_BASE/android-sdk/platforms/" 2>/dev/null || \
    cp -a "$SDK_SRC/platforms/." "$BACKUP_BASE/android-sdk/platforms/"
    echo "  ✓ platforms 完成 ($(du -sh "$BACKUP_BASE/android-sdk/platforms" | cut -f1))"
else
    echo "  ⚠ platforms 不存在，跳过"
fi

if [ -d "$SDK_SRC/build-tools" ]; then
    echo "  → build-tools/"
    rsync -a --delete "$SDK_SRC/build-tools/" "$BACKUP_BASE/android-sdk/build-tools/" 2>/dev/null || \
    cp -a "$SDK_SRC/build-tools/." "$BACKUP_BASE/android-sdk/build-tools/"
    echo "  ✓ build-tools 完成 ($(du -sh "$BACKUP_BASE/android-sdk/build-tools" | cut -f1))"
fi

for dir in cmdline-tools platform-tools licenses; do
    if [ -d "$SDK_SRC/$dir" ]; then
        echo "  → $dir/"
        rsync -a --delete "$SDK_SRC/$dir/" "$BACKUP_BASE/android-sdk/$dir/" 2>/dev/null || \
        cp -a "$SDK_SRC/$dir/." "$BACKUP_BASE/android-sdk/$dir/"
        echo "  ✓ $dir 完成 ($(du -sh "$BACKUP_BASE/android-sdk/$dir" | cut -f1))"
    fi
done

# .knownPackages
[ -f "$SDK_SRC/.knownPackages" ] && cp "$SDK_SRC/.knownPackages" "$BACKUP_BASE/android-sdk/"

echo "  ✓ Android SDK 备份完成 ($(du -sh "$BACKUP_BASE/android-sdk" | cut -f1))"

# ─── Gradle ──────────────────────────────────────────────
echo ""
echo "[2/5] Gradle 缓存和包装器 ..."

if [ -d "$GRADLE_SRC/wrapper" ]; then
    echo "  → wrapper/dists/"
    mkdir -p "$BACKUP_BASE/gradle/wrapper/dists"
    rsync -a --delete "$GRADLE_SRC/wrapper/dists/" "$BACKUP_BASE/gradle/wrapper/dists/" 2>/dev/null || \
    cp -a "$GRADLE_SRC/wrapper/dists/." "$BACKUP_BASE/gradle/wrapper/dists/"
    echo "  ✓ wrapper 完成 ($(du -sh "$BACKUP_BASE/gradle/wrapper" | cut -f1))"
fi

if [ -d "$GRADLE_SRC/caches" ]; then
    echo "  → caches/ (可能较大)..."
    rsync -a --delete "$GRADLE_SRC/caches/" "$BACKUP_BASE/gradle/caches/" 2>/dev/null || \
    cp -a "$GRADLE_SRC/caches/." "$BACKUP_BASE/gradle/caches/"
    echo "  ✓ caches 完成 ($(du -sh "$BACKUP_BASE/gradle/caches" | cut -f1))"
fi

if [ -d "$GRADLE_SRC/native" ]; then
    echo "  → native/"
    rsync -a --delete "$GRADLE_SRC/native/" "$BACKUP_BASE/gradle/native/" 2>/dev/null || \
    cp -a "$GRADLE_SRC/native/." "$BACKUP_BASE/gradle/native/"
    echo "  ✓ native 完成"
fi

cp "$GRADLE_SRC/gradle.properties" "$BACKUP_BASE/gradle/" 2>/dev/null || true

echo "  ✓ Gradle 备份完成 ($(du -sh "$BACKUP_BASE/gradle" | cut -f1))"

# ─── JDK ─────────────────────────────────────────────────
echo ""
echo "[3/5] JDK ..."

if [ -d "$JDK_SRC" ]; then
    echo "  → $JDK_SRC"
    rsync -a --delete "$JDK_SRC/" "$BACKUP_BASE/jdk/" 2>/dev/null || \
    cp -a "$JDK_SRC/." "$BACKUP_BASE/jdk/"
    echo "  ✓ JDK 备份完成 ($(du -sh "$BACKUP_BASE/jdk" | cut -f1))"
else
    echo "  ⚠ JDK 目录 $JDK_SRC 不存在，跳过"
fi

# ─── dev-env ─────────────────────────────────────────────
echo ""
echo "[4/5] 开发环境工具 (dev-env) ..."

if [ -d "/host-home/dev-env" ]; then
    echo "  → /host-home/dev-env/"
    rsync -a --delete "/host-home/dev-env/" "$BACKUP_BASE/dev-env/" 2>/dev/null || \
    cp -a "/host-home/dev-env/." "$BACKUP_BASE/dev-env/"
    echo "  ✓ dev-env 完成 ($(du -sh "$BACKUP_BASE/dev-env" | cut -f1))"
fi

# ─── 生成 manifest ────────────────────────────────────────
echo ""
echo "[5/5] 生成 manifest.json ..."

TOTAL_SIZE=$(du -sh "$BACKUP_BASE" | cut -f1)

cat > "$BACKUP_BASE/manifest.json" << JSONEOF
{
  "formatVersion": 1,
  "createdAt": "$(date -Iseconds)",
  "totalSize": "$TOTAL_SIZE",
  "components": {
    "android-sdk": {
      "path": "android-sdk",
      "size": "$(du -sh "$BACKUP_BASE/android-sdk" 2>/dev/null | cut -f1)",
      "description": "Android SDK (platforms, build-tools, cmdline-tools, platform-tools)"
    },
    "gradle": {
      "path": "gradle",
      "size": "$(du -sh "$BACKUP_BASE/gradle" 2>/dev/null | cut -f1)",
      "description": "Gradle wrapper distributions and dependency caches"
    },
    "jdk": {
      "path": "jdk",
      "size": "$(du -sh "$BACKUP_BASE/jdk" 2>/dev/null | cut -f1)",
      "description": "OpenJDK 17 (arm64)"
    },
    "dev-env": {
      "path": "dev-env",
      "size": "$(du -sh "$BACKUP_BASE/dev-env" 2>/dev/null | cut -f1)",
      "description": "Development environment tools (curl, etc.)"
    }
  }
}
JSONEOF

echo "  ✓ manifest.json 已生成"
echo ""
echo "=============================================="
echo "  备份完成！"
echo "  位置: $BACKUP_BASE"
echo "  总大小: $TOTAL_SIZE"
echo "  日志: $LOG_FILE"
echo "=============================================="
