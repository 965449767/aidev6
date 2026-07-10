#!/bin/sh
# aidev-crash-report: 读取宿主侧最新崩溃报告（MCP JSON），供 OpenCode 智能体自我修正
# Usage: aidev-crash-report [--package <pkg>] [--wait]
set -u

AIDEV_HOME="${AIDEV_HOME:-$HOME}"
MCP="$AIDEV_HOME/.aidev-mcp"
LATEST="$MCP/latest.json"

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PKG="$2"; shift 2 ;;
    --wait) WAIT="1"; shift ;;
    -h|--help) echo "Usage: aidev-crash-report [--package <pkg>] [--wait]"; exit 0 ;;
    *) shift ;;
  esac
done

mkdir -p "$MCP"

if [ -n "${WAIT:-}" ]; then
  # 等待一次崩溃报告生成（最多 60s）
  for i in $(seq 1 60); do
    [ -f "$LATEST" ] && break
    sleep 1
  done
fi

if [ ! -f "$LATEST" ]; then
  echo "尚未生成任何崩溃报告（home/.aidev-mcp/latest.json 不存在）。"
  echo "可先运行目标 App，再触发 aidev-crash-report；或由宿主在拉起后自动抓取。"
  exit 0
fi

echo "== AIDev 崩溃报告 (MCP) =="
cat "$LATEST"
echo
echo "== 提示 =="
echo "将上述报告交给 OpenCode 智能体分析，定位根因并修改 /workspace 下的源码，"
echo "然后运行 aidev-build-request 重新触发宇宙 B 编译，形成自我进化闭环。"
