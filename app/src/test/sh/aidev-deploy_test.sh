# Test: aidev-deploy
SCRIPT="$ASSETS_DIR/aidev-deploy.sh"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "APK" "--help should mention APK"
assert_contains "$output" "包名" "--help should mention package name"

# --version
output=$(bash "$SCRIPT" --version 2>&1 || true)
assert_contains "$output" "aidev-deploy" "--version should show script version"

# missing --apk and --pkg
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "missing" "no args should show missing error"

# missing --pkg
output=$(bash "$SCRIPT" --apk /path/to/app.apk 2>&1 || true)
assert_contains "$output" "missing" "missing --pkg should show error"

# APK not found
output=$(bash "$SCRIPT" --apk /non/existent.apk --pkg com.example.app 2>&1 || true)
assert_contains "$output" "not found" "non-existent APK should show error"

# invalid package name (reachable with --apk pointing to existing file)
tmp_apk=$(mktemp -p /tmp aidev-deploy-test-XXXXXXXX.apk 2>/dev/null || echo "/tmp/aidev-deploy-test.apk")
touch "$tmp_apk" 2>/dev/null || true
output=$(bash "$SCRIPT" --apk "$tmp_apk" --pkg "bad pkg name" 2>&1 || true)
assert_contains "$output" "invalid" "bad package name should show error"
rm -f "$tmp_apk" 2>/dev/null || true

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
