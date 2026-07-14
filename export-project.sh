#!/usr/bin/env bash
#
# export-project.sh — 把安卓/通用项目源码合成为一份 AI 可读文档（Markdown 或纯文本）。
# 复刻 app/src/main/java/com/aidev/six/ProjectExporter.kt 的逻辑：
#   开头附目录树与统计，随后每个源文件以「相对路径 + 代码块」呈现。
#   默认排除构建产物、IDE 缓存、二进制；.git 可选择包含。
#   默认把当前项目导出到 SD 卡根目录：/sdcard/<项目名>-source.md
#
# 用法:
#   ./export-project.sh [选项] [项目目录] [输出文件]
#     -g, --include-git   包含 .git 目录（默认排除）
#     -p, --plain-text    纯文本格式（默认 Markdown）
#     -m, --max-bytes N   单文件大小上限，默认 524288 (512KB)
#     -o, --output FILE   指定输出文件（覆盖默认 SD 卡路径）
#     -h, --help          显示帮助
#
# 无参数时：对当前目录(.)导出为 /sdcard/<目录名>-source.md。

set -euo pipefail

# ---------- 默认参数 ----------
INCLUDE_GIT=0
PLAIN_TEXT=0
MAX_BYTES=524288
SPECIFIED_OUT=""
PROJECT_DIR="."

# ---------- 帮助 ----------
usage() {
    grep '^# ' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

# ---------- 参数解析 ----------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -g|--include-git) INCLUDE_GIT=1 ;;
        -p|--plain-text)  PLAIN_TEXT=1 ;;
        -m|--max-bytes)   MAX_BYTES="$2"; shift ;;
        -o|--output)      SPECIFIED_OUT="$2"; shift ;;
        -h|--help)        usage ;;
        -*)               echo "未知选项: $1" >&2; usage ;;
        *)
            if [[ -z "${PROJECT_DIR_SET:-}" ]]; then
                PROJECT_DIR="$1"; PROJECT_DIR_SET=1
            elif [[ -z "$SPECIFIED_OUT" ]]; then
                SPECIFIED_OUT="$1"
            else
                echo "多余参数: $1" >&2; usage
            fi
            ;;
    esac
    shift
done

# ---------- 校验 ----------
if [[ ! -d "$PROJECT_DIR" ]]; then
    echo "项目目录不存在: $PROJECT_DIR" >&2
    exit 1
fi

# 绝对路径化，便于后续 relativeTo 计算
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
PROJ_NAME="$(basename "$PROJECT_DIR")"

# ---------- 输出文件名默认值：SD 卡根目录 ----------
if [[ -z "$SPECIFIED_OUT" ]]; then
    if [[ "$PLAIN_TEXT" -eq 1 ]]; then
        SPECIFIED_OUT="/sdcard/${PROJ_NAME}-source.txt"
    else
        SPECIFIED_OUT="/sdcard/${PROJ_NAME}-source.md"
    fi
fi

# 绝对路径化；若父目录不存在则回退到当前目录
_out_dir="$(cd "$(dirname "$SPECIFIED_OUT")" 2>/dev/null && pwd || echo "")"
if [[ -z "$_out_dir" || ! -d "$_out_dir" ]]; then
    echo "警告：输出目录 $(dirname "$SPECIFIED_OUT") 不存在，回退到当前目录" >&2
    _out_dir="$(pwd)"
fi
OUT_FILE="$_out_dir/$(basename "$SPECIFIED_OUT")"

# ---------- 排除规则 ----------
BINARY_EXT=(png jpg jpeg gif webp bmp ico so aar jar
            zip tar gz tgz wav mp3 mp4 mkv ttf otf
            woff woff2 db sqlite keystore jks bin apk dex)

# 构建二进制扩展名查找表（一次）
declare -A BIN_SET
for _e in "${BINARY_EXT[@]}"; do BIN_SET["$_e"]=1; done

# 纯内建判定（无外部进程）；大小上限交给 find 的 -size 处理
should_skip_file() {
    local f="$1" base name ext
    base="${f##*/}"
    name="${base,,}"
    [[ "$name" == "local.properties" ]] && return 0
    [[ "$name" == *.iml ]] && return 0
    ext="${base##*.}"
    ext="${ext,,}"
    [[ -n "${BIN_SET[$ext]:-}" ]] && return 0
    return 1
}

# ---------- 收集文件：单次 find 遍历（快） ----------
# 目录剪枝表达式
if [[ "$INCLUDE_GIT" -eq 1 ]]; then
    # 排除 build/captures/node_modules 及除 .git 外的所有点目录
    PRUNE=( -type d \( -name build -o -name captures -o -name node_modules -o \( -name '.*' ! -name '.git' \) \) -prune )
