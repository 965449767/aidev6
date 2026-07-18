# Test: aidev-bridge
SCRIPT="$ASSETS_DIR/aidev-bridge.sh"

# no args -> usage
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "send" "usage should list send subcommand"
assert_contains "$output" "status" "usage should list status subcommand"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"

# unknown subcommand
output=$(bash "$SCRIPT" unknown 2>&1 || true)
assert_contains "$output" "用法" "unknown subcommand should show usage"
