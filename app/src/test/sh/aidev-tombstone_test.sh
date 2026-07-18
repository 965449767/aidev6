# Test: aidev-tombstone
SCRIPT="$ASSETS_DIR/aidev-tombstone.sh"

# no args -> defaults to help
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "tombstone" "usage should mention tombstone"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "list" "help should list subcommands"

# unknown subcommand (passed to cmd_read which will fail gracefully)
output=$(bash "$SCRIPT" __nonexistent_file__ 2>&1 || true)
assert_contains "$output" "." "unknown tombstone name should produce output"
