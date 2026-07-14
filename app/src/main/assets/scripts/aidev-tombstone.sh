#!/bin/sh
# aidev-tombstone: 读取原生崩溃 tombstone
# 用法: aidev-tombstone [list|latest|<n>|clear]
set -e

SHIZUKU="aidev-shizuku"
TS_DIR="/data/tombstones"

usage() {
    echo "用法: aidev-tombstone [子命令]"
    echo "  list                列出 tombstone 文件"
    echo "  latest              读取最新的 tombstone"
    echo "  <n>                 读取 tombstone_0N"
    echo "  clear               删除所有 tombstone"
}

shizuku_cat() { $SHIZUKU exec "cat '$1'" 2>/dev/null || true; }
shizuku_ls()  { $SHIZUKU exec "ls -lt '$TS_DIR'" 2>/dev/null || true; }

cmd_list() {
    local list
    list=$(shizuku_ls)
    if [ -z "$list" ]; then
        echo "无 tombstones (目录为空或不可访问)"
        return
    fi
    echo "Tombstones:"
    echo "$list" | while IFS= read -r line; do
        echo "  $line"
    done
}

cmd_latest() {
    local latest
    latest=$(shizuku_ls | grep -v '^$' | head -1 | awk '{for(i=NF;i>0;i--) if($i!=""){print $i; exit}}' 2>/dev/null)
    [ -z "$latest" ] && { echo "无 tombstones"; return 1; }
    echo "=== 最新的 tombstone: $latest ==="
    shizuku_cat "$TS_DIR/$latest"
}

cmd_read() {
    local name="$1"
    shizuku_cat "$TS_DIR/$name" 2>/dev/null || echo "tombstone 不存在: $name"
}

cmd_clear() {
    echo -n "确定删除所有 tombstone? [y/N] "
    read -r ans
    case "$ans" in
        [Yy]|[Yy][Ee][Ss]) ;;
        *) echo "已取消"; exit 0 ;;
    esac
    $SHIZUKU exec "find $TS_DIR -type f -delete" 2>/dev/null || true
    echo "Tombstones 已删除"
}

case "${1:-help}" in
    list)      cmd_list ;;
    latest)    cmd_latest ;;
    clear)     cmd_clear ;;
    help|--help|-h) usage ;;
    *)         cmd_read "$1" ;;
esac
