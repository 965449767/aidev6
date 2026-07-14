#!/bin/sh
# aidev-crash-why: 分析运行时崩溃日志
# 用法: aidev-crash-why [--anr|--logcat <文件>]
set -e

usage() {
    echo "用法: aidev-crash-why [选项]"
    echo "  (从 stdin 读取日志)"
    echo "  --anr              分析最新的 ANR trace"
    echo "  --logcat <文件>     分析 logcat 文件"
    echo "  --help, -h         显示此帮助"
}

cmd_stdin() {
    local input
    input=$(cat)
    analyze "$input"
}

cmd_anr() {
    local content
    content=$(aidev-anr latest 2>/dev/null) || { echo "无法读取 ANR trace"; return 1; }
    echo "$content"
    echo ""
    echo "═══ 分析结果 ═══"
    analyze "$content"
}

cmd_logcat() {
    local file="$1"
    [ -f "$file" ] || { echo "文件不存在: $file"; return 1; }
    local content
    content=$(cat "$file")
    analyze "$content"
}

analyze() {
    local input="$1"
    local found=false

    # ANR
    if echo "$input" | grep -qiE "^ANR|ANR in|reason:"; then
        echo "🔴 检测到 ANR (应用无响应)"
        local reason
        reason=$(echo "$input" | grep -i "reason:" | head -1)
        [ -n "$reason" ] && echo "   原因: $reason"
        local proc
        proc=$(echo "$input" | grep -i "ANR in" | head -1)
        [ -n "$proc" ] && echo "   进程: $proc"
        found=true
    fi

    # Java FATAL EXCEPTION
    if echo "$input" | grep -qi "FATAL EXCEPTION"; then
        echo "🔴 检测到 Java 崩溃 (FATAL EXCEPTION)"
        local exc
        exc=$(echo "$input" | grep -A1 "FATAL EXCEPTION" | head -2)
        echo "   $exc"
        found=true
    fi

    # NullPointerException
    if echo "$input" | grep -qi "NullPointerException"; then
        echo "🟠 NullPointerException"
        echo "$input" | grep -B1 -A2 "NullPointerException" | head -5 | sed 's/^/   /'
        found=true
    fi

    # ClassNotFoundException
    if echo "$input" | grep -qi "ClassNotFoundException\|NoClassDefFoundError"; then
        echo "🟠 类未找到: ClassNotFoundException / NoClassDefFoundError"
        echo "$input" | grep -i "ClassNotFoundException\|NoClassDefFoundError" | head -3 | sed 's/^/   /'
        found=true
    fi

    # OutOfMemoryError
    if echo "$input" | grep -qi "OutOfMemoryError\|Out of memory\|OOM"; then
        echo "🔴 OOM (内存不足)"
        echo "$input" | grep -iB2 "OutOfMemoryError\|Out of memory" | head -5 | sed 's/^/   /'
        found=true
    fi

    # Native crash (signal)
    if echo "$input" | grep -qiE "signal [0-9]+|SIGSEGV|SIGABRT|SIGILL|SIGBUS|SIGFPE"; then
        echo "🔴 原生崩溃 (Native Crash)"
        echo "$input" | grep -iE "signal [0-9]+|SIGSEGV|SIGABRT|SIGILL|SIGBUS|SIGFPE|Build fingerprint|Abort message" | head -5 | sed 's/^/   /'
        found=true
    fi

    # StackOverflowError
    if echo "$input" | grep -qi "StackOverflowError"; then
        echo "🟠 StackOverflowError"
        echo "$input" | grep -i "StackOverflowError" | head -3 | sed 's/^/   /'
        found=true
    fi

    # RuntimeException / IllegalStateException
    if echo "$input" | grep -qiE "RuntimeException|IllegalStateException|IllegalArgumentException"; then
        local hits
        hits=$(echo "$input" | grep -iE "RuntimeException|IllegalStateException|IllegalArgumentException" | head -5)
        echo "🟠 运行时异常"
        echo "$hits" | sed 's/^/   /'
        found=true
    fi

    if [ "$found" = false ]; then
        echo "✅ 未检测到已知崩溃模式"
    fi
}

case "${1:-}" in
    --anr|-a)
        cmd_anr
        ;;
    --logcat|-l)
        shift
        cmd_logcat "$@"
        ;;
    --help|-h)
        usage
        ;;
    "")
        cmd_stdin
        ;;
    *)
        echo "未知选项: $1"
        usage
        exit 1
        ;;
esac
