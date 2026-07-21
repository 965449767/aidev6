# shellcheck shell=sh
# 通用日志工具（供所有 aidev-* 脚本复用）
# 用法: . "$(dirname "$0")/lib/logging.sh"

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
die()  { log "错误: $*"; exit 1; }
warn() { echo "  \u26a0 $*"; }
info() { echo "  \u2192 $*"; }
ok()   { echo "  \u2713 $*"; }
fail() { echo "  \u2717 $*"; }
