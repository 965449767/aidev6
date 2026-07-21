# Test: aidev-autoinstall
SCRIPT="$ASSETS_DIR/aidev-autoinstall.sh"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "launch" "--help should mention --launch"
assert_contains "$output" "项目目录" "--help should mention project strategy"

# -h
output=$(bash "$SCRIPT" -h 2>&1 || true)
assert_contains "$output" "用法" "-h should show usage"

# no args, not in project -> should ask for APK path
output=$(cd /tmp && bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "不是 Android 项目" "non-project dir should ask for APK path"

# non-existent APK
output=$(bash "$SCRIPT" /non/existent.apk 2>&1 || true)
assert_contains "$output" "找不到" "non-existent APK should show error"

# --uninstall with bad package name
output=$(bash "$SCRIPT" --uninstall "bad name" 2>&1 || true)
assert_contains "$output" "非法" "bad uninstall package name should show error"

# --json + non-existent APK
output=$(bash "$SCRIPT" --json /non/existent.apk 2>&1 || true)
assert_contains "$output" '"installed":false' "--json should output JSON"

# --launch + --json + non-existent APK
output=$(bash "$SCRIPT" --launch com.example.app --json /non/existent.apk 2>&1 || true)
assert_contains "$output" '"pkg":"com.example.app"' "--launch should pass pkg to JSON output"

# --status (wraps to aidev-shizuku status)
bash "$SCRIPT" --status 2>&1 || true

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
