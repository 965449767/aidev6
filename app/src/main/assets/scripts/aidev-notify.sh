#!/bin/sh
# aidev-notify: 经宿主桥接推送 Android 通知（Socket 主用，文件通道兜底）
#
# 用法:
#   aidev-notify "消息内容"
#   aidev-notify -t "标题" "消息内容"
#   aidev-notify -p high "消息内容"          # priority: low|default|high|max
set -e
. "$(dirname "$0")/lib/json-utils.sh"
. "$(dirname "$0")/lib/bridge-common.sh"

TITLE="AIDev Terminal"
PRIORITY=""
MSG=""

while [ $# -gt 0 ]; do
  case "$1" in
    -t) TITLE="$2"; shift 2 ;;
    -p) PRIORITY="$2"; shift 2 ;;
    -h|--help)
      echo "用法: aidev-notify [-t 标题] [-p low|default|high|max] \"消息内容\""
      exit 0 ;;
    *) MSG="$1"; shift ;;
  esac
done

if [ -z "$MSG" ]; then
  echo "用法: aidev-notify [-t 标题] [-p 优先级] \"消息内容\""
  exit 2
fi
TITLE_ESC=$(printf '%s' "$TITLE" | json_escape)
MSG_ESC=$(printf '%s' "$MSG" | json_escape)
PRIORITY_ESC=$(printf '%s' "$PRIORITY" | json_escape)

PAYLOAD=$(cat <<EOF
{
  "title": "$TITLE_ESC",
  "message": "$MSG_ESC",
  "priority": "$PRIORITY_ESC"
}
EOF
)

bridge_send notify "$PAYLOAD" "/host-home/.aidev-notify" "n"
exit 0
