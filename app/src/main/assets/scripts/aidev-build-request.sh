#!/bin/sh
# aidev-build-request: 薄包装 Gradle 构建，精简输出 + 自动日志 + APK 检测 + 通知 + 熄屏保活
# Usage: aidev-build-request [--project <dir>] [Gradle args...]
#   --project <dir>  项目目录（默认：自动检测 gradlew 所在目录）
#   其余参数透传给 Gradle
#
# 退出码:
#   0  构建成功
#   1  构建失败
#   2  环境错误

set -e

# ── 版本常量（与 create-compose-project.sh 同步）──
GRADLE_VERSION="9.1.0"
AGP_VERSION="9.0.1"
KOTLIN_VERSION="2.0.21"
COMPILE_SDK="36"
MIN_SDK="26"
TARGET_SDK="36"

# ── 配置校验函数 ──
validate_build_config() {
    local dir="$1"
    local errors=0

    # ── 1. gradle-wrapper.properties ──
    local gwp="$dir/gradle/wrapper/gradle-wrapper.properties"
    if [ -f "$gwp" ]; then
        if ! grep -q "gradle-${GRADLE_VERSION}-bin.zip" "$gwp" 2>/dev/null; then
            echo "  ✗ gradle-wrapper.properties: Gradle 版本被修改" >&2
            echo "    期望: gradle-${GRADLE_VERSION}-bin.zip" >&2
            errors=$((errors + 1))
        fi
    else
        echo "  ✗ 缺少 gradle/wrapper/gradle-wrapper.properties" >&2
        errors=$((errors + 1))
    fi

    # ── 2. root build.gradle.kts 插件版本 ──
    local root_build="$dir/build.gradle.kts"
    if [ -f "$root_build" ]; then
        for ver in "${AGP_VERSION}" "${KOTLIN_VERSION}"; do
            if ! grep -q "\"${ver}\"" "$root_build" 2>/dev/null; then
                echo "  ✗ build.gradle.kts: 版本 ${ver} 被修改或缺失" >&2
                errors=$((errors + 1))
            fi
        done
    fi

    # ── 3. app/build.gradle.kts SDK 版本 ──
    local app_build="$dir/app/build.gradle.kts"
    if [ -f "$app_build" ]; then
        for kv in "compileSdk = ${COMPILE_SDK}" \
                  "minSdk = ${MIN_SDK}" \
                  "targetSdk = ${TARGET_SDK}"; do
            if ! grep -q "$kv" "$app_build" 2>/dev/null; then
                echo "  ✗ app/build.gradle.kts: $kv 不存在或已被修改" >&2
                errors=$((errors + 1))
            fi
        done
    else
        echo "  ✗ 缺少 app/build.gradle.kts" >&2
        errors=$((errors + 1))
    fi

    # ── 4. gradle.properties 关键设置 ──
    local gprops="$dir/gradle.properties"
    if [ -f "$gprops" ]; then
        for kv in "android.aapt2DaemonMode=false" \
                  "android.useAndroidX=true" \
                  "android.nonTransitiveRClass=true"; do
            if ! grep -q "$kv" "$gprops" 2>/dev/null; then
                echo "  ✗ gradle.properties: $kv 不存在或已被修改" >&2
                errors=$((errors + 1))
            fi
        done
    else
        echo "  ✗ 缺少 gradle.properties" >&2
        errors=$((errors + 1))
    fi

    # ── 5. SHA256 哈希校验（如有 .build-config.json）──
    if [ -f "$dir/.build-config.json" ] && command -v sha256sum >/dev/null 2>&1; then
        grep -o '"[a-z./-]*\.\(properties\|kts\)": "[a-f0-9]\{64\}"' "$dir/.build-config.json" \
            > /tmp/.aidv-hash-$$.txt 2>/dev/null || true
        if [ -s /tmp/.aidv-hash-$$.txt ]; then
            while IFS= read -r entry; do
                [ -z "$entry" ] && continue
                fname=$(echo "$entry" | sed 's/":.*//;s/"//g')
                expected=$(echo "$entry" | sed 's/.*": "//;s/"$//')
                [ "$expected" = "skip" ] && continue
                actual=$(sha256sum "$dir/$fname" 2>/dev/null | cut -d' ' -f1) || true
                if [ "$actual" != "$expected" ]; then
                    echo "  ✗ $fname SHA256 不匹配" >&2
                    errors=$((errors + 1))
                fi
            done < /tmp/.aidv-hash-$$.txt
        fi
        rm -f /tmp/.aidv-hash-$$.txt
    fi

    if [ "$errors" -gt 0 ]; then
        echo "" >&2
        echo "═══════════════════════════════════════════" >&2
        echo "  配置校验失败（${errors} 项），构建被拒绝" >&2
        echo "═══════════════════════════════════════════" >&2
        echo "  恢复默认配置: create-compose-project --force <项目名>" >&2
        return 1
    fi
    echo "  ✓ 配置校验通过"
    return 0
}

