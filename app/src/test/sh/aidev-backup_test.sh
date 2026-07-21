# Test: aidev-backup
SCRIPT="$ASSETS_DIR/aidev-backup.sh"

# help
output=$(bash "$SCRIPT" help 2>&1 || true)
assert_contains "$output" "用法" "help should show usage"
assert_contains "$output" "create" "help should list create"
assert_contains "$output" "list" "help should list list"
assert_contains "$output" "restore" "help should list restore"
assert_contains "$output" "delete" "help should list delete"

# --help
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"

# list with no backups
output=$(BACKUP_DIR=/tmp/aidev-test-backups bash "$SCRIPT" list 2>&1 || true)
assert_contains "$output" "暂无备份" "list on empty dir should show no backups"

# info non-existent backup
output=$(bash "$SCRIPT" info non-existent-backup 2>&1 || true)
assert_contains "$output" "不存在" "info on missing backup should show error"

# delete non-existent backup
output=$(bash "$SCRIPT" delete non-existent-backup 2>&1 || true)
assert_contains "$output" "不存在" "delete on missing backup should show error"

# invalid subcommand
output=$(bash "$SCRIPT" invalid-cmd 2>&1 || true)
assert_contains "$output" "未知子命令" "invalid subcommand should show error"

assert_syntax_ok "$SCRIPT"
assert_dash_compatible "$SCRIPT"
