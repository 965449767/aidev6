#!/bin/sh
# aidev-autoinstall: Shizuku 静默安装 APK
# 在 Android 项目目录内自动安装当前项目产出物；在项目外需指定 APK 路径。
# 仅支持 Shizuku 静默安装，失败直接报错退出（无系统安装器降级）。
# 用法: aidev-autoinstall [options] [apk_path]
set -e

APK_PATH=""
CLEANUP=false
LAUNCH_PKG=""
OUTPUT_JSON=false
IS_PROJECT=false
PROJECT_ROOT=""

INSTALL_OK=false
LAUNCH_OK=false
ACTIVITY=null
ERR_MSG=""

usage() {
    cat <<EOF
用法: aidev-autoinstall [选项] [APK路径]

选项:
  --launch <包名>      安装后启动应用
  --uninstall, -u      静默卸载应用: aidev-autoinstall --uninstall <包名>
  --status             检查 Shizuku 桥接状态
  --json               输出结构化 JSON（机器可读）
  -h, --help           显示帮助

路径策略（智能）:
  在 Android 项目根目录下执行 → 自动安装当前项目的构建产物
  在项目目录外执行 → 必须指定 APK 路径

安装策略:
  1. 检测 Shizuku 桥接状态
  2. 桥接正常 → 静默安装（Shizuku）
  3. 失败 → 输出错误信息退出

示例:
  aidev-autoinstall                         # 在项目目录内自动安装
  aidev-autoinstall /sdcard/app-debug.apk   # 安装指定 APK
  aidev-autoinstall --launch com.example.app app-debug.apk  # 安装并启动
  aidev-autoinstall --uninstall com.example # 卸载应用
EOF
    exit 0
}

emit_json() {
    local installed="$1" launched="$2" activity="$3" err="${4:-}"
    local err_json
    if [ -z "$err" ]; then err_json="null"; else err_json="\"$err\""; fi
    printf '{"apk":"%s","pkg":"%s","installed":%s,"launched":%s,"activity":%s,"error":%s}\n' \
        "$APK_PATH" "$LAUNCH_PKG" "$installed" "$launched" "$activity" "$err_json"
}

clean_output() {
    grep -vE '^Shizuku 请求已发送$|^命令: |^等待执行结果' | sed 's/\r$//'
}

find_project_root() {
    local dir="$PWD"
    while [ "$dir" != "/" ]; do
        if [ -f "$dir/app/build.gradle.kts" ] || [ -f "$dir/app/build.gradle" ]; then
            echo "$dir"
            return 0
        fi
        dir=$(dirname "$dir")
    done
    return 1
}

is_valid_apk() {
    local f="$1"
    [ -f "$f" ] || return 1
    local size
    size=$(stat -c%s "$f" 2>/dev/null) || return 1
    [ "$size" -gt 102400 ] || return 1
    local hex
    hex=$(od -A n -t x1 -N 4 "$f" 2>/dev/null | tr -d ' \n')
    [ "$hex" = "504b0304" ] || return 1
    return 0
}

discover_project_apk() {
    for pattern in \
        "$PROJECT_ROOT/app/build/outputs/apk/debug/"*.apk \
        "$PROJECT_ROOT/app/build/outputs/apk/"*.apk; do
        for f in $pattern; do
            case "$f" in *.apk) ;; *) continue ;; esac
            if [ -f "$f" ] && is_valid_apk "$f"; then
                echo "$f"
                return 0
            fi
        done
    done
    echo ""
    return 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --launch) LAUNCH_PKG="$2"; shift 2 ;;
        --uninstall|-u) shift; PKG_UN="$1"; echo "$PKG_UN" | grep -qE '^[a-zA-Z0-9._]+$' || { echo "错误: 非法包名"; exit 1; }; exec aidev-shizuku exec "pm uninstall -k --user 0 '$PKG_UN'" ;;
        --status) exec aidev-shizuku status ;;
        --json) OUTPUT_JSON=true; shift ;;
        -h|--help) usage ;;
        *) APK_PATH="$1"; shift ;;
    esac
done

