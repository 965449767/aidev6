#!/bin/sh
# aidev-anr: 读取 ANR traces
# 用法: aidev-anr [list|latest|<name>|summary|clear]
set -e

SHIZUKU="aidev-shizuku"
ANR_DIR="/data/anr"

. "$(dirname "$0")/lib/shizuku-common.sh"

usage() {
    echo "用法: aidev-anr [子命令]"
    echo "  list                列出 ANR traces 文件"
    echo "  latest              读取最新的 ANR trace"
    echo "  <name>              读取指定 ANR 文件"
    echo "  summary             摘要（进程名、关键堆栈）"
    echo "  clear               删除所有 ANR traces"
}

cmd_list() {
    local list
    list=$(shizuku_ls "$ANR_DIR")
    if [ -z "$list" ]; then
        echo "无 ANR traces (目录为空或不可访问)"
        return
    fi
    echo "ANR traces:"
    echo "$list" | while IFS= read -r line; do
        echo "  $line"
    done
}

cmd_latest() {
    local latest
    latest=$(shizuku_ls "$ANR_DIR" | grep -v '^$' | head -1 | awk '{for(i=NF;i>0;i--) if($i!=""){print $i; exit}}' 2>/dev/null)
    [ -z "$latest" ] && { echo "无 ANR traces"; return 1; }
    echo "=== 最新的 ANR: $latest ==="
    shizuku_cat "$ANR_DIR/$latest"
}

cmd_read() {
    local name="$1"
    shizuku_cat "$ANR_DIR/$name" 2>/dev/null || echo "ANR 文件不存在: $name"
}

cmd_summary() {
    local latest
    latest=$(shizuku_ls "$ANR_DIR" | grep -v '^$' | head -1 | awk '{for(i=NF;i>0;i--) if($i!=""){print $i; exit}}' 2>/dev/null)
    [ -z "$latest" ] && { echo "无 ANR traces"; return 1; }

    local content
    content=$(shizuku_cat "$ANR_DIR/$latest")

    echo "=== ANR 摘要: $latest ==="
    echo ""
    echo "$content" | grep -i "pid:" | head -3
    echo "$content" | grep -i "process:" | head -3
    echo "$content" | grep -i "signal|reason|subject" | head -5 || true
    echo ""
    echo "--- 主线程堆栈 (top 15) ---"
    echo "$content" | awk '/main\(|"main"|main\)/{found=1} found{print; count++; if(count>20) exit}' | head -20
}

cmd_clear() {
    echo -n "确定删除所有 ANR traces? [y/N] "
    read -r ans
    case "$ans" in
        [Yy]|[Yy][Ee][Ss]) ;;
        *) echo "已取消"; exit 0 ;;
    esac
    $SHIZUKU exec "find $ANR_DIR -type f -delete" 2>/dev/null || true
    echo "ANR traces 已删除"
}

case "${1:-help}" in
    list)      cmd_list ;;
    latest)    cmd_latest ;;
    summary)   cmd_summary ;;
    clear)     cmd_clear ;;
    help|--help|-h) usage ;;
    *)         cmd_read "$1" ;;
esac
