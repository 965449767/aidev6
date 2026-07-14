#!/bin/sh
# android-sh: 在 Android 宿主 shell 中执行命令（PRoot 内包装）
# 用法: android-sh '<command>'
set -e

if [ $# -eq 0 ]; then
    echo "用法: android-sh '<command>'"
    echo "示例: android-sh 'getprop ro.build.version.sdk'"
    exit 1
fi

exec aidev-shizuku exec "$@"