# ── 自愈函数 ──
auto_heal() {
    local dir="$1"
    local healed=0

    # 1. gradle.properties: 补回被删除的关键属性
    local gprops="$dir/gradle.properties"
    if [ -f "$gprops" ]; then
        for kv in \
            "android.aapt2DaemonMode=false" \
            "android.useAndroidX=true" \
            "android.nonTransitiveRClass=true"; do
            if ! grep -q "$kv" "$gprops" 2>/dev/null; then
                echo "$kv" >> "$gprops"
                echo "  ⚠ 自动修复: gradle.properties 补回 $kv" >&2
                healed=$((healed + 1))
            fi
        done
    fi

    # 2. gradle-wrapper.properties: 版本被改 → 从版本常量重新生成
    local gwp="$dir/gradle/wrapper/gradle-wrapper.properties"
    if [ -f "$gwp" ] && ! grep -q "gradle-${GRADLE_VERSION}-bin.zip" "$gwp" 2>/dev/null; then
        cp "$gwp" "$gwp.bak.$(date +%s)" 2>/dev/null || true
        cat > "$gwp" << GWP_EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
GWP_EOF
        echo "  ⚠ 自动修复: gradle-wrapper.properties 恢复为 Gradle ${GRADLE_VERSION}" >&2
        healed=$((healed + 1))
    fi

    return $healed
}

# ── 锁定函数 ──
lock_project() {
    local dir="$1"

    if command -v sha256sum >/dev/null 2>&1; then
        _h1=$(sha256sum "$dir/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null | cut -d' ' -f1 || echo 'skip')
        _h2=$(sha256sum "$dir/build.gradle.kts" 2>/dev/null | cut -d' ' -f1 || echo 'skip')
        _h3=$(sha256sum "$dir/app/build.gradle.kts" 2>/dev/null | cut -d' ' -f1 || echo 'skip')
        _h4=$(sha256sum "$dir/settings.gradle.kts" 2>/dev/null | cut -d' ' -f1 || echo 'skip')
        _h5=$(sha256sum "$dir/gradle.properties" 2>/dev/null | cut -d' ' -f1 || echo 'skip')
    else
        _h1="skip"; _h2="skip"; _h3="skip"; _h4="skip"; _h5="skip"
    fi
    cat > "$dir/.build-config.json" << EOF
{
  "version": 1,
  "gradle": "${GRADLE_VERSION}",
  "agp": "${AGP_VERSION}",
  "kotlin": "${KOTLIN_VERSION}",
  "compileSdk": ${COMPILE_SDK},
  "minSdk": ${MIN_SDK},
  "targetSdk": ${TARGET_SDK},
  "hashes": {
    "gradle/wrapper/gradle-wrapper.properties": "${_h1}",
    "build.gradle.kts": "${_h2}",
    "app/build.gradle.kts": "${_h3}",
    "settings.gradle.kts": "${_h4}",
    "gradle.properties": "${_h5}"
  }
}
EOF
    chmod 444 "$dir/gradle/wrapper/gradle-wrapper.properties" \
             "$dir/build.gradle.kts" \
             "$dir/app/build.gradle.kts" \
             "$dir/settings.gradle.kts" \
             "$dir/gradle.properties" \
             "$dir/.build-config.json" 2>/dev/null || true
}

# ── 恢复函数 ──
restore_from_manifest() {
    local dir="$1"
    local restored=0
    local config="$dir/.build-config.json"
    [ -f "$config" ] || return 0

    grep -o '"[a-z./-]*\.\(properties\|kts\)": "[a-f0-9]\{64\}"' "$config" \
        > /tmp/.aidv-restore-$$.txt 2>/dev/null || true
    if [ -s /tmp/.aidv-restore-$$.txt ]; then
        while IFS= read -r entry; do
            fname=$(echo "$entry" | sed 's/":.*//;s/"//g')
            expected=$(echo "$entry" | sed 's/.*": "//;s/"$//')
            actual=$(sha256sum "$dir/$fname" 2>/dev/null | cut -d' ' -f1) || true
            if [ "$actual" != "$expected" ] && [ "$expected" != "skip" ]; then
                chmod +w "$dir/$fname" 2>/dev/null || true
                case "$fname" in
                    gradle.properties)
                        for kv in "android.aapt2DaemonMode=false" \
                                  "android.useAndroidX=true" \
                                  "android.nonTransitiveRClass=true"; do
                            if ! grep -q "$kv" "$dir/$fname" 2>/dev/null; then
                                echo "$kv" >> "$dir/$fname"
                            fi
                        done
                        ;;
                    gradle/wrapper/gradle-wrapper.properties)
                        cat > "$dir/$fname" << GWP_EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip
