#!/bin/sh
# uninstallapp: 卸载应用（PRoot 内包装，委托 Shizuku）
# 用法: uninstallapp <包名>
set -e

if [ $# -eq 0 ]; then
    echo "用法: uninstallapp <package_name>"
    echo "示例: uninstallapp com.example.myapp"
    exit 1
fi

PKG="$1"
echo "$PKG" | grep -qE '^[a-zA-Z0-9._]+$' || { echo "错误: 非法包名 '$PKG'"; exit 1; }
exec aidev-shizuku exec "pm uninstall -k --user 0 '$PKG'"
