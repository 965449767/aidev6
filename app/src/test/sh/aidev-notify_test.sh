# Test: aidev-notify
SCRIPT="$ASSETS_DIR/aidev-notify.sh"

# no args -> error about missing message
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "aidev-notify" "--help should show script name"

# unknown flag is consumed as message by *) case
output=$(bash "$SCRIPT" --unknown 2>&1 || true)
assert_contains "$output" "通知" "unknown flag should be treated as message"
