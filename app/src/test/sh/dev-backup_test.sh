# Test: dev-backup
SCRIPT="$ASSETS_DIR/dev-backup.sh"

# no args -> shows backup header with default path; timeout to avoid long copy
TMPDIR=$(mktemp -d)
output=$(timeout 5 bash "$SCRIPT" "$TMPDIR" 2>&1 || true)
assert_contains "$output" "备份" "should print backup header"
assert_contains "$output" "$TMPDIR" "custom path should appear in header"
rm -rf "$TMPDIR"
