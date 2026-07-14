#!/usr/bin/env bash
#
# safe_bash_guard.sh — Agent 命令安全护栏（PRoot 终端可选 wrapper）。
# 规则与 app 端 com.aidev.six.SafeCommandGuard 保持一致：只拦明确危险 / 对受保护外部路径的破坏性写，
# 放行正常构建产物拷贝（cp/mv 到 /sdcard）等良性操作。
#
# 用法: safe_bash_guard.sh <command string>   → 通过则输出 "ALLOW"，否则输出 "BLOCKED: ..." 并 exit 1
set -euo pipefail

cmd="${*:-}"

if [[ -z "$cmd" ]]; then
  echo "Usage: $0 <command string>" >&2
  exit 2
fi

lower_cmd="$(printf '%s' "$cmd" | tr '[:upper:]' '[:lower:]')"

# 1) 危险命令模式（fail-safe：命中即拦截）
dangerous_patterns=(
  "rm -rf /"
  "rm -rf ."
  "rm -rf ~"
  "mkfs"
  "dd if="
  ":(){"
  ":(){ :|:& };:}"
  "git reset --hard"
  "git clean -fd"
  "git push --force"
  "git push -f "
  "drop database"
  "truncate table"
  "supabase db reset"
  "prisma migrate reset"
)
for pattern in "${dangerous_patterns[@]}"; do
  if [[ "$lower_cmd" == *"$pattern"* ]]; then
    echo "BLOCKED: 危险命令模式: $pattern"
    exit 1
  fi
done

# 2) 受保护路径（/sdcard / storage / data）上的"破坏性"写需确认
#    良性拷贝（cp/mv 到 /sdcard）正常放行，避免误伤构建产物落盘。
protected_prefixes=("/sdcard/" "/storage/emulated/0/" "/storage/emulated/" "/data/")
destructive_verbs=("rm " "mkfs" "dd " "truncate " "chmod " "chown " "format " ">" "shred ")
for prefix in "${protected_prefixes[@]}"; do
  if [[ "$lower_cmd" == *"$prefix"* ]]; then
    for verb in "${destructive_verbs[@]}"; do
      if [[ "$lower_cmd" == *"$verb"* ]]; then
        echo "BLOCKED: 对受保护路径 $prefix 执行破坏性写，需用户确认: $cmd"
        exit 1
      fi
    done
  fi
done

echo "ALLOW"
