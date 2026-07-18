# shellcheck shell=sh
# Shizuku 通用函数

# 通过 Shizuku 读取文件（忽略错误，如文件不存在时静默返回空）
# 用法: shizuku_cat <file_path>
shizuku_cat() { $SHIZUKU exec "cat '$1'" 2>/dev/null || true; }

# 通过 Shizuku 列出目录（忽略错误）
# 用法: shizuku_ls <dir_path>
shizuku_ls() { $SHIZUKU exec "ls -lt '$1'" 2>/dev/null || true; }
