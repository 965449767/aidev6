#!/bin/sh
# aidev-create-android-project: 兼容封装，统一委托 create-compose-project
#
# 旧接口（位置参数，保持兼容）:
#   aidev-create-android-project <应用名> <包名> [输出目录]
#
# 内部一律委托 create-compose-project（版本矩阵、Gradle 包装、模板复制的单一真源），
# 不再自行脚手架，避免与宿主 AGENTS.md 版本锁定漂移。
set -e

APP_NAME="${1:-}"
PACKAGE="${2:-}"

if [ -z "$APP_NAME" ] || [ -z "$PACKAGE" ]; then
    echo "用法: aidev-create-android-project <应用名> <包名> [输出目录]"
    echo ""
    echo "示例:"
    echo "  aidev-create-android-project MyApp com.example.myapp"
    echo "  aidev-create-android-project MyApp com.example.myapp /workspace"
    echo ""
    echo "注意: 项目必须建在 /workspace 下，宇宙B 才能编译。默认已指向 ~/projects。"
    exit 1
fi

if ! echo "$PACKAGE" | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'; then
    echo "错误: 包名格式无效: $PACKAGE"
    echo "正确格式: com.example.myapp"
    exit 1
fi

OUTPUT_DIR="${3:-${HOME}/projects}"

# create-compose-project 已部署在 /usr/local/bin（UbuntuBootstrapScripts 同步）
if command -v create-compose-project >/dev/null 2>&1; then
    exec create-compose-project -p "$PACKAGE" -o "$OUTPUT_DIR" "$APP_NAME"
else
    echo "错误: 找不到 create-compose-project（请重启 AIDev 终端以部署脚本）" >&2
    exit 1
fi
