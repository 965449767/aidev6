#!/bin/sh
# aidev-build-request: 请求宿主在终端环境中构建项目
# Usage: aidev-build-request [--project <name|/workspace/path>] [--no-install] [--no-launch] [--launch-package <pkg>]
#
# 改进：提交请求后阻塞等待宿主 BuildBridge 的 result-<id>.json，并打印构建结果：
#   - 成功：打印「构建成功」+ APK 路径（退出码 0）
#   - 失败：打印消息 + 完整构建日志（/sdcard/AIDev/logs/<project>/build.log，退出码 1）
#   - 超时：未在限定时间内收到结果（退出码 2）
#   - 跳过：该项目已有构建在进行中（退出码 3，非失败，应跟踪而非重试）
set -e
. "$(dirname "$0")/lib/json-utils.sh"

# 桥目录必须与宿主 BuildBridge 写入位置一致：宿主用 aidevHome=ctx.filesDir/home，
# 在终端环境中挂载为 /host-home。结果/请求都落在 /host-home/.aidev-build-bridge。
# 若仍按默认 $HOME(=/root) 轮询，则永远找不到 result-<id>.json，导致 900s 超时、
# agent 在结果回来前就结束会话、收不到构建反馈。
if [ -d /host-home ]; then
  AIDEV_HOME="/host-home"
else
  AIDEV_HOME="${AIDEV_HOME:-$HOME}"
fi
BRIDGE="$AIDEV_HOME/.aidev-build-bridge"
mkdir -p "$BRIDGE"

PROJECT=""
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
      echo "Usage: aidev-build-request --project <name|/workspace/path> [--no-install] [--no-launch] [--launch-package <pkg>]"
      exit 0 ;;
    *) PROJECT="$1"; shift ;;
  esac
done

if [ -z "$PROJECT" ]; then
  echo "错误: 必须通过 --project 指定项目（名称或 /workspace/<name> 路径）。" >&2
  echo "用法: aidev-build-request --project <name> [--no-install] [--no-launch] [--launch-package <pkg>]" >&2
  exit 2
fi

ID="$(date +%s%N)"
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
echo "宿主 BuildBridge 将监听并在终端环境中编译，等待结果..."

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
  # 兼容 JSON 转义斜杠 \/ 与 \"，取出后做反向转义，避免路径含 \/workspace\/... 这类非法路径。
  grep -o "\"$2\"[ ]*:[ ]*\"[^\"]*\"" "$1" 2>/dev/null | head -1 | sed "s/\"$2\"[ ]*:[ ]*\"//; s/\"$//" | sed 's#\\/"#/#g; s#\\"#"#g; s#\\\\#\\#g'
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
    echo "→ 安装: aidev-autoinstall --launch <包名> $APK"
  fi
  exit 0
else
  # 特例：该项目已有构建在进行中（宿主 ProjectTaskLock 占用）。这不是编译失败，
  # 常见于上一次请求客户端超时中断、但仍在后台编译时重复提交。此时应引导
  # 用户跟踪进行中的构建，而不是当作失败盲目重试（见闭环报告 2026-07-18 问题1）。
  case "$MESSAGE" in
    *已有任务进行中*|*任务进行中*)
      PROJ_NORM=$(printf '%s' "$PROJ" | sed 's#^/workspace/##; s#^/##; s#.*/##')
      [ -z "$PROJ_NORM" ] && PROJ_NORM="$PROJ"
      echo "═══ 构建跳过（已有构建进行中）═══"
      echo "消息: $MESSAGE"
      echo ""
      echo "提示: 该项目已有一次构建在后台运行（可能来自上次超时中断的请求）。"
      echo "      请勿重复提交——用 'aidev-build-log $PROJ_NORM' 跟踪其进度与结果，"
      echo "      待其结束后再按需重新构建。"
      exit 3
      ;;
  esac
  echo "═══ 构建失败 ═══"
  [ -n "$MESSAGE" ] && echo "消息: $MESSAGE"
  LOGPATH=$(get_field "$RESULT" log_path)
  # 规范化项目名：result 的 project 字段可能是 /workspace/xxx 这类路径，
  # 用作日志子目录会产生非法/转义路径（见调试报告 Issue #1/#2）。统一取 basename。
  PROJ_NORM=$(printf '%s' "$PROJ" | sed 's#^/workspace/##; s#^/##; s#.*/##')
  [ -z "$PROJ_NORM" ] && PROJ_NORM="$PROJ"
  # 多路径兜底：宿主写入位置历史上有过差异（/sdcard/AIDev/logs/<name> 与
  # /workspace/<name>），逐一尝试，任一可读即打印，避免"日志不可用"误报。
  CANDIDATES=""
  [ -n "$LOGPATH" ] && [ "$LOGPATH" != "null" ] && CANDIDATES="$CANDIDATES
$LOGPATH"
  CANDIDATES="$CANDIDATES
/sdcard/AIDev/logs/$PROJ_NORM/build.log
/workspace/$PROJ_NORM/build.log"
  RESOLVED=""
  IFS="
"
  for cand in $CANDIDATES; do
    cand=$(printf '%s' "$cand" | tr -d ' \t')
    [ -z "$cand" ] && continue
    if [ -f "$cand" ]; then RESOLVED="$cand"; break; fi
  done
  unset IFS
  echo ""
  if [ -n "$RESOLVED" ]; then
    echo "═══ 完整构建日志 ($RESOLVED) ═══"
    cat "$RESOLVED"
  else
    echo "═══ 构建日志未找到 ═══"
    echo "(已尝试: $(printf '%s' "$CANDIDATES" | tr '\n' ' ' | sed 's/^ //; s/ *$//'))"
    echo "提示: 用 'aidev-build-log $PROJ_NORM' 随时重新拉取；或检查宿主是否已重装并重启终端。"
  fi
  exit 1
fi
