#!/bin/sh
set +e
export DEBIAN_FRONTEND=noninteractive

show_help() {
  cat <<'EOF'
AIDev 开发环境配置（分层 / 按需安装）

用法: setup-dev-env [选项]

默认（无选项）：只装核心层——基础工具 + python3 + nodejs/npm（约 350MB）。
可选层（按需追加）：
  --build     原生构建工具：build-essential cmake ninja-build（约 250MB）
  --android   Android SDK：cmdline-tools + platform-tools + platforms;android-36
              + build-tools;36.1.0 + build-tools;34.0.0 + headless JDK（约 700MB）
  --ndk       Android NDK 27（约 1.8GB，隐含 --android）
  --rust      rustup + Android targets + cargo-ndk（约 1.5GB）
  --all       安装以上全部
  -h|--help   显示本帮助

说明：Android/NDK/Rust 属于编译环境职责，终端环境通常无需安装。
仅在你要在终端环境内本地编译时才追加对应选项。
EOF
}

WANT_BUILD=0; WANT_ANDROID=0; WANT_NDK=0; WANT_RUST=0
for arg in "$@"; do
  case "$arg" in
    --build) WANT_BUILD=1 ;;
    --android) WANT_ANDROID=1 ;;
    --ndk) WANT_NDK=1; WANT_ANDROID=1 ;;
    --rust) WANT_RUST=1 ;;
    --all) WANT_BUILD=1; WANT_ANDROID=1; WANT_NDK=1; WANT_RUST=1 ;;
    -h|--help) show_help; exit 0 ;;
    *) echo "未知选项: $arg（-h 查看帮助）" ;;
  esac
done

echo "AIDev 开发环境配置"
echo "原则：先检测，缺什么装什么；重工具链按需（-h 查看选项）"
echo

# ---- 组装本次要检测/安装的 apt 包清单 ----
core_packages="ca-certificates curl wget git unzip zip tar xz-utils nano vim less procps coreutils findutils python3 python3-pip nodejs npm openssl openssh-client htop lsof jq netcat-openbsd iproute2 dnsutils tinyproxy"
apt_packages="$core_packages"
[ "$WANT_BUILD" = 1 ] && apt_packages="$apt_packages build-essential cmake ninja-build"
[ "$WANT_ANDROID" = 1 ] && apt_packages="$apt_packages openjdk-17-jdk-headless"

missing=""
for p in $apt_packages; do
  if ! dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q "install ok installed"; then
    missing="$missing $p"
  fi
done

echo "[1/4] apt 包检测"
if [ -z "$missing" ]; then
  echo "  已全部安装。"
else
  echo "  缺失：$missing"
  echo "[2/4] 更新 apt 索引并安装..."
  apt-get update
  apt-get install -y --no-install-recommends $missing || {
    echo "  安装失败，尝试修复后重试..."
    repair-dev-env
    apt-get install -y --no-install-recommends $missing || {
      echo "  安装失败。请运行 check-dev-env 查看状态。"
      exit 1
    }
  }
fi
echo

# ---- JDK 版本确认（仅 --android / --ndk）----
if [ "$WANT_ANDROID" = 1 ]; then
  echo "  ├─ 验证 JDK..."
  if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    if echo "$JAVA_VER" | grep -q 'openjdk.*17\.\|"17\.'; then
      echo "  │  ✓ $JAVA_VER"
    else
      echo "  │  ⚠ 当前 JDK: $JAVA_VER"
      echo "  │    AGP 9 要求 JDK 17，如版本不匹配请运行: apt install openjdk-17-jdk-headless"
    fi
  else
    echo "  │  ✗ java 未找到（openjdk-17-jdk-headless 安装可能失败）"
    echo "  │    请运行: repair-dev-env"
  fi
  echo
fi

