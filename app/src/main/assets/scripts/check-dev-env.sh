#!/bin/sh
set +e

echo "═══════════════════════════════════════════"
echo "      AIDev 开发环境检测"
echo "═══════════════════════════════════════════"
echo

# ── [0] 系统 ──
echo "── [0] 系统 ──"
echo "  OS:    $(grep PRETTY_NAME /etc/os-release 2>/dev/null | cut -d= -f2- | tr -d '"' || echo 'unknown')"
echo "  Arch:  $(uname -m)"
echo "  Date:  $(date '+%Y-%m-%d %H:%M:%S')"
echo

# ── [1] 环境变量 ──
echo "── [1] 环境变量 ──"
for _var in "ANDROID_SDK_ROOT" "GRADLE_USER_HOME"; do
  _val="$(eval echo \$$_var)"
  if [ -z "$_val" ]; then
    echo "  ✗ $_var → (未设置)"
    echo "    .aidevrc 应在终端启动时自动设置，请确认在 AIDev 终端内运行"
  elif [ -d "$_val" ]; then
    echo "  ✓ $_var → $_val"
  else
    echo "  ⚠ $_var → $_val (路径不存在)"
  fi
done
# JAVA_HOME 为可选，Gradle 不强制要求
_JAVA_HOME_VAL="$(eval echo \$JAVA_HOME)"
if [ -z "$_JAVA_HOME_VAL" ]; then
  echo "  ⚠ JAVA_HOME → (未设置，可选)"
  echo "    Gradle 不需要 JAVA_HOME（java 在 PATH 即可）"
elif [ -d "$_JAVA_HOME_VAL" ]; then
  echo "  ✓ JAVA_HOME → $_JAVA_HOME_VAL"
else
  echo "  ⚠ JAVA_HOME → $_JAVA_HOME_VAL (路径不存在)"
fi
# PATH 中 java 可达性（比检查特定路径更准确）
if command -v java >/dev/null 2>&1; then
  _JAVA_PATH="$(command -v java)"
  echo "  ✓ java 在 PATH 中: $_JAVA_PATH"
else
  echo "  ✗ java 不在 PATH 中（构建将失败）"
fi
echo

# ── [2] JDK ──
echo "── [2] JDK ──"
if command -v java >/dev/null 2>&1; then
  JAVA_VER=$(java -version 2>&1 | head -1)
  echo "  ✓ java: $JAVA_VER"
  if echo "$JAVA_VER" | grep -q '17\.'; then
    echo "    → JDK 17 ✓ AGP 9 兼容"
  else
    echo "    ⚠ 推荐 JDK 17（当前版本可能不兼容 AGP 9）"
  fi
else
  echo "  ✗ java 未安装"
  echo "    运行: setup-dev-env --android"
fi
echo

# ── [3] Android SDK ──
echo "── [3] Android SDK ──"
SDK="${ANDROID_SDK_ROOT:-/host-home/android-sdk}"
if [ ! -d "$SDK" ]; then
  echo "  ✗ SDK 目录 $SDK 不存在"
  echo "    运行: setup-dev-env --android"
else
  [ -d "$SDK/platforms/android-36" ]      && echo "  ✓ platforms/android-36"      || echo "  ✗ platforms/android-36 缺失（构建将失败）"
  [ -d "$SDK/build-tools/36.1.0" ]        && echo "  ✓ build-tools/36.1.0"        || echo "  ⚠ build-tools/36.1.0 缺失（Gradle 会从 Maven 下载）"
  [ -d "$SDK/build-tools/34.0.0" ]        && echo "  ✓ build-tools/34.0.0（兜底）" || echo "  ⚠ build-tools/34.0.0 缺失（可选兜底）"
  [ -f "$SDK/platform-tools/adb" ]        && echo "  ✓ platform-tools (adb)"       || echo "  ⚠ platform-tools 缺失（不影响编译）"
  [ -f "$SDK/cmdline-tools/latest/bin/sdkmanager" ] && echo "  ✓ cmdline-tools (sdkmanager)" || echo "  ⚠ cmdline-tools 缺失（不影响编译，仅 SDK 管理用）"
fi
echo

