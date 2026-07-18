# Test: deploy-dev-env
SCRIPT="$ASSETS_DIR/deploy-dev-env.sh"

# no args -> starts checking packages (prints header); timeout to avoid apt hangs
output=$(timeout 10 bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "AIDev" "no args should print header"
assert_contains "$output" "检测" "should mention detection/checking"
