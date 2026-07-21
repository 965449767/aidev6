# shellcheck shell=sh
# 桥接通信公共库（Socket 主用 + 文件通道兜底）
# 用法: . "$(dirname "$0")/lib/bridge-common.sh"
#
# 核心函数:
#   bridge_send <bridge_name> <payload> <fallback_dir> [file_prefix]
#     优先走 aidev-bridge socket，失败则写入 fallback_dir/prefix_<id>.json
#     返回 0 表示 socket 成功，1 表示走了文件兜底
#
#   bridge_poll_result <result_file> <timeout_seconds> <poll_interval>
#     阻塞等待结果文件出现，超时返回非 0

bridge_send() {
  _BRIDGE="$1"
  _PAYLOAD="$2"
  _FALLBACK_DIR="$3"
  _PREFIX="${4:-req}"

  if command -v aidev-bridge >/dev/null 2>&1; then
    _ACK=$(aidev-bridge send "$_BRIDGE" "$_PAYLOAD" 2>/dev/null) || true
    if [ -n "$_ACK" ]; then
      echo "Socket OK (ack: $_ACK)"
      return 0
    fi
  fi

  # 文件通道兜底
  mkdir -p "$_FALLBACK_DIR"
  _ID="$_PREFIX"_"$(date +%s%N)"_"$$"
  printf '%s\n' "$_PAYLOAD" > "$_FALLBACK_DIR/$_ID.json"
  echo "Socket 不可用，已回退文件通道 ($_FALLBACK_DIR/$_ID.json)"
  return 1
}

bridge_poll_result() {
  _RESULT_FILE="$1"
  _TIMEOUT="${2:-900}"
  _INTERVAL="${3:-2}"

  _WAITED=0
  while [ ! -f "$_RESULT_FILE" ] && [ "$_WAITED" -lt "$_TIMEOUT" ]; do
    sleep "$_INTERVAL"
    _WAITED=$((_WAITED + _INTERVAL))
    printf "."
  done
  echo ""

  if [ ! -f "$_RESULT_FILE" ]; then
    echo "错误: 等待超时（${_TIMEOUT}s 内未收到结果文件）"
    return 1
  fi
  return 0
}