# 统一 EXIT trap：JSON 输出 + 临时文件清理（无论成功/失败/异常退出均触发）
trap '
    if [ "$OUTPUT_JSON" = true ] && [ "$INSTALL_OK" != true ]; then
        emit_json false false null "aidev-autoinstall 异常退出(rc=$?)"
    fi
    if [ "$CLEANUP" = true ]; then
        rm -f "$TMP_APK" 2>/dev/null || true
    fi
' EXIT

# ── APK 路径发现 ───────────────────────────────────────
if [ -z "$APK_PATH" ]; then
    PROJECT_ROOT=$(find_project_root) || true
    if [ -n "$PROJECT_ROOT" ]; then
        IS_PROJECT=true
        APK_PATH=$(discover_project_apk) || true
        if [ -z "$APK_PATH" ]; then
            [ "$OUTPUT_JSON" = true ] && { emit_json false false null "在项目 $PROJECT_ROOT 中未找到构建产物"; exit 1; }
            echo "错误: 在项目 $PROJECT_ROOT 中未找到 APK 构建产物。"
            echo "请先执行 aidev-build-request --project \"$PROJECT_ROOT\" 构建项目。"
            exit 1
        fi
        [ "$OUTPUT_JSON" != true ] && echo "→ 检测到 Android 项目: $(basename "$PROJECT_ROOT")"
    else
        [ "$OUTPUT_JSON" = true ] && { emit_json false false null "非项目目录，必须指定 APK 路径"; exit 1; }
        echo "错误: 当前目录不是 Android 项目目录（未找到 app/build.gradle.kts）。"
        echo "请指定 APK 路径:"
        echo "  aidev-autoinstall /sdcard/your-app.apk"
        echo "  aidev-autoinstall --launch com.example.app /sdcard/your-app.apk"
        exit 1
    fi
fi

if [ ! -f "$APK_PATH" ]; then
    [ "$OUTPUT_JSON" = true ] && { emit_json false false null "找不到 APK 文件: $APK_PATH"; exit 1; }
    echo "错误: 找不到 APK 文件: $APK_PATH"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" 2>/dev/null | cut -f1)
[ "$OUTPUT_JSON" != true ] && echo "APK: $(basename "$APK_PATH") ($APK_SIZE)"

