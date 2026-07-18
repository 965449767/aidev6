# Test: aidev-build-request
SCRIPT="$ASSETS_DIR/aidev-build-request.sh"

# 当前契约：缺 --project 必须报错并提示用法（不再无参提交，见强制 --project 改动）
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "必须通过 --project" "no project should error"
assert_contains "$output" "用法" "error should show usage"

# --no-install 但缺 --project 同样报错
output=$(bash "$SCRIPT" --no-install 2>&1 || true)
assert_contains "$output" "必须通过 --project" "--no-install without project should still error"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "Usage" "--help should show usage"

# 带 --project 时应提交构建请求（用 timeout 避免阻塞在结果轮询）
output=$(timeout 5 bash "$SCRIPT" --project /workspace/__nonexist_probe__ 2>&1 || true)
assert_contains "$output" "构建请求" "with --project should submit build request"
