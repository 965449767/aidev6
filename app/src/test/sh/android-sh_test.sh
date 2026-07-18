# Test: android-sh
SCRIPT="$ASSETS_DIR/android-sh.sh"

# no args -> usage hint and exit 1
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "用法" "no args should show usage"
assert_contains "$output" "android-sh" "usage should mention script name"

# --help flag (android-sh has no --help; arg is passed to shizuku exec)
# It should still produce output (error from shizuku or usage)
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "." "--help arg should produce some output"
