# Test: aidev-repo
SCRIPT="$ASSETS_DIR/aidev-repo.sh"

# no args -> defaults to help
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "resolve" "usage should list resolve"

# help flag
output=$(bash "$SCRIPT" help 2>&1 || true)
assert_contains "$output" "用法" "help subcommand should show usage"
assert_contains "$output" "decide" "help should list decide"

# unknown subcommand -> error
output=$(bash "$SCRIPT" unknown 2>&1 || true)
assert_contains "$output" "未知" "unknown subcommand should error"

# root subcommand
output=$(bash "$SCRIPT" root 2>&1 || true)
assert_contains "$output" "AIDevRepo" "root should print repo path"