GWP_EOF
                        ;;
                esac
                echo "  ⚠ 自动恢复: $fname 已从基线恢复" >&2
                restored=$((restored + 1))
            fi
        done < /tmp/.aidv-restore-$$.txt
    fi
    rm -f /tmp/.aidv-restore-$$.txt
    return $restored
}

# ── 解析参数 ──
PROJECT_DIR=""
GRADLE_ARGS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT_DIR="$2"; shift 2 ;;
    --project=*) PROJECT_DIR="${1#*=}"; shift ;;
    -h|--help)
      echo "用法: aidev-build-request [--project <目录>] [Gradle 参数...]"
      echo "  --project <dir>  项目目录（默认自动检测）"
      echo "  其余参数透传给 Gradle"
      echo ""
      echo "例子:"
      echo "  aidev-build-request --project MyApp assembleDebug"
      echo "  aidev-build-request assembleDebug              # 当前目录"
      exit 0 ;;
    --validate-test)
        shift
        # 隐藏测试模式：校验配置后退出，不执行构建
        validate_build_config "$1" >&2 && exit 0 || exit $?
        ;;
    --unlock-project)
        shift
        rm -f "$1/.build-config.json" 2>/dev/null || true
        for f in "$1/gradle/wrapper/gradle-wrapper.properties" \
                 "$1/build.gradle.kts" \
                 "$1/app/build.gradle.kts" \
                 "$1/settings.gradle.kts" \
                 "$1/gradle.properties"; do
            chmod +w "$f" 2>/dev/null || true
        done
        echo "已解锁: $1" >&2
        exit 0 ;;
    --lock-project)
        shift
        lock_project "$1"
        echo "已锁定: $1" >&2
        exit 0 ;;
    --heal-test)
        shift
        auto_heal "$1" >&2
        # 验证修复结果
        local healed=0
        [ -f "$1/gradle.properties" ] && grep -q "android.aapt2DaemonMode=false" "$1/gradle.properties" || healed=$((healed + 1))
        [ -f "$1/gradle/wrapper/gradle-wrapper.properties" ] && grep -q "gradle-${GRADLE_VERSION}-bin.zip" "$1/gradle/wrapper/gradle-wrapper.properties" || healed=$((healed + 1))
        [ "$healed" -eq 0 ] && echo "auto_heal: OK" >&2 || echo "auto_heal: FAILED" >&2
        exit $healed ;;
    --) shift; GRADLE_ARGS="$GRADLE_ARGS $*"; break ;;
    *) GRADLE_ARGS="$GRADLE_ARGS $1"; shift ;;
  esac
