#!/bin/sh
# aidev-build-request: 请求宿主在编译器宇宙 B 中构建项目（自我进化闭环触发信号）
# Usage: aidev-build-request [--project <name|/workspace/path>] [--no-install] [--no-launch] [--launch-package <pkg>]
#
# 改进：提交请求后阻塞等待宿主 BuildBridge 的 result-<id>.json，并打印构建结果：
#   - 成功：打印「构建成功」+ APK 路径（退出码 0）
#   - 失败：打印消息 + 完整构建日志（/sdcard/AIDev/logs/<project>/build.log，退出码 1）
#   - 超时：未在限定时间内收到结果（退出码 2）
set -u

AIDEV_HOME="${AIDEV_HOME:-$HOME}"
BRIDGE="$AIDEV_HOME/.aidev-build-bridge"
mkdir -p "$BRIDGE"

PROJECT="MyAndroidProject"
AUTO_INSTALL="true"
AUTO_LAUNCH="true"
LAUNCH_PKG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --no-install) AUTO_INSTALL="false"; shift ;;
    --no-launch) AUTO_LAUNCH="false"; shift ;;
    --launch-package) LAUNCH_PKG="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: aidev-build-request [--project <name>] [--no-install] [--no-launch] [--launch-package <pkg>]"
      exit 0 ;;
    *) PROJECT="$1"; shift ;;
  esac
done

ID="$(date +%s%N)"
json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
PROJ_ESC=$(printf '%s' "$PROJECT" | json_escape)
LP_ESC=$(printf '%s' "$LAUNCH_PKG" | json_escape)

PAYLOAD=$(cat <<EOF
{
  "id": "$ID",
  "project": "$PROJ_ESC",
  "flavor": "debug",
  "autoInstall": $AUTO_INSTALL,
  "autoLaunch": $AUTO_LAUNCH,
  "launchPackage": "$LP_ESC"
}
EOF
)

# 优先走 Socket（即时入队，服务器落盘 req-<id>.json 后由既有 BuildBridge 处理），失败回退文件 drop
if command -v aidev-bridge >/dev/null 2>&1; then
  ACK=$(aidev-bridge send build "$PAYLOAD" 2>/dev/null)
  if [ -n "$ACK" ]; then
    echo "已通过 Socket 提交构建请求 (ack: $ACK)"
  else
    printf '%s\n' "$PAYLOAD" > "$BRIDGE/req-$ID.json"
    echo "Socket 不可用，已回退文件通道提交"
  fi
else
  printf '%s\n' "$PAYLOAD" > "$BRIDGE/req-$ID.json"
  echo "未找到 aidev-bridge，已用文件通道提交"
fi

RESULT="$BRIDGE/result-$ID.json"
echo "已提交构建请求：project=$PROJECT install=$AUTO_INSTALL launch=$AUTO_LAUNCH (id=$ID)"
echo "宿主 BuildBridge 将监听并在宇宙 B（编译器）中编译，等待结果..."

# ── 阻塞等待结果 ──
TIMEOUT=900
WAITED=0
while [ ! -f "$RESULT" ] && [ "$WAITED" -lt "$TIMEOUT" ]; do
  sleep 2
  WAITED=$((WAITED + 2))
  printf "."
done
echo ""

if [ ! -f "$RESULT" ]; then
  echo "错误: 构建超时（${TIMEOUT}s 内未收到 result-$ID.json）"
  exit 2
fi

# ── 极简 JSON 取值（不依赖 jq）──
get_field() {
  # $1=file $2=key  → 取 "key": "value"
  grep -o "\"$2\"[ ]*:[ ]*\"[^\"]*\"" "$1" 2>/dev/null | head -1 | sed "s/\"$2\"[ ]*:[ ]*\"//; s/\"$//"
}
SUCCESS=$(grep -o '"success"[ ]*:[ ]*\(true\|false\)' "$RESULT" 2>/dev/null | sed 's/.*://; s/[ ]//g')
MESSAGE=$(get_field "$RESULT" message)
APK=$(get_field "$RESULT" apk_path)
PROJ=$(get_field "$RESULT" project)
[ -z "$PROJ" ] && PROJ="$PROJECT"

if [ "$SUCCESS" = "true" ]; then
  echo "═══ 构建成功 ═══"
  [ -n "$MESSAGE" ] && echo "消息: $MESSAGE"
  if [ -n "$APK" ] && [ "$APK" != "null" ]; then
    echo "APK: $APK"
    echo "→ 部署: aidev-deploy --apk $APK --pkg <包名>"
  fi
  exit 0
else
  echo "═══ 构建失败 ═══"
  [ -n "$MESSAGE" ] && echo "消息: $MESSAGE"
  LOGPATH=$(get_field "$RESULT" log_path)
  if [ -z "$LOGPATH" ] || [ "$LOGPATH" = "null" ]; then
    LOGPATH="/sdcard/AIDev/logs/$PROJ/build.log"
  fi
  echo ""
  echo "═══ 完整构建日志 ($LOGPATH) ═══"
  if [ -f "$LOGPATH" ]; then
    cat "$LOGPATH"
  else
    echo "(日志文件不可用: $LOGPATH)"
  fi
  exit 1
fi
