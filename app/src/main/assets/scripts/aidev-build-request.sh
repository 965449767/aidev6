#!/bin/sh
# aidev-build-request: 请求宿主在编译器宇宙 B 中构建项目（自我进化闭环触发信号）
# Usage: aidev-build-request [--project <name|/workspace/path>] [--no-install] [--no-launch] [--launch-package <pkg>]
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

cat > "$BRIDGE/req-$ID.json" <<EOF
{
  "id": "$ID",
  "project": "$PROJ_ESC",
  "flavor": "debug",
  "autoInstall": $AUTO_INSTALL,
  "autoLaunch": $AUTO_LAUNCH,
  "launchPackage": "$LP_ESC"
}
EOF

echo "已提交构建请求：project=$PROJECT install=$AUTO_INSTALL launch=$AUTO_LAUNCH (id=$ID)"
echo "宿主 BuildBridge 将监听并在宇宙 B（编译器）中编译，完成后静默安装/拉起。"