# ── 复制到 /sdcard（如不在共享存储）─────────────────────
case "$APK_PATH" in
    /sdcard/*|/storage/emulated/*) : ;;
    *)
        TMP_APK=$(mktemp -p /sdcard aidev-autoinstall-XXXXXXXX.apk 2>/dev/null) || {
            [ "$OUTPUT_JSON" = true ] && { emit_json false false null "无法在 /sdcard 创建临时文件"; exit 1; }
            echo "错误: 无法在 /sdcard 创建临时文件"
            exit 1
        }
        [ "$OUTPUT_JSON" != true ] && echo "→ 复制到 $TMP_APK"
        if ! cp "$APK_PATH" "$TMP_APK" 2>/dev/null; then
            [ "$OUTPUT_JSON" != true ] && echo "  → PRoot 内复制失败，通过 Shizuku 桥接复制..."
            apk_esc=$(printf '%s' "$APK_PATH" | sed "s/'/'\\\\''/g")
            tmp_apk_esc=$(printf '%s' "$TMP_APK" | sed "s/'/'\\\\''/g")
            aidev-shizuku exec "cp '$apk_esc' '$tmp_apk_esc'" || {
                [ "$OUTPUT_JSON" = true ] && { emit_json false false null "无法读取 APK 文件: $APK_PATH"; exit 1; }
                echo "错误: 无法读取 APK 文件: $APK_PATH"
                exit 1
            }
        fi
        APK_PATH="$TMP_APK"
        CLEANUP=true
        ;;
esac

shizuku_available() {
    if command -v aidev-shizuku >/dev/null 2>&1; then
        if aidev-shizuku status >/dev/null 2>&1; then
            return 0
        fi
    fi
    hb_req="/host-home/.aidev-shizuku-bridge/request/hb_$$"
    hb_res="/host-home/.aidev-shizuku-bridge/result/hb_$$"
    mkdir -p "$(dirname "$hb_req")" "$(dirname "$hb_res")"
    cat > "$hb_req" <<EOF
TYPE=exec
COMMAND=echo aidev-autoinstall-ok
EOF
    waited=0
    while [ ! -s "$hb_res" ] && [ "$waited" -lt 5 ]; do
        sleep 1; waited=$((waited + 1))
    done
    if [ -s "$hb_res" ] && grep -q "aidev-autoinstall-ok" "$hb_res" 2>/dev/null; then
        rm -f "$hb_req" "$hb_res"
        return 0
    fi
    rm -f "$hb_req" "$hb_res" 2>/dev/null || true
    return 1
}

install_silent() {
    [ "$OUTPUT_JSON" != true ] && echo "→ 静默安装 (Shizuku)..."
    out=""
    rc=0
    set +e
    out=$(aidev-shizuku install "$APK_PATH" 2>&1)
    rc=$?
    set -e
    # 输出完整日志以便排查
    echo "$out" >&2
    if [ "$rc" -ne 0 ]; then
        ERR_MSG="Shizuku 安装失败 (rc=$rc): $(echo "$out" | tail -5 | tr '\n' ' ')"
        return 1
    fi
    if echo "$out" | grep -qiE "Failure|INSTALL_FAILED|error|Exception"; then
        reason=$(echo "$out" | grep -iE "Failure|INSTALL_FAILED|error|Exception" | head -1)
        ERR_MSG="安装被系统拒绝: $reason"
        return 1
    fi
    return 0
}

# ── 主流程 ─────────────────────────────────────────────
if ! shizuku_available; then
    ERR_MSG="Shizuku 未就绪，无法静默安装"
    [ "$OUTPUT_JSON" != true ] && echo "错误: Shizuku 未就绪，无法静默安装。"
    [ "$OUTPUT_JSON" != true ] && echo "请先在 AIDev 应用内确保 Shizuku 桥接正常（aidev-shizuku status）。"
    if [ "$OUTPUT_JSON" = true ]; then emit_json false false null "$ERR_MSG"; fi
    exit 1
fi

if install_silent; then
    INSTALL_OK=true
    [ "$OUTPUT_JSON" != true ] && echo "✓ 静默安装完成"
else
    [ "$OUTPUT_JSON" != true ] && echo "✗ 静默安装失败: $ERR_MSG"
    if [ "$OUTPUT_JSON" = true ]; then emit_json false false null "$ERR_MSG"; fi
    exit 1
fi

# Launch if requested
if [ -n "$LAUNCH_PKG" ]; then
    if command -v aidev-shizuku >/dev/null 2>&1; then
        [ "$OUTPUT_JSON" != true ] && echo "→ 启动 $LAUNCH_PKG ..."
        for l in 1 2 3; do
            COMP=$(aidev-shizuku exec "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER --user 0 '$LAUNCH_PKG' 2>/dev/null | tail -1" 2>/dev/null | clean_output | grep "/" | tail -1 | tr -d '\r' | xargs)
            [ -n "$COMP" ] && ACTIVITY="$COMP"
            OUT=$(aidev-shizuku launch "$LAUNCH_PKG" 2>/dev/null | clean_output)
            if ! echo "$OUT" | grep -qiE "Error:|No activities|Exception"; then
                LAUNCH_OK=true
                break
            fi
            [ "$OUTPUT_JSON" != true ] && echo "启动尝试 $l 未确认，1s 后重试..."
            sleep 1
        done
        if [ "$LAUNCH_OK" = true ]; then
            [ "$OUTPUT_JSON" != true ] && echo "✓ 应用已启动"
        else
            [ "$OUTPUT_JSON" != true ] && echo "⚠ 安装成功但启动失败: $(echo "$OUT" | head -c 200)"
        fi
    fi
fi

if [ "$OUTPUT_JSON" = true ]; then
    emit_json "$INSTALL_OK" "$LAUNCH_OK" "$ACTIVITY" "$ERR_MSG"
fi
exit 0