done

# ── 自动检测项目目录 ──
if [ -z "$PROJECT_DIR" ]; then
  case "$0" in
    *gradlew) PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)" ;;
    *)
      if [ -f "./gradlew.real" ]; then
        PROJECT_DIR="$(pwd)"
      else
        echo "错误: 未指定项目目录，且当前目录未找到 gradlew.real" >&2
        echo "用法: aidev-build-request --project <目录> [Gradle 参数...]" >&2
        exit 2
      fi
      ;;
  esac
fi

PROJECT_DIR="$(cd "$PROJECT_DIR" 2>/dev/null && pwd)" || {
  echo "错误: 项目目录不存在: $PROJECT_DIR" >&2; exit 2
}

GRADLEW_REAL="$PROJECT_DIR/gradlew.real"
if [ ! -f "$GRADLEW_REAL" ]; then
  if [ -f "$PROJECT_DIR/gradlew" ]; then
    # 首次使用：自动迁移——原 gradlew → gradlew.real，创建包装器
    mv "$PROJECT_DIR/gradlew" "$PROJECT_DIR/gradlew.real"
    chmod +x "$PROJECT_DIR/gradlew.real"
    cat > "$PROJECT_DIR/gradlew" <<'GRADLEW_EOF'
#!/bin/sh
exec /usr/local/bin/aidev-build-request --project "$(cd "$(dirname "$0")" && pwd)" "$@"
GRADLEW_EOF
    chmod +x "$PROJECT_DIR/gradlew"
  else
    echo "错误: 未找到 gradlew.real（真实 Gradle 包装器）" >&2
    exit 2
  fi
fi

# ── 自愈 + 校验 ──
auto_heal "$PROJECT_DIR"
if [ ! -f "$PROJECT_DIR/.build-config.json" ]; then
    lock_project "$PROJECT_DIR"
    echo "  ✓ 项目配置已锁定（首次构建自动生成基线）" >&2
fi
if ! validate_build_config "$PROJECT_DIR"; then
    restore_from_manifest "$PROJECT_DIR"
    auto_heal "$PROJECT_DIR"
    validate_build_config "$PROJECT_DIR" || exit 2
fi

# ── 初始化 ──
PROJECT_NAME="$(basename "$PROJECT_DIR")"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="/sdcard/AIDev/logs/$PROJECT_NAME"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/build-$TIMESTAMP.log"

AIDEV_HOME="${AIDEV_HOME:-/host-home}"
MARKER_FILE="$AIDEV_HOME/.build-running"

# 写构建标记 → KeepAliveService 监测到后持唤醒锁
echo "$PROJECT_DIR" > "$MARKER_FILE"
START_EPOCH="$(date +%s)"

cleanup() {
  rm -f "$MARKER_FILE"
}
trap cleanup EXIT

# ── Gradle 输出过滤 ──
filter_output() {
  while IFS= read -r line; do
    case "$line" in
      "> Task "*)
        task="${line#> Task }"
        task="${task% UP-TO-DATE}"
        task="${task% NO-SOURCE}"
        task="${task% FROM-CACHE}"
        task="${task% SKIPPED}"
        echo "▶ $task"
        ;;
      "w: "*)
        echo "  ⚠ ${line#w: }"
        ;;
      "e: "*)
        echo "  ✗ ${line#e: }"
        ;;
      *"BUILD SUCCESSFUL"*)
        echo "✓ ${line}"
        ;;
      *"BUILD FAILED"*)
        echo "✗ ${line}"
        ;;
      *"FAILURE: "*|*"error:"*)
        echo "  $line"
        ;;
      "Download "*)
        echo "  ↓ ${line#Download }"
        ;;
      *"could not be resolved"*|*"Could not resolve"*|*"Failed to resolve"*)
        echo "  ⚠ $line"
        ;;
      "")
        # empty line - skip
        ;;
      *)
        # Other output - show only if important looking
        case "$line" in
          "BUILD SUCCESSFUL"*|"BUILD FAILED"*) echo "  $line" ;;
          "Deprecated"*) echo "  ⚠ $line" ;;
          "Starting"*"Gradle"*) echo "  $line" ;;
          "To honour"*) ;; # Skip JVM memory warning noise
          "This JVM"*) ;;
          "Publications"*) ;;
          *) echo "  $line" ;;
        esac
        ;;
    esac
  done
}

