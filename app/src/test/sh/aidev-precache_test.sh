# Test: aidev-precache
SCRIPT="$ASSETS_DIR/aidev-precache.sh"

# no args -> checks for gradle; use timeout to avoid long gradle operations
output=$(timeout 10 bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "." "no args should produce some output"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "aidev-precache" "help should mention script name"
