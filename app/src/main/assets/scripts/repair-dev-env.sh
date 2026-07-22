#!/bin/sh
set +e
export DEBIAN_FRONTEND=noninteractive

echo "═══════════════════════════════════════════"
echo "      AIDev 环境修复"
echo "═══════════════════════════════════════════"
echo

# ── [1] 修复 apt/dpkg（已有逻辑）──
echo "── [1] 修复 apt/dpkg ──"
rm -f /var/cache/apt/archives/lock /var/lib/apt/lists/lock \
      /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock 2>/dev/null
dpkg --configure -a 2>/dev/null
apt-get -f install -y 2>/dev/null
dpkg --configure -a 2>/dev/null
echo "  ✓ apt/dpkg 修复完成"
echo

# ── [2] 修复 Android SDK ──
echo "── [2] 修复 Android SDK ──"
SDK_DIR="${AIDEV_HOME:-$HOME}/android-sdk"
CMDLINE_TOOLS="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ -f "$CMDLINE_TOOLS" ]; then
  SDK_MGR="$CMDLINE_TOOLS --sdk_root=$SDK_DIR"
  echo "  检查 SDK 组件完整性..."
  for _entry in "platforms;android-36:platforms/android-36" \
                "build-tools;36.1.0:build-tools/36.1.0" \
                "build-tools;34.0.0:build-tools/34.0.0" \
                "platform-tools:platform-tools"; do
    _pkg="${_entry%%:*}"
    _dir="${_entry##*:}"
    if [ -d "$SDK_DIR/$_dir" ] || [ -f "$SDK_DIR/$_dir/adb" ]; then
      echo "  ✓ $_pkg"
    else
      echo "  → 安装 $_pkg ..."
      yes | $SDK_MGR "$_pkg" 2>&1 | tail -3
      if [ -d "$SDK_DIR/$_dir" ] || [ -f "$SDK_DIR/$_dir/adb" ]; then
        echo "  ✓ $_pkg 安装成功"
      else
        echo "  ⚠ $_pkg 仍未安装（首次构建时 Gradle 会从 Maven 自动下载）"
      fi
    fi
  done
else
  echo "  ⚠ sdkmanager 未就绪，无法通过 SDK Manager 修复"
  if [ -d "$SDK_DIR/platforms/android-36" ]; then
    echo "  ✓ SDK 数据已存在，可继续使用"
  else
    echo "  → SDK 不完整，建议运行: setup-dev-env --android"
  fi
fi
echo

