# Test: aidev-build-log
SCRIPT="$ASSETS_DIR/aidev-build-log.sh"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"

# no args -> error
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "错误" "no args should show error"
assert_contains "$output" "项目名称" "no args should ask for project"

# non-existent project
output=$(bash "$SCRIPT" NonExistentProject 2>&1 || true)
assert_contains "$output" "未找到" "non-existent project should show error"

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
