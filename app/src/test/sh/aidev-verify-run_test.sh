# Test: aidev-verify-run
SCRIPT="$ASSETS_DIR/aidev-verify-run.sh"

# no args -> JSON error about missing --pkg
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "missing --pkg" "no args should report missing --pkg"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "包名" "help should mention pkg parameter"