# ── 执行构建 ──
set +e
chmod +x "$GRADLEW_REAL"

echo "═══ 构建开始: $(date) ═══" | tee -a "$LOG_FILE"
echo "项目: $PROJECT_DIR" | tee -a "$LOG_FILE"
echo "命令: $GRADLEW_REAL $GRADLE_ARGS" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# Gradle 输出 → 同时写入日志文件 + 终端过滤显示
"$GRADLEW_REAL" $GRADLE_ARGS 2>&1 | tee -a "$LOG_FILE" | filter_output

EXIT_CODE=$?
END_EPOCH="$(date +%s)"
DURATION=$((END_EPOCH - START_EPOCH))
set -e

# ── 检测 APK ──
APK_PATH=""
if [ "$EXIT_CODE" -eq 0 ]; then
  for candidate in \
    "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" \
    "$PROJECT_DIR/app/build/outputs/apk/debug/"*-debug.apk; do
    for f in $candidate; do
      if [ -f "$f" ]; then
        APK_PATH="$f"
        break 2
      fi
    done
  done
fi

# ── 写报告 ──(
echo "═══ 构建结束: $(date) ═══" >> "$LOG_FILE"
echo "状态: $([ "$EXIT_CODE" -eq 0 ] && echo 'SUCCESS' || echo 'FAILED')" >> "$LOG_FILE"
echo "耗时: $(printf '%d:%02d' $((DURATION/60)) $((DURATION%60)))" >> "$LOG_FILE"
[ -n "$APK_PATH" ] && echo "APK: $APK_PATH" >> "$LOG_FILE"

# 结构化结果 (.aidev-last-build.json)
RESULT_FILE="$PROJECT_DIR/.aidev-last-build.json"
{
  echo "{"
  echo "  \"success\": $( [ "$EXIT_CODE" -eq 0 ] && echo true || echo false ),"
  echo "  \"exitCode\": $EXIT_CODE,"
  echo "  \"duration\": $DURATION,"
  echo "  \"logFile\": \"$LOG_FILE\""
  if [ -n "$APK_PATH" ]; then
    echo "  ,\"apk\": \"$APK_PATH\""
  fi
  echo "}"
} > "$RESULT_FILE"

# ── Android 通知（仅 APK 构建成功或构建失败时发通知，跳过 test/compile 等非产物任务）──
if command -v sysnotify >/dev/null 2>&1; then
  if [ "$EXIT_CODE" -eq 0 ] && [ -n "$APK_PATH" ]; then
    DUR_STR=$(printf '%d:%02d' $((DURATION/60)) $((DURATION%60)))
    MSG="✓ $DUR_STR"
    APK_SIZE=$(du -h "$APK_PATH" 2>/dev/null | cut -f1)
    MSG="$MSG · $(basename "$APK_PATH") (${APK_SIZE:-?})"
    sysnotify "$PROJECT_NAME 构建成功" "$MSG" 2>/dev/null || true
  elif [ "$EXIT_CODE" -ne 0 ]; then
    sysnotify --priority high "$PROJECT_NAME 构建失败" "exit=$EXIT_CODE · 耗时 $(printf '%d:%02d' $((DURATION/60)) $((DURATION%60)))" 2>/dev/null || true
  fi
fi

# ── 终端摘要 ──
echo ""
echo "═══ 构建报告 ═══"
echo "  结果: $( [ "$EXIT_CODE" -eq 0 ] && echo '✓ 成功' || echo '✗ 失败' )"
echo "  耗时: $(printf '%d分%02d秒' $((DURATION/60)) $((DURATION%60)))"
echo "  日志: $LOG_FILE"
if [ -n "$APK_PATH" ]; then
  echo "  APK: $APK_PATH"
fi

exit "$EXIT_CODE"
