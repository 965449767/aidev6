# Test: aidev-shizuku
SCRIPT="$ASSETS_DIR/aidev-shizuku.sh"

# dash 兼容静态检查（对齐真机 PRoot /bin/sh=dash）
assert_dash_compatible "$SCRIPT"

# no args -> help (用 dash 实跑，对齐真机)
output=$(dash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "exec" "no args should show subcommands"
assert_contains "$output" "install" "should list install"

# unknown subcommand
output=$(dash "$SCRIPT" unknown 2>&1 || true)
assert_contains "$output" "用法" "unknown subcommand should show usage"
