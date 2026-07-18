# Test: aidev-anr
SCRIPT="$ASSETS_DIR/aidev-anr.sh"

# no args -> defaults to help
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "anr" "usage should mention anr"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "list" "help should list subcommands"

# unknown subcommand (passed to cmd_read which will fail gracefully)
output=$(bash "$SCRIPT" __nonexistent_file__ 2>&1 || true)
assert_contains "$output" "." "unknown ANR name should produce output"
