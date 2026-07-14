#!/bin/sh
set +e
export DEBIAN_FRONTEND=noninteractive
echo "AIDev 开发环境配置"
echo "原则：先检测，缺什么装什么"
echo

base_packages="ca-certificates curl wget git unzip zip tar xz-utils nano vim less procps coreutils findutils build-essential python3 python3-pip openjdk-17-jdk"
js_packages="nodejs npm"
missing_base=""
missing_js=""

for p in $base_packages; do
  if ! dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed"; then
    missing_base="$missing_base $p"
  fi
done
for p in $js_packages; do
  if ! dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed"; then
    missing_js="$missing_js $p"
  fi
done

echo "[1/4] 当前检测结果"
if [ -z "$missing_base" ]; then
  echo "  基础层：已全部安装。"
else
  echo "  基础层缺失：$missing_base"
fi
if [ -z "$missing_js" ]; then
  echo "  JS 层：已全部安装。"
else
  echo "  JS 层缺失：$missing_js"
fi

if [ -n "$missing_base" ] || [ -n "$missing_js" ]; then
  echo "[2/4] 更新 apt 索引..."
  apt-get update
else
  echo "[2/4] 无需更新"
fi

if [ -n "$missing_base" ]; then
  echo "[3/4] 安装基础层缺失包..."
  apt-get install -y --no-install-recommends $missing_base || {
    echo "基础层安装失败，尝试修复后重试..."
    repair-dev-env
    apt-get install -y --no-install-recommends $missing_base || {
      echo "基础层安装失败。请运行 check-dev-env 查看状态。"
      exit 1
    }
  }
else
  echo "[3/4] 基础层无需安装"
fi

if [ -n "$missing_js" ]; then
  echo "[4/4] 安装 JS 层 nodejs/npm..."
  apt-get install -y --no-install-recommends $missing_js || {
    echo "JS 层安装遇到问题，尝试修复后重试..."
    repair-dev-env
    apt-get install -y --no-install-recommends $missing_js || {
      echo "JS 层 nodejs/npm 仍未完全配置。"
    }
  }
else
  echo "[4/4] JS 层无需安装"
fi

echo
echo "配置完成。建议运行：check-dev-env"
