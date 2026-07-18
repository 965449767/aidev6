# Test helpers for AIDev shell script tests
PASS=0
FAIL=0
SCRIPT_DIR=""

init_tests() {
    ASSETS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../main/assets/scripts" && pwd)"
    PASS=0
    FAIL=0
}

ok() {
    PASS=$((PASS + 1))
}

fail() {
    local msg="$1"
    FAIL=$((FAIL + 1))
    echo "  FAIL: $msg" >&2
}

assert_eq() {
    local expected="$1"
    local actual="$2"
    local msg="${3:-expected '$expected', got '$actual'}"
    if [ "$expected" = "$actual" ]; then
        ok
    else
        fail "$msg"
    fi
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local msg="${3:-expected to contain '$needle'}"
    if echo "$haystack" | grep -qF "$needle"; then
        ok
    else
        fail "$msg"
    fi
}

assert_not_contains() {
    local haystack="$1"
    local needle="$2"
    local msg="${3:-expected NOT to contain '$needle'}"
    if echo "$haystack" | grep -qF "$needle"; then
        fail "$msg"
    else
        ok
    fi
}

# dash -n 优先（对齐真机 PRoot 的 /bin/sh=dash）；无 dash 时退 sh -n。
# 真机 PRoot 的 /bin/sh 是 dash，不认 pipefail/[[/函数外 local；bash -n 会漏掉这些坑，
# 故语法校验必须以 dash 为基线（见 docs/error-journal 2026-07-17 复盘）。
assert_syntax_ok() {
    local file="$1"
    if command -v dash >/dev/null 2>&1; then
        if dash -n "$file" 2>/dev/null; then
            ok
        else
            fail "dash syntax error in $file"
        fi
    elif sh -n "$file" 2>/dev/null; then
        ok
    else
        fail "syntax error in $file"
    fi
}

# 静态规则：拦住真机 dash 下会崩、但 bash 放行的 bashism。
# 1) set -[a-zA-Z]*o pipefail / set -o pipefail
# 2) 函数外出现 local
# 用 awk 精确追踪花括号深度，正确识别函数体（支持 name()/name() {/function name() {/跨行 {）。
# 注：裸词误赋值（如 `out rc`）dash -n 无法静态识别、且易与函数调用混淆，改由专项测试用 dash 实跑暴露。
assert_dash_compatible() {
    local file="$1"
    local bad=0
    # 规则1：pipefail
    if grep -nE '^set -[a-zA-Z]*o pipefail|set -o pipefail' "$file" >/dev/null 2>&1; then
        fail "pipefail (dash 不支持) in $file"
        bad=1
    fi
    # 规则2：函数外 local。用 awk 状态机追踪花括号深度，跳过引号/heredoc 内的 {}，
    # 正确识别函数体（支持 name() / name() { / function name() { / 跨行 {）。
    local awk_out
    awk_out=$(awk '
        function trim(s){ sub(/^[ \t]+/,"",s); return s }
        BEGIN { depth=0; pending=0; q=0; heredoc=0; hdelim="" }
        {
            line = $0
            raw = line
            s = trim(raw)
            # heredoc 边界
            if (heredoc) {
                if (s == hdelim) { heredoc=0 }
                next
            }
            # 跳过引号内字符，提取"裸"代码符号
            code = ""
            i = 1; n = length(raw); inq = 0; qc = ""
            while (i <= n) {
                c = substr(raw, i, 1)
                if (inq) {
                    if (c == qc) { inq = 0 }
                } else {
                    if (c == "\047" || c == "\042") { inq = 1; qc = c }
                    else { code = code c }
                }
                i++
            }
            # heredoc 开始: <<[/-]WORD
            if (code ~ /<<[-]?[ \t]*[A-Za-z_][A-Za-z0-9_]*/) {
                # 取 delimiter
                m = code
                sub(/^.*<</, "", m); sub(/^[ \t]*/, "", m); sub(/[ \t].*$/, "", m)
                hdeloc = 0
                if (substr(m,1,1) == "-") { hdelim = substr(m,2) } else { hdelim = m }
                heredoc = 1
                # 同一行也可能结束，但简化处理：下一行判断
            }
            # 函数头
            if (s ~ /^[A-Za-z_][A-Za-z0-9_]*\(\)$/ || s ~ /^function[ \t]+[A-Za-z_][A-Za-z0-9_]*\(\)[ \t]*$/ || s ~ /^[A-Za-z_][A-Za-z0-9_]*\(\)[ \t]*\{[ \t]*$/ || s ~ /^function[ \t]+[A-Za-z_][A-Za-z0-9_]*\(\)[ \t]*\{[ \t]*$/) {
                pending = 1
                if (s ~ /\{/) { depth++; pending = 0 }
                next
            }
            if (pending) {
                if (s ~ /^\{/) { depth++; pending = 0 }
                else if (s != "") { pending = 0 }
                next
            }
            # 统计代码内 {}（已去引号）
            nopen = gsub(/\{/, "{", code); nclose = gsub(/\}/, "}", code)
            depth += nopen - nclose
            if (depth < 0) depth = 0
            # 函数外 local（基于原始行首，避免误伤注释内）
            if (depth == 0 && raw ~ /^[ \t]*local[ \t]/) {
                print "TOPLOCAL:" raw
            }
        }
        END {}
    ' "$file")
    if [ -n "$awk_out" ]; then
        echo "$awk_out" | while IFS= read -r l; do
            case "$l" in
                TOPLOCAL:*) fail "top-level 'local' (dash 仅允许函数内) in $file: ${l#TOPLOCAL:}" ; bad=1 ;;
            esac
        done
    fi
    [ "$bad" -eq 0 ] && ok
}

assert_exit_code() {
    local expected="$1"
    shift
    local actual=0
    "$@" 2>/dev/null || actual=$?
    if [ "$actual" -eq "$expected" ]; then
        ok
    else
        fail "exit code: expected $expected, got $actual for: $*"
    fi
}

summary() {
    local name="${1:-tests}"
    echo ""
    echo "═══ $name: $PASS passed, $FAIL failed ═══"
    return $FAIL
}
