#!/bin/sh
# aidev-repo — AIDevRepo 离线仓库消费端（fail-safe）
#
# 读取共享仓库 /sdcard/.AIDevRepo/{catalog.json,manifest.json}，
# 解析已缓存资源的本地路径。任何异常都安全降级：resolve 返回非 0
# 时调用方回退到原有网络下载路径，绝不破坏既有行为。
#
# 下载来源策略（用户开关决定，AI 无法覆盖）：
#   - 环境变量 AIDEV_REPO_MODE 优先（构建进程注入，PRoot 内也可达）
#   - 否则读 /sdcard/.AIDevRepo/policy.txt（App 设置开关写入）
#   - 否则默认 AUTO（仓库优先，没有再走网络）
#   取值: AUTO(默认) | STRICT(只走仓库，缺失则禁止网络) | NETWORK(只走网络)
#
# 路径可通过环境变量 AIDEV_REPO_ROOT 覆盖（例如在 PRoot Ubuntu 内
# 指向 /sdcard 的实际挂载点）。
#
# 用法:
#   aidev-repo resolve <seriesId> [version]   # 命中则打印本地路径并 exit 0，否则 exit 1
#   aidev-repo decide  <seriesId> [version]   # 按策略输出 repo:<path> | network | deny
#   aidev-repo root                           # 打印仓库根目录
#   aidev-repo help

set -e

REPO_ROOT="${AIDEV_REPO_ROOT:-/sdcard/.AIDevRepo}"

resolve() {
    seriesId="${1:-}"
    ver="${2:-}"
    [ -n "$seriesId" ] || return 1
    manifest="$REPO_ROOT/manifest.json"
    [ -f "$manifest" ] || return 1
    command -v jq >/dev/null 2>&1 || return 1

    local p=""
    if [ -n "$ver" ]; then
        p=$(jq -r --arg id "$seriesId" --arg v "$ver" \
            '.repos[]? | select(.id==$id) | .versions[]? | select(.version==$v and (.cached==true)) | .path // empty' \
            "$manifest" | head -1)
    else
        p=$(jq -r --arg id "$seriesId" \
            '.repos[]? | select(.id==$id) as $e
             | ($e | first(
                 (.versions[]? | select(.cached==true and (.version==$e.recommended)) | .path),
                 (.versions[]? | select(.cached==true) | .path)
               ) // empty)' \
            "$manifest" | head -1)
    fi
    [ -n "$p" ] || return 1
    full="$REPO_ROOT/$p"
    [ -e "$full" ] || return 1
    echo "$full"
    return 0
}

policy() {
    if [ -n "${AIDEV_REPO_MODE:-}" ]; then
        local m
        m=$(echo "${AIDEV_REPO_MODE}" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')
        case "$m" in
            AUTO|STRICT|NETWORK) echo "$m"; return ;;
        esac
    fi
    local pf="$REPO_ROOT/policy.txt"
    if [ -f "$pf" ]; then
        local m
        m=$(cat "$pf" 2>/dev/null | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')
        case "$m" in
            AUTO|STRICT|NETWORK) echo "$m"; return ;;
        esac
    fi
    echo "AUTO"
}

decide() {
    seriesId="${1:-}"
    ver="${2:-}"
    local mode
    mode=$(policy)
    case "$mode" in
        NETWORK)
            echo "network"; return 0 ;;
        STRICT)
            local p=""
            p=$(resolve "$seriesId" "$ver") || true
            if [ -n "$p" ]; then echo "repo:$p"; return 0; fi
            echo "deny"; return 0 ;;
        *)
            local p=""
            p=$(resolve "$seriesId" "$ver") || true
            if [ -n "$p" ]; then echo "repo:$p"; return 0; fi
            echo "network"; return 0 ;;
    esac
}

case "${1:-help}" in
    resolve)
        shift
        resolve "$@"
        exit $?
        ;;
    decide)
        shift
        decide "$@"
        exit $?
        ;;
    root)
        echo "$REPO_ROOT"
        exit 0
        ;;
    help|-h|--help)
        echo "用法: aidev-repo resolve <seriesId> [version]"
        echo "      aidev-repo decide <seriesId> [version]"
        echo "      aidev-repo root"
        echo "      aidev-repo help"
        exit 0
        ;;
    *)
        echo "未知命令: $1" >&2
        exit 1
        ;;
esac