# ── [4] aapt2 ──
echo "── [4] aapt2 ──"
_AAPT2_OK=false
for _bt in "$SDK/build-tools"/*/; do
  [ -f "${_bt}aapt2" ] || continue
  if "${_bt}aapt2" version >/dev/null 2>&1; then
    echo "  ✓ 原生 aapt2 可用: ${_bt}aapt2"
    _AAPT2_OK=true
    break
  fi
done
if [ "$_AAPT2_OK" = false ] && [ -x /host-home/x86_64/aapt2 ]; then
  if /host-home/x86_64/aapt2 version >/dev/null 2>&1; then
    echo "  ✓ QEMU 包装 aapt2 可用: /host-home/x86_64/aapt2"
    _AAPT2_OK=true
  fi
fi
if [ "$_AAPT2_OK" = false ]; then
  echo "  ⚠ 未找到可用的 aapt2"
  echo "    构建时 AGP 9 会从 Maven 下载 ARM64 版 aapt2，不影响编译。"
  echo "    若需 create-compose-project 检测通过，请重启 AIDev 终端。"
  if [ "$(uname -m)" = "aarch64" ]; then
    echo "    ℹ ARM64 环境，可运行 aidev-doctor 部署 QEMU 包装器"
  fi
fi
echo

# ── [5] Gradle 环境 ──
echo "── [5] Gradle 环境 ──"
GH="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ -d "$GH" ]; then
  echo "  ✓ GRADLE_USER_HOME: $GH"
  echo "  全局 gradle.properties:"
  if [ -f "$GH/gradle.properties" ]; then
    grep -q "aapt2FromMavenOverride" "$GH/gradle.properties" 2>/dev/null && \
      echo "    ✓ aapt2 override 已配置" || \
      echo "    ⚠ aapt2 override 缺失（运行: repair-dev-env）"
    grep -q "aapt2DaemonMode" "$GH/gradle.properties" 2>/dev/null && \
      echo "    ✓ aapt2 daemon mode=关" || \
      echo "    ⚠ aapt2 daemon mode 未配置（运行: repair-dev-env）"
  else
    echo "    ⚠ 文件不存在（运行: repair-dev-env）"
  fi
  echo "  init.d 脚本:"
  for _f in "wrap-native.gradle" "performance.gradle" "copy-apk.gradle"; do
    [ -f "$GH/init.d/$_f" ] && echo "    ✓ $_f" || echo "    ⚠ $_f 缺失（重启 AIDev 终端自动生成）"
  done
  GRADLE_ZIPS=$(find "$GH/wrapper/dists" -name 'gradle-9.1.0-*-bin.zip' 2>/dev/null | head -3)
  if [ -n "$GRADLE_ZIPS" ]; then
    echo "  ✓ Gradle 9.1.0 分发已缓存"
  else
    echo "  ⚠ Gradle 9.1.0 分发未缓存（首次构建自动下载）"
  fi
  MOD_COUNT=$(find "$GH/caches/modules-2/files-2.1" -maxdepth 4 -type d 2>/dev/null | wc -l)
  if [ "$MOD_COUNT" -gt 10 ]; then
    echo "  ✓ Maven 依赖缓存: ~${MOD_COUNT} 个模块"
  else
    echo "  ⚠ Maven 依赖缓存较少（首次构建自动下载）"
  fi
else
  echo "  ⚠ GRADLE_USER_HOME ($GH) 不存在"
fi
echo

# ── [6] 项目模板 ──
echo "── [6] create-compose-project 模板 ──"
TPL="$HOME/.gradle/template-wrapper"
if [ -f "$TPL/gradlew" ] && [ -f "$TPL/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "  ✓ 模板目录完整: $TPL"
else
  echo "  ⚠ 模板不完整（重启 AIDev 终端自动生成）"
fi
echo

# ── [7] 网络连通性 ──
echo "── [7] 网络连通性 ──"
getent hosts github.com >/dev/null 2>&1 && echo "  ✓ DNS: github.com 可解析" || echo "  ⚠ DNS: github.com 解析失败"
getent hosts maven.aliyun.com >/dev/null 2>&1 && echo "  ✓ DNS: maven.aliyun.com 可解析" || echo "  ⚠ DNS: maven.aliyun.com 解析失败（国内 Maven 镜像）"
getent hosts services.gradle.org >/dev/null 2>&1 && echo "  ✓ DNS: services.gradle.org 可解析" || echo "  ⚠ DNS: services.gradle.org 解析失败"
if command -v curl >/dev/null 2>&1; then
  _ALIYUN_MS=$(curl -s -o /dev/null -w '%{time_total}' --connect-timeout 5 https://maven.aliyun.com/ 2>/dev/null || echo '0')
  if [ "$_ALIYUN_MS" != '0' ] && [ -n "$_ALIYUN_MS" ]; then
    echo "  ✓ 阿里云 Maven: 可达 (${_ALIYUN_MS}s)"
  else
    echo "  ⚠ 阿里云 Maven: 不可达（不影响已缓存依赖的离线构建）"
  fi
  _GRADLE_MS=$(curl -s -o /dev/null -w '%{time_total}' --connect-timeout 5 https://services.gradle.org/ 2>/dev/null || echo '0')
  if [ "$_GRADLE_MS" != '0' ] && [ -n "$_GRADLE_MS" ]; then
    echo "  ✓ Gradle 分发源: 可达 (${_GRADLE_MS}s)"
  else
    echo "  ⚠ Gradle 分发源: 不可达（首次构建需下载 Gradle 分发包）"
  fi
fi
echo

# ── [8] 通用工具 ──
echo "── [8] 通用工具 ──"
for _c in curl git unzip zip tar python3 node npm openssl; do
  if command -v "$_c" >/dev/null 2>&1; then
    echo "  ✓ $_c"
  else
    echo "  ⚠ $_c 未安装（运行 setup-dev-env 安装）"
  fi
done
echo

# ── [9] 可选工具 ──
echo "── [9] 可选工具（未装属正常）──"
for _c in gcc g++ cmake ninja rustc cargo ndk-build; do
  command -v "$_c" >/dev/null 2>&1 && echo "  ✓ $_c"
done
echo

echo "═══════════════════════════════════════════"
echo "检测完成。如有 ⚠/✗ 项，运行: repair-dev-env"
echo "═══════════════════════════════════════════"
