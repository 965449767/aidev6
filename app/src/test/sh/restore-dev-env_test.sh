# Test: restore-dev-env
SCRIPT="$ASSETS_DIR/restore-dev-env.sh"

# no args -> shows restore header (prompts for confirmation); timeout to avoid hang
output=$(timeout 5 bash "$SCRIPT" </dev/null 2>&1 || true)
assert_contains "$output" "恢复" "no args should print restore header"
assert_contains "$output" "SDK" "should mention SDK"
