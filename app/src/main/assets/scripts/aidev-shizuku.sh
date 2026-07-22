#!/bin/sh
# aidev-shizuku: 通过 Shizuku 执行 Android 系统命令
# 子命令:
#   exec '<command>'         执行任意 shell 命令
#   install <apk_path>       静默安装 APK
#   launch <pkg>             拉起指定包名的应用
#   input keyevent <key>     模拟按键
#   input tap <x> <y>        模拟点击
#   wifi on|off              WiFi 开关
#   data on|off              移动数据开关
#   battery level <N>        设置模拟电池电量
#   battery reset            重置电池状态
#   status                   检查 Shizuku 状态

set -e
BRIDGE_DIR="/host-home/.aidev-shizuku-bridge"
REQUEST_DIR="$BRIDGE_DIR/request"
RESULT_DIR="$BRIDGE_DIR/result"
mkdir -p "$REQUEST_DIR" "$RESULT_DIR"

# 经 Shizuku 桥执行一条「单一、无 shell 元字符」命令（socket 优先，文件通道兜底）。
# 返回 0 表示桥执行成功（退出码 0），1 表示被安全策略拒绝/执行失败/超时。
# 注意：ShizukuBridgeService.isCommandAllowed 仅放行单一「前缀白名单」命令且禁止
# 任何 shell 元字符（; & | $ ` ( ) < > 等），故 cp 与 pm install 必须拆成两次独立请求。
shizuku_exec() {
  _CMD=$(printf '%s' "$1" | tr '\n' ' ')
  _PAYLOAD="TYPE=exec
COMMAND=$_CMD"
  _RESP=""
  if command -v aidev-bridge >/dev/null 2>&1; then
    _RESP=$(aidev-bridge send shizuku "$_PAYLOAD" 2>/dev/null) || true
    if [ -n "$_RESP" ]; then
      echo "命令: $_CMD"
      echo "$_RESP"
      case "$_RESP" in
        ERROR:*) return 1 ;;
      esac
      return 0
    fi
  fi
  # ── 文件通道兜底 ──
  _REQ_ID="exec_$(date +%s%N)_$$"
  _REQ_FILE="$REQUEST_DIR/$_REQ_ID"
  _RES_FILE="$RESULT_DIR/$_REQ_ID"
  cat > "$_REQ_FILE" <<EOF
TYPE=exec
COMMAND=$_CMD
EOF
  echo "命令: $_CMD"
  echo "等待执行结果..."
  _WAITED=0
  while [ ! -s "$_RES_FILE" ] && [ "$_WAITED" -lt 30 ]; do
    sleep 1
    _WAITED=$((_WAITED + 1))
    if [ -f "$_RES_FILE" ] && grep -q "^ERROR:" "$_RES_FILE" 2>/dev/null; then
      cat "$_RES_FILE"
      rm -f "$_REQ_FILE" "$_RES_FILE"
      return 1
    fi
  done
  if [ ! -s "$_RES_FILE" ]; then
    echo "ERROR: 请求超时。Shizuku 可能未运行或未授权。"
    rm -f "$_REQ_FILE" "$_RES_FILE"
    return 1
  fi
  cat "$_RES_FILE"
  rm -f "$_REQ_FILE" "$_RES_FILE"
  return 0
}

SUBCOMMAND="${1:-}"
# dash 下无参时 `shift` 是特殊内建命令，失败会在 set -e 中终止脚本，且
# `2>/dev/null || true` 无法兜住（rc=2，永远到不了帮助分支）。故先判有参再 shift。
if [ "$#" -gt 0 ]; then shift; fi

case "$SUBCOMMAND" in
  exec)
    CMD="$*"
    ;;
  install)
    APK_PATH="$1"
    if [ -z "$APK_PATH" ]; then
      echo "用法: aidev-shizuku install <apk_path>"
      exit 1
    fi
    if [ ! -f "$APK_PATH" ]; then
      echo "错误: 文件不存在: $APK_PATH"
      exit 1
    fi
    APK_PATH=$(readlink -f "$APK_PATH")
    APK_PATH_ESC=$(printf '%s' "$APK_PATH" | sed "s/'/'\\\\''/g")

    # 通过 Shizuku Bridge 的 TYPE=install 处理安装。
    # Bridge 端复制 APK 到 /data/local/tmp/ 后执行 pm install -r -d --user 0，
    # 绕过 FUSE SELinux 限制，避免 session-based 安装的 HyperOS 弹窗问题。
    echo "→ 静默安装 (Shizuku Bridge)..."
    _RESP=$(aidev-bridge send shizuku "TYPE=install
APK_PATH=$APK_PATH_ESC" 2>/dev/null) || true
    if [ -z "$_RESP" ]; then
      echo "ERROR: 桥接无响应。请确认 Shizuku 已授权并在 AIDev 中启动桥接服务。"
      exit 1
    fi
    echo "$_RESP"
    case "$_RESP" in
      ERROR:*) exit 1 ;;
    esac
    exit 0
    ;;
  input)
    CMD="input $*"
    ;;
  wifi)
    case "$1" in
      on|off) CMD="svc wifi $1" ;;
      *) echo "用法: aidev-shizuku wifi on|off"; exit 1 ;;
    esac
    ;;
  data)
    case "$1" in
      on|off) CMD="svc data $1" ;;
      *) echo "用法: aidev-shizuku data on|off"; exit 1 ;;
    esac
    ;;
  battery)
    case "$1" in
      level) CMD="dumpsys battery set level $2" ;;
      reset) CMD="dumpsys battery reset" ;;
      *) echo "用法: aidev-shizuku battery level N|reset"; exit 1 ;;
    esac
    ;;
  launch)
    PKG="$1"
    if [ -z "$PKG" ]; then
      echo "用法: aidev-shizuku launch <package_name>"
      exit 1
    fi
    CMD="monkey -p '$PKG' -c android.intent.category.LAUNCHER 1"
    ;;
  status)
    echo "正在检测 Shizuku 桥接..."
    out=""
    # getprop 在白名单内且返回数字；echo 不在白名单会被安全护栏拒绝
    out=$(aidev-shizuku exec "getprop ro.build.version.sdk" 2>&1) || true
    if echo "$out" | grep -qE '[0-9]+'; then
      echo "Shizuku 状态: 正常 (桥接通道可用，可静默安装)"
      exit 0
    else
      echo "Shizuku 状态: 未响应/未授权。"
      echo "  → 请确认: 1) Shizuku App 已启动; 2) AIDev (com.aidev.six.dev) 已在 Shizuku 已授权列表中。"
      echo "  → 桥接原始响应: $out"
      exit 1
    fi
    ;;
  *)
    echo "用法: aidev-shizuku <子命令> [参数]"
    echo ""
    echo "子命令:"
    echo "  exec '<command>'         执行任意 shell 命令"
    echo "  install <apk_path>       静默安装 APK"
    echo "  launch <pkg>             拉起指定包名的应用"
    echo "  input keyevent <key>     模拟按键"
    echo "  input tap <x> <y>        模拟点击"
    echo "  wifi on|off              WiFi 开关"
    echo "  data on|off              移动数据开关"
    echo "  battery level <N>        设置模拟电池电量"
    echo "  battery reset            重置电池状态"
    echo "  status                   检查 Shizuku 状态"
    exit 0
    ;;
esac

# 统一走 shizuku_exec（Socket 优先 + 文件通道兜底），消除重复逻辑
shizuku_exec "$CMD" || exit 1
