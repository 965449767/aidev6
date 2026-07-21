# Test: aidev-crash-why
SCRIPT="$ASSETS_DIR/aidev-crash-why.sh"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "ANR" "--help should mention ANR"
assert_contains "$output" "logcat" "--help should mention logcat"

# stdin with empty input -> no crash patterns detected
output=$(echo "" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "未检测到" "empty stdin should show no crashes"

# ANR detection via stdin
anr_sample="ANR in com.example.app
Reason: Input dispatching timed out"
output=$(echo "$anr_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "ANR" "ANR sample should be detected"

# FATAL EXCEPTION detection
fatal_sample="FATAL EXCEPTION: main
Process: com.example.app, PID: 12345
java.lang.NullPointerException"
output=$(echo "$fatal_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "FATAL EXCEPTION" "FATAL EXCEPTION should be detected"
assert_contains "$output" "NullPointer" "NPE should be detected"

# OOM detection
oom_sample="java.lang.OutOfMemoryError: Failed to allocate"
output=$(echo "$oom_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "OOM" "OutOfMemoryError should be detected"

# Native crash detection
native_sample="signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"
output=$(echo "$native_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "原生崩溃" "SIGSEGV should be detected"

# ClassNotFoundException detection
cnf_sample="java.lang.ClassNotFoundException: com.example.MissingClass"
output=$(echo "$cnf_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "类未找到" "ClassNotFoundException should be detected"

# StackOverflowError detection
so_sample="java.lang.StackOverflowError: stack size 8MB"
output=$(echo "$so_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "StackOverflowError" "StackOverflowError should be detected"

# RuntimeException detection
re_sample="java.lang.RuntimeException: Unable to start activity"
output=$(echo "$re_sample" | bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "运行时异常" "RuntimeException should be detected"

# unknown option
output=$(bash "$SCRIPT" --invalid-opt 2>&1 || true)
assert_contains "$output" "未知选项" "unknown option should show error"

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
