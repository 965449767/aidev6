# Test: aidev-clean
SCRIPT="$ASSETS_DIR/aidev-clean.sh"

# no args -> shows usage (falls through to *)
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "全部清理" "usage should list all-clean option"
assert_contains "$output" "选项" "usage should list options"
