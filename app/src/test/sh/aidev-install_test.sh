# Test: aidev-install
SCRIPT="$ASSETS_DIR/aidev-install.sh"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "静默" "--help should mention silent mode"
assert_contains "$output" "系统安装" "--help should mention GUI mode"

# -h
output=$(bash "$SCRIPT" -h 2>&1 || true)
assert_contains "$output" "用法" "-h should show usage"

# no APK found (auto-discover may find one on real device, so test skipped)

# non-existent APK
output=$(bash "$SCRIPT" /non/existent.apk 2>&1 || true)
assert_contains "$output" "找不到" "non-existent APK should show error"

# --uninstall with bad package name
output=$(bash "$SCRIPT" --uninstall "bad name" 2>&1 || true)
assert_contains "$output" "非法" "bad uninstall package name should show error"

# --status (wraps to aidev-shizuku status; will fail if shizuku not available)
bash "$SCRIPT" --status 2>&1 || true

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
