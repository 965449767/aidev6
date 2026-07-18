#!/bin/sh
# aidev-install: 安装 APK，支持静默（Shizuku）/标准（系统安装器）模式
# 用法: aidev-install [options] [apk_path]
set -e

INSTALL_MODE="auto"
APK_PATH=""
CLEANUP=false

usage() {
    cat <<EOF
用法: aidev-install [选项] [APK路径]

选项:
  --silent, -s     强制静默安装（Shizuku，需授权）
  --gui, -g        强制使用系统安装界面
  --uninstall, -u  静默卸载应用: aidev-install --uninstall <包名>
  --status         检查 Shizuku 桥接状态
  -h, --help       显示帮助

示例:
  aidev-install                          # 自动发现 APK，优先静默安装
  aidev-install app-debug.apk            # 安装指定 APK
  aidev-install --silent ./app-debug.apk # 强制静默安装
  aidev-install --gui /sdcard/demo.apk   # 强制系统安装界面
  aidev-install --uninstall com.example  # 卸载应用
EOF
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --silent|-s|silent) INSTALL_MODE="silent"; shift ;;
        --gui|-g|gui) INSTALL_MODE="gui"; shift ;;
        --uninstall|-u) shift; PKG_UN="$1"; echo "$PKG_UN" | grep -qE '^[a-zA-Z0-9._]+$' || { echo "错误: 非法包名"; exit 1; }; exec aidev-shizuku exec "pm uninstall -k --user 0 '$PKG_UN'" ;;
        --status) exec aidev-shizuku status ;;
        -h|--help) usage ;;
        *) APK_PATH="$1"; shift ;;
    esac
done

auto_discover_apk() {
    for pattern in \
        "/root/Workspace/"*"/app/build/outputs/apk/debug/"*.apk \
        "$PWD/app/build/outputs/apk/debug/"*.apk \
        /sdcard/Download/*.apk \
        /sdcard/*.apk; do
        for f in $pattern; do
            [ -f "$f" ] && { echo "$f"; return 0; }
        done
    done
    return 1
}

if [ -z "$APK_PATH" ]; then
    APK_PATH=$(auto_discover_apk) || true
fi

if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
    echo "错误: 找不到 APK 文件。请指定路径或先构建: aidev-build-request && aidev-install"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" 2>/dev/null | cut -f1)
echo "APK: $(basename "$APK_PATH") ($APK_SIZE)"

case "$APK_PATH" in
    /sdcard/*|/storage/emulated/*) : ;;
    *)
        TMP_APK=$(mktemp -p /sdcard aidev-install-XXXXXXXX.apk 2>/dev/null) || {
            echo "错误: 无法在 /sdcard 创建临时文件"
            exit 1
        }
        echo "→ 复制到 $TMP_APK"
    if ! cp "$APK_PATH" "$TMP_APK" 2>/dev/null; then
        echo "  → PRoot 内复制失败，通过 Shizuku 桥接复制..."
        # shell 转义路径再传给 shizuku exec，防止空格/元字符注入
    apk_esc tmp_apk_esc
        apk_esc=$(printf '%s' "$APK_PATH" | sed "s/'/'\\\\''/g")
        tmp_apk_esc=$(printf '%s' "$TMP_APK" | sed "s/'/'\\\\''/g")
        aidev-shizuku exec "cp '$apk_esc' '$tmp_apk_esc'" || {
            echo "错误: 无法读取 APK 文件: $APK_PATH"
            exit 1
        }
    fi
    APK_PATH="$TMP_APK"
    CLEANUP=true
    ;;
esac

cleanup() {
    [ "$CLEANUP" = true ] && rm -f "$TMP_APK" 2>/dev/null || true
}
trap cleanup EXIT

shizuku_heartbeat() {
    # 主探测走 socket 通道（与 aidev-shizuku status 同路径，已在终端验证可用）。
    if command -v aidev-shizuku >/dev/null 2>&1; then
        if aidev-shizuku status >/dev/null 2>&1; then
            return 0
        fi
    fi
    # 兜底：文件通道心跳（部分环境 /host-home 与宿主 home 不完全一致时可能不通）。
    hb_req="/host-home/.aidev-shizuku-bridge/request/hb_$$"
    hb_res="/host-home/.aidev-shizuku-bridge/result/hb_$$"
    mkdir -p "$(dirname "$hb_req")" "$(dirname "$hb_res")"
    cat > "$hb_req" <<EOF
TYPE=exec
COMMAND=echo aidev-install-ok
EOF
    waited=0
    while [ ! -s "$hb_res" ] && [ "$waited" -lt 5 ]; do
        sleep 1; waited=$((waited + 1))
    done
    if [ -s "$hb_res" ] && grep -q "aidev-install-ok" "$hb_res" 2>/dev/null; then
        rm -f "$hb_req" "$hb_res"
        return 0
    fi
    rm -f "$hb_req" "$hb_res" 2>/dev/null || true
    return 1
}

install_silent() {
    echo "→ 静默安装 (Shizuku)..."
    out=""
    rc=0
    set +e
    out=$(aidev-shizuku install "$APK_PATH" 2>&1)
    rc=$?
    set -e
    echo "$out"
    if [ "$rc" -ne 0 ]; then
        echo "错误: Shizuku 安装桥返回非零 (rc=$rc)"
        exit 1
    fi
    if echo "$out" | grep -qiE "Failure|INSTALL_FAILED|error|Exception"; then
        reason=""
        reason=$(echo "$out" | grep -iE "Failure|INSTALL_FAILED|error|Exception" | head -1)
        echo "错误: 安装被系统拒绝: $reason"
        exit 1
    fi
    echo "✓ 静默安装完成"
}

install_gui() {
    # 无头终端环境不支持系统安装界面（需人工点击，且 .aidev-cmd 通道已废弃）。
    echo "错误: GUI 系统安装界面在无头终端中不可用，无法完成安装。"
    echo "请改用 --silent（Shizuku 静默安装），并确保 Shizuku 已启动且已授权 AIDev。"
    exit 1
}

shizuku_unavailable() {
    echo "错误: Shizuku 未响应/未授权，无法静默安装。"
    echo "请按以下步骤排查："
    echo "  1) 打开 Shizuku App，确认其已启动（通常通过「无线调试」或已 root 方式启动）。"
    echo "  2) 在 Shizuku 的「已授权应用」中确认已包含 AIDev (com.aidev.six.dev)。"
    echo "  3) 在 AIDev 终端中执行「aidev-shizuku status」确认桥接通道正常。"
    echo "  4) 若仍失败，可在 AIDev 应用内「项目」页使用应用内安装（带清晰错误提示）。"
    exit 1
}

case "$INSTALL_MODE" in
    silent)
        install_silent
        ;;
    gui)
        install_gui
        ;;
    auto)
        if shizuku_heartbeat; then
            install_silent
        else
            shizuku_unavailable
        fi
        ;;
esac
