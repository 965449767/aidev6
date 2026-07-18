# Test: aidev-dumpsys
SCRIPT="$ASSETS_DIR/aidev-dumpsys.sh"

# no args -> defaults to help
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "meminfo" "usage should list meminfo"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "activity" "help should list activity subcommand"

# unknown subsystem (goes to cmd_raw)
output=$(bash "$SCRIPT" __nonexistent_subsystem__ 2>&1 || true)
assert_contains "$output" "dumpsys" "unknown subsystem should attempt dumpsys"