else
    # 排除 build/captures/node_modules 及所有点目录（含 .git）
    PRUNE=( -type d \( -name build -o -name captures -o -name node_modules -o -name '.*' \) -prune )
fi

# 文件侧：限制单文件大小（-size 由 find 完成），其余排除在 bash 端做（无 fork）
mapfile -d '' RAW < <(find "$PROJECT_DIR" "${PRUNE[@]}" -o \( -type f -size "-${MAX_BYTES}c" -print0 \))

SOURCES=()
for f in "${RAW[@]:-}"; do
    [[ -z "$f" ]] && continue
    [[ "$f" == "$OUT_FILE" ]] && continue
    should_skip_file "$f" && continue
    SOURCES+=("$f")
done

# 按相对路径排序（公共前缀一致，按绝对路径排序等价于按相对路径排序）
if [[ ${#SOURCES[@]} -gt 0 ]]; then
    mapfile -t SOURCES < <(printf '%s\n' "${SOURCES[@]}" | sort)
fi

TOTAL=${#SOURCES[@]}

# ---------- 目录树（由文件列表重建，避免二次遍历） ----------
print_tree() {
    declare -A printed
    local rel parts i accum indent depth
    for f in "${SOURCES[@]}"; do
        rel="${f#"$PROJECT_DIR"/}"
        IFS='/' read -ra parts <<< "$rel"
        accum=""
        depth=0
        for ((i = 0; i < ${#parts[@]} - 1; i++)); do
            accum+="${parts[i]}/"
            if [[ -z "${printed[$accum]:-}" ]]; then
                indent="$(printf '%*s' $((depth * 2)) '')"
                echo "${indent}${parts[i]}/"
                printed[$accum]=1
            fi
            depth=$((depth + 1))
        done
        indent="$(printf '%*s' $((depth * 2)) '')"
        echo "${indent}${parts[-1]}"
    done
}

# ---------- 语言映射 ----------
lang_of() {
    local name="$1" ext
    ext="${name##*.}"
    ext="${ext,,}"
    case "$ext" in
        kt|kts) echo "kotlin" ;;
        java) echo "java" ;;
        xml) echo "xml" ;;
        gradle) echo "gradle" ;;
        json) echo "json" ;;
        md) echo "markdown" ;;
        txt) echo "text" ;;
        yaml|yml) echo "yaml" ;;
        toml) echo "toml" ;;
        sha|sh|bash) echo "bash" ;;
        properties) echo "properties" ;;
        pro) echo "protobuf" ;;
        css|scss|sass) echo "css" ;;
        html|htm) echo "html" ;;
        js|mjs|cjs) echo "javascript" ;;
        ts) echo "typescript" ;;
        py) echo "python" ;;
        c|h) echo "c" ;;
        cpp|cc|cxx|hpp|hxx) echo "cpp" ;;
        rs) echo "rust" ;;
        go) echo "go" ;;
        swift) echo "swift" ;;
        *) echo "" ;;
    esac
}

# ---------- 安全读取（直接 cat 到输出，单次读取，对齐 Kotlin readText） ----------
safe_read() {
    local f="$1"
    if ! cat "$f" 2>/dev/null; then
        echo "<<无法读取（非文本或编码异常）>>"
    fi
}

# ---------- 生成文档 ----------
STAMP="$(date '+%Y-%m-%d %H:%M:%S')"

{
    if [[ "$PLAIN_TEXT" -eq 1 ]]; then
        echo "PROJECT SOURCE: $PROJ_NAME"
        echo
        echo "Generated: $STAMP"
        echo "Source files: $TOTAL"
        echo "Path: $PROJECT_DIR"
    else
        echo "# 项目源码导出：$PROJ_NAME"
        echo
        echo "> 生成时间：$STAMP"
        echo "> 源码文件数：$TOTAL"
        echo "> 项目路径：$PROJECT_DIR"
    fi
    echo
    if [[ "$PLAIN_TEXT" -eq 1 ]]; then
        echo "DIRECTORY TREE:"
    else
        echo "## 目录结构"
    fi
    echo '```'
    if [[ "$TOTAL" -gt 0 ]]; then
        print_tree
    fi
    echo '```'
    echo

    if [[ "$PLAIN_TEXT" -ne 1 ]]; then
        echo "## 源码"
    fi

    done=0
    for f in "${SOURCES[@]}"; do
        rel="${f#"$PROJECT_DIR"/}"
        base="${f##*/}"
        if [[ "$PLAIN_TEXT" -eq 1 ]]; then
            echo "===== FILE: $rel ====="
            safe_read "$f"
            echo
        else
            lang="$(lang_of "$base")"
            echo "### $rel"
            echo '```'"$lang"
            safe_read "$f"
            echo '```'
            echo
        fi
        done=$((done + 1))
    done
} > "$OUT_FILE"

echo "已导出：$OUT_FILE （源码文件数：$TOTAL）"