# ── [3] 修复 aapt2 ARM64 兼容性 ──
echo "── [3] 修复 aapt2 兼容性 ──"
if [ "$(uname -m)" = "aarch64" ]; then
  _AAPT2_OK=false
  for _bt in "$SDK_DIR/build-tools"/*/; do
    [ -f "${_bt}aapt2" ] || continue
    if "${_bt}aapt2" version >/dev/null 2>&1; then
      _AAPT2_OK=true
      break
    fi
  done
  if [ "$_AAPT2_OK" = true ]; then
    echo "  ✓ aapt2 可原生运行"
  else
    if [ -x /host-home/x86_64/aapt2 ]; then
      echo "  测试 QEMU 包装器..."
      if /host-home/x86_64/aapt2 version >/dev/null 2>&1; then
        echo "  ✓ QEMU aapt2 包装器正常工作"
      else
        echo "  ⚠ QEMU aapt2 包装器异常，重启 AIDev 终端可重新部署"
      fi
    else
      echo "  ℹ 本地 aapt2 为 x86_64，在 ARM64 上不可直接运行。"
      echo "    不会阻断编译：AGP 9 会自动从 Maven 下载 ARM64 版 aapt2。"
      echo "    如需 create-compose-project 检测通过，请重启 AIDev 终端触发 QEMU 包装器部署。"
    fi
  fi
else
  echo "  ✓ 非 ARM64 架构，无需 aapt2 兼容处理"
fi
echo

# ── [4] 修复 Gradle init 脚本 ──
echo "── [4] 修复 Gradle init 脚本 ──"
GH="${GRADLE_USER_HOME:-$HOME/.gradle}"
INIT_D="$GH/init.d"
if [ -d "/host-home/gradle-init.d" ]; then
  mkdir -p "$INIT_D"
  cp -n /host-home/gradle-init.d/*.gradle "$INIT_D/" 2>/dev/null
  echo "  ✓ 已从 /host-home/gradle-init.d/ 恢复 init 脚本"
  for _f in "wrap-native.gradle" "performance.gradle" "copy-apk.gradle"; do
    [ -f "$INIT_D/$_f" ] && echo "    ✓ $_f" || echo "    ⚠ $_f 仍缺失（重启 AIDev 终端）"
  done
elif [ -d "$INIT_D" ]; then
  echo "  ✓ init.d 已存在: $INIT_D"
  for _f in "wrap-native.gradle" "performance.gradle" "copy-apk.gradle"; do
    [ -f "$INIT_D/$_f" ] && echo "    ✓ $_f" || echo "    ⚠ $_f 缺失（重启 AIDev 终端自动生成）"
  done
else
  echo "  ⚠ init.d 来源缺失，重启 AIDev 终端后自动生成"
fi
echo

# ── [5] 修复项目模板 ──
echo "── [5] 修复项目模板 ──"
TPL_DIR="$HOME/.gradle/template-wrapper"
if [ -f "$TPL_DIR/gradlew" ] && [ -f "$TPL_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "  ✓ 项目模板完整"
else
  echo "  → 模板不完整，尝试恢复..."
  mkdir -p "$TPL_DIR/gradle/wrapper"
  # 搜索可能的模板源
  for _src in "/host-home/dev-env/bin" "/usr/local/bin" "/data/data/com.aidev.six.dev/files/home/dev-env/bin"; do
    _parent="$(dirname "$_src")"
    if [ -f "$_parent/template-wrapper/gradlew" ]; then
      cp "$_parent/template-wrapper/gradlew" "$TPL_DIR/gradlew" 2>/dev/null && chmod +x "$TPL_DIR/gradlew"
      cp "$_parent/template-wrapper/gradle/wrapper/gradle-wrapper.jar" "$TPL_DIR/gradle/wrapper/" 2>/dev/null
      break
    fi
  done
  if [ -f "$TPL_DIR/gradlew" ]; then
    echo "  ✓ 已恢复 gradlew + wrapper jar"
  else
    echo "  ⚠ 无法自动恢复模板，请重启 AIDev 终端"
  fi
fi
echo

# ── [6] 修复 JAVA_HOME ──
echo "── [6] 修复 JAVA_HOME ──"
if command -v java >/dev/null 2>&1; then
  _JAVA_BIN="$(readlink -f "$(command -v java)")"
  _JDK_HOME="$(dirname "$(dirname "$_JAVA_BIN")")"
  if [ -f "${_JDK_HOME}/lib/jrt-fs.jar" ] || [ -d "${_JDK_HOME}/lib" ]; then
    if [ -z "$JAVA_HOME" ]; then
      echo "  → 检测到 JDK: $_JDK_HOME"
      echo "  → 写入 JAVA_HOME 到 /etc/profile.d/aidev-java.sh ..."
      mkdir -p /etc/profile.d
      cat > /etc/profile.d/aidev-java.sh << EOFPROF
export JAVA_HOME="$_JDK_HOME"
export PATH="\${JAVA_HOME}/bin:\$PATH"
EOFPROF
      chmod +x /etc/profile.d/aidev-java.sh
      export JAVA_HOME="$_JDK_HOME"
      echo "  ✓ JAVA_HOME 已设为 $_JDK_HOME"
      echo "    当前会话已生效，新登录终端自动加载。"
    else
      echo "  ✓ JAVA_HOME 已设置: $JAVA_HOME"
    fi
  else
    echo "  ⚠ 找到 java 但无法确定 JDK 目录: $_JAVA_BIN"
  fi
else
  echo "  ⚠ java 未安装，无法设置 JAVA_HOME"
  echo "    运行: setup-dev-env --android"
fi
echo

# ── [7] 修复全局 Gradle 配置（aapt2 QEMU 包装器 override）──
echo "── [7] 修复全局 Gradle 配置 ──"
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
  echo "  ✓ 已追加全局 Gradle 属性到 $GRADLE_PROPS"
else
  echo "  ✓ 全局 Gradle 属性已存在"
fi
echo

echo "═══════════════════════════════════════════"
echo "修复完成。建议运行: check-dev-env"
echo "═══════════════════════════════════════════"
