#!/bin/bash
# aidev-verify-run: 运行验证黑盒（设备侧 / Shizuku 桥）
#
# 职责：窗口内监控目标包是否崩溃 / ANR，返回【确定结论】。
# 这是「改码 → 构建 → 运行 → 确认不崩」闭环里最脆的一环，原本靠
# "等几秒 + 手查 logs/<pkg>/crash.log" 完成，不可靠。本命令把它封装成标准黑盒：
#
#   标准入口: aidev-verify-run --pkg <包名> [--window <秒>] [--launch]
#   标准出口: 结构化 JSON 打到 stdout，并写入崩溃日志（若崩溃）
#     { "pkg": "...", "running": true/false, "crashed": true/false,
#       "crash_log_path": "<绝对路径>|null", "window_ms": 8000, "error": "..." }
#
# 内部实现：委托 aidev-logcat --watch-crash（已走 Shizuku 桥）在窗口内监听，
# 捕获 FATAL EXCEPTION / ANR / Native crash / Process died 即判 crashed=true。

set -uo pipefail

PKG=""
WINDOW=8
DO_LAUNCH=false
LOGS_BASE="/sdcard/AIDev/logs"

while [ $# -gt 0 ]; do
    case "$1" in
        --pkg) PKG="$2"; shift 2 ;;
        --window) WINDOW="$2"; shift 2 ;;
        --launch) DO_LAUNCH=true; shift ;;
        --help|-h)
            echo "用法: aidev-verify-run --pkg <包名> [--window <秒>] [--launch]"
            echo ""
            echo "  --pkg <包名>   要监控的应用包名（必填）"
            echo "  --window <秒>  崩溃监控窗口，默认 8 秒"
            echo "  --launch       监控前先尝试启动应用（经 startapp）"
            echo ""
            echo "标准出口: stdout 打印 JSON {pkg,running,crashed,crash_log_path,window_ms,error}"
            exit 0 ;;
        *) shift ;;
    esac
done

emit() {
    # $1 = crashed(bool)  $2 = crash_log_path(|null)  $3 = running(bool)  $4 = error(|"")
    local crashed="$1" path="$2" running="$3" err="${4:-}"
    local elapsed=$(( ($(date +%s) - START) * 1000 ))
    local err_json
    if [ -z "$err" ]; then err_json="null"; else err_json="\"$err\""; fi
    printf '{"pkg":"%s","running":%s,"crashed":%s,"crash_log_path":%s,"window_ms":%d,"error":%s}\n' \
        "$PKG" "$running" "$crashed" "$path" "$elapsed" "$err_json"
}

if [ -z "$PKG" ]; then
    printf '{"pkg":null,"running":false,"crashed":false,"crash_log_path":null,"window_ms":0,"error":"missing --pkg"}\n'
    exit 1
fi

START=$(date +%s)

# 可选：监控前先启动
if [ "$DO_LAUNCH" = true ]; then
    startapp "$PKG" 2>/dev/null || aidev-shizuku exec "monkey -p $PKG -c android.intent.category.LAUNCHER 1" 2>/dev/null || true
    sleep 1
fi

# 运行态探测（尽力而为，失败不致命）
RUNNING=false
if pidof "$PKG" >/dev/null 2>&1; then
    RUNNING=true
elif aidev-shizuku exec "pidof $PKG" 2>/dev/null | grep -q .; then
    RUNNING=true
fi

# 后台用 aidev-logcat --watch-crash 监听窗口内崩溃
CRASH_OUT=$(mktemp)
timeout "$WINDOW" aidev-logcat --watch-crash --package "$PKG" > "$CRASH_OUT" 2>/dev/null
RC=$?

if [ "$RC" -eq 0 ] && [ -s "$CRASH_OUT" ]; then
    # 窗口内检测到崩溃
    TS=$(date +%s)
    mkdir -p "$LOGS_BASE/$PKG"
    CRASH_LOG="$LOGS_BASE/$PKG/crash-verify-$TS.log"
    cp "$CRASH_OUT" "$CRASH_LOG"
    rm -f "$CRASH_OUT"
    emit true "\"$CRASH_LOG\"" true
    exit 0
fi

# 超时（RC=124）或中途异常：窗口内未捕获崩溃
rm -f "$CRASH_OUT"
if [ "$RUNNING" = false ]; then
    # 应用根本没在跑：无法判定"运行不崩"，如实回报
    emit false null false "app not running during window (deploy/launch may have failed)"
    exit 0
fi
emit false null true
exit 0