# ---- Android SDK（仅 --android / --ndk）----
SDK_DIR="${AIDEV_HOME:-$HOME}/android-sdk"
CMDLINE_TOOLS="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ "$WANT_ANDROID" = 1 ]; then
  echo "[3/4] Android SDK..."
  if [ ! -f "$CMDLINE_TOOLS" ]; then
    echo "  下载 cmdline-tools..."
    mkdir -p "$SDK_DIR"
    tmp_zip=$(mktemp) || { echo "错误: 无法创建临时文件"; exit 1; }
    tmp_dir=$(mktemp -d) || { echo "错误: 无法创建临时目录"; exit 1; }
    trap 'rm -f "$tmp_zip"; rm -rf "$tmp_dir"' EXIT
    # 多源尝试：腾讯云镜像（国内快）→ Google 官方兜底。-f 让 HTTP 错误直接失败，避免把 404 页面存成 zip
    CMDLINE_URLS="https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    dl_ok=0
    for url in $CMDLINE_URLS; do
      echo "    尝试: $url"
      if curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 "$url" -o "$tmp_zip" && \
         unzip -tq "$tmp_zip" >/dev/null 2>&1; then
        dl_ok=1; break
      fi
      echo "    此源失败，换下一个..."
      rm -f "$tmp_zip"
    done
    if [ "$dl_ok" = 1 ]; then
      unzip -q "$tmp_zip" -d "$tmp_dir" && \
      mkdir -p "$SDK_DIR/cmdline-tools" && \
      rm -rf "$SDK_DIR/cmdline-tools/latest" && \
      mv "$tmp_dir/cmdline-tools" "$SDK_DIR/cmdline-tools/latest" && \
      echo "  cmdline-tools 已安装" || echo "  cmdline-tools 解压安装失败"
    else
      echo "  cmdline-tools 下载失败（所有源均不可用，请检查网络后重试）"
    fi
  else
    echo "  cmdline-tools 已存在"
  fi
  if [ -f "$CMDLINE_TOOLS" ]; then
    SDK_MGR="$CMDLINE_TOOLS --sdk_root=$SDK_DIR"
    echo "  接受许可证..."
    yes | $SDK_MGR --licenses 2>&1 | tail -1 || true
    for pkg in "platform-tools" "platforms;android-36" "build-tools;36.1.0" "build-tools;34.0.0"; do
      echo "  安装 $pkg ..."
      result=$($SDK_MGR "$pkg" 2>&1) && echo "    ✓" || {
        echo "    ✗ 失败输出:"
        echo "$result" | sed 's/^/      /'
        echo "    ℹ️ 若 SDK 组件未完整安装，Gradle 首次构建时会自动从 Maven 下载缺失组件（需联网）。"
      }
    done
    # ── SDK 安装验证 ──
    echo "  验证 SDK 安装..."
    _MISSING=""
    [ -d "$SDK_DIR/platforms/android-36" ] && echo "    ✓ platforms;android-36" || _MISSING="$_MISSING platforms;android-36"
    [ -d "$SDK_DIR/build-tools/36.1.0" ]   && echo "    ✓ build-tools;36.1.0"   || _MISSING="$_MISSING build-tools;36.1.0"
    [ -d "$SDK_DIR/build-tools/34.0.0" ]   && echo "    ✓ build-tools;34.0.0"   || _MISSING="$_MISSING build-tools;34.0.0"
    [ -f "$SDK_DIR/platform-tools/adb" ]   && echo "    ✓ platform-tools"        || _MISSING="$_MISSING platform-tools"
    if [ -n "$_MISSING" ]; then
      echo "    ⚠ 以下组件未完整安装:$_MISSING"
      echo "      首次构建时 Gradle 会自动从 Maven 补充下载。"
      echo "      若需离线构建，请运行: repair-dev-env"
    fi
  fi
  echo

  # ── ARM64: aapt2 兼容性检查与说明（仅检测+提示，不自动部署） ──
  if [ "$(uname -m)" = "aarch64" ]; then
    echo "  检查 aapt2 ARM64 兼容性..."
    _aapt2_found=""
    for _bt in "$SDK_DIR/build-tools"/*/; do
      [ -f "${_bt}aapt2" ] && { _aapt2_found="${_bt}aapt2"; break; }
    done
    if [ -n "$_aapt2_found" ]; then
      if "${_aapt2_found}" version >/dev/null 2>&1; then
        echo "    ✓ aapt2 可原生运行（ARM64 原生兼容）"
      else
        echo "    ℹ️ 当前 aapt2 为 x86_64 架构，无法在 ARM64 上直接运行。"
        echo "       请重启 AIDev 终端触发 QEMU 包装器部署，或在终端内运行: repair-dev-env"
      fi
    else
      echo "    ℹ️ 未找到 aapt2（build-tools 未安装），Gradle 构建时会从 Maven 获取。"
    fi
  fi

  # ── 全局 Gradle 配置：写入 GRADLE_USER_HOME/gradle.properties ──
  echo "  配置全局 Gradle 属性..."
  GH="${GRADLE_USER_HOME:-$HOME/.gradle}"
  mkdir -p "$GH"
  GRADLE_PROPS="$GH/gradle.properties"
  [ -f "$GRADLE_PROPS" ] || touch "$GRADLE_PROPS"
  _added=0
  for _line in "android.aapt2FromMavenOverride=/host-home/x86_64/aapt2" \
               "android.aapt2DaemonMode=false"; do
    if ! grep -qxF "$_line" "$GRADLE_PROPS" 2>/dev/null; then
      echo "$_line" >> "$GRADLE_PROPS"
      _added=1
    fi
  done
  if [ "$_added" = 1 ]; then
    echo "    ✓ 全局 Gradle 属性已写入 $GRADLE_PROPS"
  else
    echo "    ✓ 全局 Gradle 属性已存在"
  fi
fi

# ---- Android NDK（仅 --ndk）----
if [ "$WANT_NDK" = 1 ] && [ -f "$CMDLINE_TOOLS" ]; then
  NDK_DIR="$SDK_DIR/ndk/27.0.12077973"
  echo "[3b/4] Android NDK 27（约 1.8GB，耗时较长）..."
  if [ ! -d "$NDK_DIR" ]; then
    yes | "$CMDLINE_TOOLS" "ndk;27.0.12077973" 2>/dev/null && \
    echo "  NDK 已安装" || echo "  NDK 安装失败（可手动重试：sdkmanager \"ndk;27.0.12077973\"）"
  else
    echo "  NDK 已存在"
  fi
  echo
fi

# ---- Rust 工具链（仅 --rust）----
if [ "$WANT_RUST" = 1 ]; then
  echo "[4/4] Rust 工具链..."
  if ! command -v rustup >/dev/null 2>&1; then
    echo "  下载 rustup-init..."
    RUSTUP_TMP=$(mktemp)
    if curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs -o "$RUSTUP_TMP" 2>/dev/null; then
      chmod +x "$RUSTUP_TMP"
      "$RUSTUP_TMP" -y 2>/dev/null && echo "  rustup 已安装" || echo "  rustup 安装失败（可手动安装）"
    else
      echo "  rustup 下载失败（可手动安装）"
    fi
    rm -f "$RUSTUP_TMP"
  fi
  if command -v rustup >/dev/null 2>&1; then
    . "$HOME/.cargo/env" 2>/dev/null || true
    for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
      if ! rustup target list --installed 2>/dev/null | grep -q "$target"; then
        echo "  添加 Android target: $target"
        rustup target add "$target" 2>/dev/null
      fi
    done
    if ! command -v cargo-ndk >/dev/null 2>&1; then
      echo "  安装 cargo-ndk..."
      cargo install cargo-ndk 2>/dev/null && echo "  cargo-ndk 已安装" || true
    fi
  fi
  echo
fi

echo "配置完成。建议运行：check-dev-env"
