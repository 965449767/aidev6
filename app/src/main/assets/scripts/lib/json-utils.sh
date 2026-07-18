# shellcheck shell=sh
# JSON 工具函数

# 转义字符串用于 JSON 字符串值
# 用法: echo "$str" | json_escape
json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
