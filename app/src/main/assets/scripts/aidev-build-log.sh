#!/bin/sh
# aidev-build-log: 读取本地构建日志（宿主写入 /sdcard/AIDev/logs/<项目>/build.log）
#
# 构建在宇宙 B（编译器 rootfs）内进行，但构建日志由宿主实时落盘到本地
# /sdcard/AIDev/logs/<项目名>/build.log（universe A 的 PRoot 已绑定 /sdcard，
# 故宇宙 A 内可直接读取，无需跨宇宙回传）。
#
# 用法:
#   aidev-build-log <project>           打印指定项目的完整构建日志
#   aidev-build-log <project> --tail N  仅打印末尾 N 行（默认 80）
#   aidev-build-log latest             列出最近修改的构建日志目录
#   aidev-build-log list               同上（别名）
#   aidev-build-log -h|--help          显示帮助
#
# 说明: <project> 为项目目录名（basename），如 DebugTest；也可传 /workspace/DebugTest，
#       会自动取 basename。找不到时尝试 /workspace/<name>/build.log 兜底。
set -e

LOG_ROOT="/sdcard/AIDev/logs"

usage() {
    cat <<'HELP'
用法: aidev-build-log <project> [--tail N]
      aidev-build-log latest|list        列出最近构建日志
      aidev-build-log -h                  显示帮助

  读取宿主落盘的本地构建日志（/sdcard/AIDev/logs/<项目>/build.log）。
  构建在宇宙 B 进行，但日志实时写回本地，宇宙 A 可直接读取。
HELP
    exit 0
}

case "${1:-}" in
    -h|--help) usage ;;
    latest|list)
        if [ ! -d "$LOG_ROOT" ]; then
            echo "无日志目录: $LOG_ROOT"
            exit 1
        fi
        echo "=== 最近构建日志（按修改时间）==="
        ls -lt "$LOG_ROOT" 2>/dev/null | awk 'NR>1 && $NF!="." {print $NF}' | while read -r d; do
            p="$LOG_ROOT/$d/build.log"
            if [ -f "$p" ]; then
                printf "  %-30s %s\n" "$d" "$(wc -l < "$p" 2>/dev/null | tr -d ' ') 行"
            fi
        done
        exit 0
        ;;
esac

PROJECT="${1:-}"
TAIL=80
shift 2>/dev/null || true
while [ $# -gt 0 ]; do
    case "$1" in
        --tail) TAIL="${2:-80}"; shift 2 ;;
        *) shift ;;
    esac
done

if [ -z "$PROJECT" ]; then
    echo "错误: 缺少项目名称"
    echo "用法: aidev-build-log <project> [--tail N]"
    exit 1
fi

# 规范化：取 basename，兼容 /workspace/xxx
NAME=$(printf '%s' "$PROJECT" | sed 's#^/workspace/##; s#^/##; s#.*/##')
[ -z "$NAME" ] && NAME="$PROJECT"

CANDIDATES="
$LOG_ROOT/$NAME/build.log
/workspace/$NAME/build.log
"

RESOLVED=""
IFS="
"
for cand in $CANDIDATES; do
    cand=$(printf '%s' "$cand" | tr -d ' \t')
    [ -z "$cand" ] && continue
    if [ -f "$cand" ]; then RESOLVED="$cand"; break; fi
done
unset IFS

if [ -z "$RESOLVED" ]; then
    echo "未找到构建日志: $LOG_ROOT/$NAME/build.log"
    echo "提示: 项目是否已构建？用 aidev-build-request --project $NAME 触发；"
    echo "      或 aidev-build-log list 查看已有日志。"
    exit 1
fi

echo "═══ 构建日志: $RESOLVED ═══"
if [ "$TAIL" -gt 0 ] 2>/dev/null; then
    tail -n "$TAIL" "$RESOLVED"
else
    cat "$RESOLVED"
fi
