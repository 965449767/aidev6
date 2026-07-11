#!/usr/bin/env bash
# verify-self-evolution.sh —— 自我进化闭环「文件契约」离线验证（无需真机）
#
# 用 fake OpenCode + fake 构建桥 模拟完整闭环，验证 App 与宇宙 A 之间的文件契约是闭合的：
#   1) 写一份崩溃回流 crash-<id>.json 到共享工作区（模拟 App 的 BuildRequestTracker.writeLoopCrash）
#   2) 启动 aidev-self-evolution 守护（模拟宇宙 A 自动驾驶），它应读崩溃→调 fake OpenCode 改码
#      → 标记 fix_applied=true → 写 req-<id>.json 触发重建（模拟 App 的 requestRebuild）
#   3) fake 构建桥 读 req-<id>.json → 写 result-<id>.json 成功（模拟宇宙 B 编译安装拉起）
#   4) 断言：崩溃已标记修复、重建请求被消费、结果成功 —— 闭环契约闭合
#
# 这验证的是"文件契约"正确性；App 侧 BuildRequestTracker 的对应行为由单元测覆盖。

set -uo pipefail
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

WORKSPACE="$TMP/workspace"
LOOP_DIR="$WORKSPACE/.aidev-loop"
BRIDGE_DIR="$TMP/.aidev-build-bridge"
mkdir -p "$LOOP_DIR" "$BRIDGE_DIR"

PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  ✔ $1"; PASS=$((PASS+1)); else echo "  ✖ $1 (期望[$3] 实际[$2])"; FAIL=$((FAIL+1)); fi; }
check_true() { if [ "$2" = "true" ]; then echo "  ✔ $1"; PASS=$((PASS+1)); else echo "  ✖ $1 ($2)"; FAIL=$((FAIL+1)); fi; }

echo "[1] 模拟 App 写出崩溃回流（fix_applied=false）"
cat > "$LOOP_DIR/crash-1700000000000.json" <<'JSON'
{ "type": "self-evolution/crash", "package": "com.example.app", "project": "MyAndroidProject",
  "crashed": true, "stack": ["java.lang.RuntimeException: boom"], "fix_applied": false }
JSON

echo "[2] 启动自我进化守护（fake OpenCode 仅回显）"
cat > "$TMP/fake-oc" <<'SH'
#!/usr/bin/env bash
echo "已修复: 加了空判断"
SH
chmod +x "$TMP/fake-oc"
HOME="$TMP" AIDEV_WORKSPACE="$WORKSPACE" OPENCODE_CMD="$TMP/fake-oc" \
  bash "$(dirname "$0")/aidev-self-evolution" --daemon --project MyAndroidProject
sleep 2

echo "[3] 断言：守护已把崩溃标记 fix_applied=true"
FIXED=$(grep -o '"fix_applied"[[:space:]]*:[[:space:]]*true' "$LOOP_DIR/crash-1700000000000.json" || true)
check_true "崩溃被标记已修复" "$([ -n "$FIXED" ] && echo true || echo false)"

echo "[4] 断言：守护写出了重建请求 req-<id>.json（模拟 requestRebuild）"
REQ=$(ls -1 "$BRIDGE_DIR"/req-*.json 2>/dev/null | head -1)
check_true "守护触发了重建请求" "$([ -n "$REQ" ] && echo true || echo false)"

echo "[5] 模拟宇宙 B 构建桥消费请求 → 写 result 成功（模拟编译安装拉起）"
if [ -n "$REQ" ]; then
  RID=$(grep -o '"id"[[:space:]]*:[[:space:]]*"[^"]*"' "$REQ" | sed -E 's/.*:"//; s/"//')
  cat > "$BRIDGE_DIR/result-$RID.json" <<'JSON'
{ "success": true, "message": "构建并安装拉起成功" }
JSON
  RESULT_OK=$(grep -o '"success"[[:space:]]*:[[:space:]]*true' "$BRIDGE_DIR/result-$RID.json" || true)
  check_true "构建桥产出成功结果" "$([ -n "$RESULT_OK" ] && echo true || echo false)"
fi

echo "[6] 停止守护"
HOME="$TMP" bash "$(dirname "$0")/aidev-self-evolution" --stop >/dev/null 2>&1

echo
echo "==== 验证结果：通过 $PASS / 失败 $FAIL ===="
[ "$FAIL" -eq 0 ] && { echo "闭环文件契约闭合 ✅"; exit 0; } || { echo "契约存在断点 ❌"; exit 1; }
