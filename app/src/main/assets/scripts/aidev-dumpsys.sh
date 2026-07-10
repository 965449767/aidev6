#!/bin/bash
# aidev-dumpsys: dumpsys 快捷命令
# 用法: aidev-dumpsys [meminfo|activity|window|battery|diskstats|<subsystem>]
set -eo pipefail

SHIZUKU="aidev-shizuku"
PKG="${AIDEV_PKG:-com.aidev.six}"

usage() {
    echo "用法: aidev-dumpsys [子命令]"
    echo "  meminfo             内存信息"
    echo "  meminfo-pkg         应用内存详情"
    echo "  activity            当前 Activity"
    echo "  window              窗口/显示信息"
    echo "  battery             电池状态"
    echo "  diskstats           磁盘统计"
    echo "  network             网络状态"
    echo "  package <pkg>       应用包信息"
    echo "  <subsystem>         任意 dumpsys 子系统"
}

do_dumpsys() {
    $SHIZUKU exec "dumpsys $* 2>/dev/null | head -40" || echo "dumpsys $* 失败"
}

cmd_meminfo() {
    echo "=== dumpsys meminfo ==="
    do_dumpsys meminfo
}

cmd_meminfo_pkg() {
    echo "=== dumpsys meminfo -a $PKG ==="
    do_dumpsys meminfo -a "$PKG"
}

cmd_activity() {
    echo "=== dumpsys activity top ==="
    do_dumpsys activity top
}

cmd_window() {
    echo "=== dumpsys window displays ==="
    do_dumpsys window displays
}

cmd_battery() {
    echo "=== dumpsys battery ==="
    do_dumpsys battery
}

cmd_diskstats() {
    echo "=== dumpsys diskstats ==="
    do_dumpsys diskstats
}

cmd_network() {
    echo "=== 网络状态 ==="
    echo "--- dumpsys connectivity ---"
    do_dumpsys connectivity
    echo ""
    echo "--- dumpsys wifi ---"
    do_dumpsys wifi | head -30
}

cmd_package() {
    local pkg="${1:-$PKG}"
    echo "=== dumpsys package $pkg ==="
    do_dumpsys package "$pkg"
}

cmd_raw() {
    echo "=== dumpsys $* ==="
    do_dumpsys "$@"
}

case "${1:-help}" in
    meminfo)     cmd_meminfo ;;
    meminfo-pkg) cmd_meminfo_pkg ;;
    activity)    cmd_activity ;;
    window)      cmd_window ;;
    battery)     cmd_battery ;;
    diskstats)   cmd_diskstats ;;
    network)     cmd_network ;;
    package)     shift; cmd_package "$@" ;;
    help|--help|-h) usage ;;
    *)           cmd_raw "$@" ;;
esac
