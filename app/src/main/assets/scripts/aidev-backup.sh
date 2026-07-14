#!/bin/sh
# aidev-backup: 备份和恢复 Ubuntu rootfs + 开发环境
# 用法:
#   aidev-backup create [--quick|--all]
#   aidev-backup list
#   aidev-backup info <name>
#   aidev-backup restore <name>
#   aidev-backup delete <name>

set -e

BACKUP_DIR="${BACKUP_DIR:-/sdcard/AIDev/backups}"
LOCK_FILE="$BACKUP_DIR/.aidev-backup.lock"
SELF="$0"

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
die()  { log "错误: $*"; exit 1; }
warn() { echo "  ⚠ $*"; }

cleanup() {
    rm -f "$LOCK_FILE"
}

# ── 进程锁 ────────────────────────────────────────────────
acquire_lock() {
    mkdir -p "$(dirname "$LOCK_FILE")"
    if [ -f "$LOCK_FILE" ]; then
        local pid
        pid=$(cat "$LOCK_FILE" 2>/dev/null || echo "")
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            die "已有备份进程在运行 (pid $pid)。请等待完成或 kill 后重试。"
        fi
        log "检测到过期锁，清理..."
        rm -f "$LOCK_FILE"
    fi
    echo "$$" > "$LOCK_FILE"
    trap cleanup EXIT INT TERM
}

# ── 空间估算 ──────────────────────────────────────────────
estimate_needs() {
    local mode="$1"
    local total=0
    local rootfs_mb=0 tools_mb=0 proj_mb=0

    rootfs_mb=$(du -sm --exclude=/host-home --exclude=/sdcard --exclude=/proc \
        --exclude=/sys --exclude=/dev --exclude=/tmp --exclude=/var/cache/apt \
        / 2>/dev/null | awk '{print $1}')
    rootfs_mb=${rootfs_mb:-500}
    total=$((total + rootfs_mb))

    if [ "$mode" != "quick" ]; then
        if [ -d /host-home/android-sdk ]; then
            tools_mb=$((tools_mb + $(du -sm /host-home/android-sdk 2>/dev/null | awk '{print $1}')))
        fi
        if [ -d /host-home/gradle-cache ]; then
            tools_mb=$((tools_mb + $(du -sm /host-home/gradle-cache 2>/dev/null | awk '{print $1}')))
        fi
        if [ -d /host-home/dev-env ]; then
            tools_mb=$((tools_mb + $(du -sm /host-home/dev-env 2>/dev/null | awk '{print $1}')))
        fi
        total=$((total + tools_mb))
    fi

    if [ "$mode" = "all" ]; then
        proj_mb=$(du -sm /root /host-home/Projects /host-home/projects \
            2>/dev/null | awk '{print $1}' | sort -rn | head -1)
        proj_mb=${proj_mb:-0}
        total=$((total + proj_mb))
    fi

    echo "$total:$rootfs_mb:$tools_mb:$proj_mb"
}

check_space() {
    local target_dir="$1"
    local needed_mb="$2"
    mkdir -p "$target_dir"
    local avail_mb
    avail_mb=$(df -m "$target_dir" 2>/dev/null | awk 'NR==2{print $4}')
    avail_mb=${avail_mb:-0}
    local margin=$((needed_mb * 20 / 100 + 100))
    local required=$((needed_mb + margin))
    if [ "$avail_mb" -lt "$required" ]; then
        die "磁盘空间不足。需要约 ${required}MB (含 ${margin}MB 余量)，可用 ${avail_mb}MB。\n  请清理后重试，或使用 --quick 模式（仅备份 rootfs）。"
    fi
}

# ── 备份 rootfs ────────────────────────────────────────────
backup_rootfs() {
    local out_path="$1"
    log "正在备份 rootfs..."
    tar czf "$out_path" \
        --one-file-system \
        --exclude=/host-home \
        --exclude=/sdcard \
        --exclude=/storage \
        --exclude=/proc \
        --exclude=/sys \
        --exclude=/dev \
        --exclude=/tmp \
        --exclude=/var/cache/apt \
        / 2>/dev/null
    local rc=$?
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 1 ]; then
        rm -f "$out_path"
        die "rootfs 备份失败 (tar exit=$rc)"
    fi
    if [ ! -s "$out_path" ]; then
        rm -f "$out_path"
        die "rootfs 备份生成了空文件"
    fi
    log "  rootfs: $(du -sh "$out_path" | cut -f1)"
}

# ── 备份 tools (SDK/Gradle/dev-env) ──────────────────────
backup_tools() {
    local tools_dir="$1"
    mkdir -p "$tools_dir"
    local has_any=false

    for src_path in /host-home/android-sdk /host-home/gradle-cache /host-home/dev-env; do
        local name
        name=$(basename "$src_path")
        if [ -d "$src_path" ]; then
            has_any=true
            log "  备份 $name ($(du -sh "$src_path" 2>/dev/null | cut -f1))..."
            tar czf "$tools_dir/$name.tar.gz" -C /host-home "$name" 2>/dev/null || {
                warn "$name 备份失败，跳过"
                rm -f "$tools_dir/$name.tar.gz"
            }
        fi
    done

    if [ "$has_any" = false ]; then
        warn "未找到 tools 目录，跳过"
        return 1
    fi
}

# ── 备份项目 ───────────────────────────────────────────────
backup_projects() {
    local out_path="$1"
    local proj_dirs=""

    if [ -d /root ] && [ "$(ls -A /root 2>/dev/null)" ]; then
        proj_dirs="$proj_dirs /root"
    fi
    for d in /host-home/Projects /host-home/projects; do
        [ -d "$d" ] && [ "$(ls -A "$d" 2>/dev/null)" ] && proj_dirs="$proj_dirs $d"
    done

    if [ -z "$proj_dirs" ]; then
        warn "未发现项目目录，跳过"
        return 1
    fi
    local tar_args="$proj_dirs"
    tar czf "$out_path" $tar_args 2>/dev/null || {
        warn "项目备份失败，跳过"
        rm -f "$out_path"
        return 1
    }
    log "  项目: $(du -sh "$out_path" | cut -f1)"
}

# ── 恢复 rootfs ────────────────────────────────────────────
restore_rootfs() {
    local archive="$1"
    log "正在验证备份完整性..."
    tar tzf "$archive" >/dev/null 2>&1 || die "备份文件损坏: $archive"
    log "  ✓ 完整性检查通过"

    log "正在恢复 rootfs..."
    tar xzf "$archive" -C / --numeric-owner 2>/dev/null
    log "  ✓ rootfs 恢复完成"
}

# ── 恢复 tools ─────────────────────────────────────────────
restore_tools() {
    local tools_dir="$1"
    if [ ! -d "$tools_dir" ]; then
        warn "备份中不包含 tools 数据，跳过"
        return
    fi
    for tarball in "$tools_dir"/*.tar.gz; do
        [ -f "$tarball" ] || continue
        local name
        name=$(basename "$tarball" .tar.gz)
        log "  恢复 $name..."
        tar xzf "$tarball" -C /host-home 2>/dev/null || warn "$name 恢复失败"
    done
    log "  ✓ tools 恢复完成"
}

# ── 恢复项目 ───────────────────────────────────────────────
restore_projects() {
    local archive="$1"
    if [ ! -f "$archive" ]; then
        warn "备份中不包含项目数据，跳过"
        return
    fi
    log "正在恢复项目..."
    tar xzf "$archive" -C / 2>/dev/null || warn "部分项目恢复失败"
    log "  ✓ 项目恢复完成"
}

# ── 创建备份 ──────────────────────────────────────────────
cmd_create() {
    local mode="full"
    case "${1:-}" in
        --quick) mode="quick" ;;
        --all)   mode="all" ;;
        --*)     die "未知参数: $1" ;;
    esac

    acquire_lock

    local needs
    needs=$(estimate_needs "$mode")
    local needed_mb="${needs%%:*}"
    local rootfs_mb
    rootfs_mb=$(echo "$needs" | cut -d: -f2)
    local tools_mb
    tools_mb=$(echo "$needs" | cut -d: -f3)
    local proj_mb
    proj_mb=$(echo "$needs" | cut -d: -f4)

    echo ""
    echo "═══ aidev-backup create ═══"
    echo "  模式: $mode"
    echo "  备份目录: $BACKUP_DIR"
    echo "  预估大小: rootfs ${rootfs_mb}MB${tools_mb:+, tools ${tools_mb}MB}${proj_mb:+, 项目 ${proj_mb}MB}"
    echo ""

    check_space "$BACKUP_DIR" "$needed_mb"

    local ts
    ts=$(date '+%Y%m%d_%H%M%S')
    local name="ubuntu-$ts"
    local dir="$BACKUP_DIR/$name"
    mkdir -p "$dir"

    local rootfs_archive="$dir/rootfs.tar.gz"

    backup_rootfs "$rootfs_archive"

    local has_tools=false
    local has_projects=false

    if [ "$mode" != "quick" ]; then
        local tools_dir="$dir/tools"
        if backup_tools "$tools_dir"; then
            has_tools=true
        fi
    fi

    if [ "$mode" = "all" ]; then
        local proj_archive="$dir/projects.tar.gz"
        if backup_projects "$proj_archive"; then
            has_projects=true
        fi
    fi

    local total_size
    total_size=$(du -sm "$dir" 2>/dev/null | awk '{print $1}')
    local total_size_hr
    total_size_hr=$(du -sh "$dir" 2>/dev/null | cut -f1)

    cat > "$dir/meta.json" <<EOF
{
  "name": "$name",
  "created_at": "$(date -Iseconds)",
  "mode": "$mode",
  "rootfs": true,
  "tools": $has_tools,
  "projects": $has_projects,
  "total_size_mb": $total_size
}
EOF

    log ""
    log "═══ 备份完成 ═══"
    log "  名称: $name"
    log "  路径: $dir"
    log "  大小: $total_size_hr"
    log "  内容: rootfs${has_tools:+ + tools}${has_projects:+ + 项目}"
    log "  使用 aidev-backup list 查看所有备份"
    log "  使用 aidev-backup restore $name 还原"
}

# ── 列出备份 ──────────────────────────────────────────────
cmd_list() {
    mkdir -p "$BACKUP_DIR"
    local found=false
    echo ""
    echo "═══ 可用备份 (${BACKUP_DIR}) ═══"
    echo ""

    for dir in "$BACKUP_DIR"/ubuntu-*/; do
        [ -d "$dir" ] || continue
        local name
        name=$(basename "$dir")
        local meta="$dir/meta.json"
        local rootfs="$dir/rootfs.tar.gz"

        if [ ! -f "$rootfs" ]; then
            printf "  %-28s ❌ 缺少 rootfs.tar.gz\n" "$name"
            continue
        fi

        local size created mode extra
        if [ -f "$meta" ]; then
            size=$(grep -o '"total_size_mb": [0-9]*' "$meta" 2>/dev/null | awk '{print $2}')
            created=$(grep -o '"created_at": "[^"]*"' "$meta" 2>/dev/null | cut -d'"' -f4 | sed 's/T/ /' | cut -c1-19)
            mode=$(grep -o '"mode": "[^"]*"' "$meta" 2>/dev/null | cut -d'"' -f4)
            extra=""
            grep -q '"tools": true' "$meta" 2>/dev/null && extra="${extra}+tools "
            grep -q '"projects": true' "$meta" 2>/dev/null && extra="${extra}+projects "
            extra="${extra% }"
        else
            size="?"
            created=$(date -r "$rootfs" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "unknown")
            mode="?"
            extra=""
        fi

        local size_hr="${size:-?}MB"
        [ "$size" = "?" ] && size_hr="?"
        printf "  %-28s %-19s %-7s %s\n" "$name" "$created" "$size_hr" "${mode}${extra:+ (${extra})}"
        found=true
    done

    if [ "$found" = false ]; then
        echo "  (暂无备份)"
    fi
    echo ""
}

# ── 查看备份详情 ──────────────────────────────────────────
cmd_info() {
    [ -z "$1" ] && die "请指定备份名称。使用 aidev-backup list 查看可用备份。"
    local name="$1"
    local dir="$BACKUP_DIR/$name"
    [ -d "$dir" ] || die "备份 '$name' 不存在"

    local rootfs="$dir/rootfs.tar.gz"
    if [ ! -f "$rootfs" ]; then
        die "备份损坏: 缺少 rootfs.tar.gz"
    fi

    echo ""
    echo "═══ 备份详情: $name ═══"

    if [ -f "$dir/meta.json" ]; then
        grep -o '"[^"]*": [^,}]*' "$dir/meta.json" 2>/dev/null | \
            sed 's/"//g; s/_at/ 时间/; s/_size_mb/ 大小/; s/^  //' | \
            while IFS=': ' read -r key val; do
                case "$key" in
                    name)     printf "  名称:     %s\n" "$val" ;;
                    时间)     printf "  备份时间: %s\n" "$(echo "$val" | sed 's/T/ /' | cut -c1-19)" ;;
                    大小)     printf "  总大小:   %s MB\n" "$val" ;;
                    mode)     printf "  模式:     %s\n" "$val" ;;
                    rootfs)   [ "$val" = "true" ] && printf "  包含:     rootfs\n" ;;
                    tools)    [ "$val" = "true" ] && printf "  包含:     tools (SDK/Gradle/dev-env)\n" ;;
                    projects) [ "$val" = "true" ] && printf "  包含:     项目代码\n" ;;
                esac
            done
    else
        local size
        size=$(du -sh "$dir" 2>/dev/null | cut -f1)
        printf "  名称:     %s\n" "$name"
        printf "  大小:     %s\n" "$size"
    fi

    echo "  路径:     $dir"
    echo "  rootfs:   $(du -sh "$rootfs" | cut -f1)" 2>/dev/null || true
    if [ -d "$dir/tools" ]; then
        echo "  tools:    $(du -sh "$dir/tools" 2>/dev/null | cut -f1)"
    fi
    if [ -f "$dir/projects.tar.gz" ]; then
        echo "  项目:     $(du -sh "$dir/projects.tar.gz" 2>/dev/null | cut -f1)"
    fi
    echo ""
}

# ── 还原备份 ──────────────────────────────────────────────
cmd_restore() {
    [ -z "$1" ] && die "请指定备份名称。使用 aidev-backup list 查看可用备份。"
    local name="$1"
    local dir="$BACKUP_DIR/$name"
    [ -d "$dir" ] || die "备份 '$name' 不存在"

    local rootfs="$dir/rootfs.tar.gz"
    [ -f "$rootfs" ] || die "备份损坏: 缺少 rootfs.tar.gz"

    acquire_lock

    echo ""
    echo "═══ 还原确认: $name ═══"
    cmd_info "$1" | tail -n +3

    local total_mb=0
    if [ -f "$dir/meta.json" ]; then
        total_mb=$(grep -o '"total_size_mb": [0-9]*' "$dir/meta.json" 2>/dev/null | awk '{print $2}')
    fi
    [ "$total_mb" -eq 0 ] 2>/dev/null && total_mb=$(du -sm "$dir" 2>/dev/null | awk '{print $1}')
    [ -z "$total_mb" ] && total_mb=500

    echo -n "即将还原以上数据 (约 ${total_mb}MB)。当前 PRoot 将被覆盖。"
    [ -n "$(pgrep -f 'gradle.*daemon' 2>/dev/null)" ] && echo -n " [检测到 Gradle daemon]"
    echo ""
    echo -n "继续? [y/N] "
    read -r ans
    case "$ans" in
        [Yy]|[Yy][Ee][Ss]) ;;
        *) die "已取消" ;;
    esac

    check_space / "$total_mb"

    log "正在准备还原..."

    local marker="$dir/.restore-in-progress"
    touch "$marker"

    if [ -n "$(pgrep -f 'gradle.*daemon' 2>/dev/null)" ]; then
        log "正在停止 Gradle daemon..."
        pkill -f 'gradle.*daemon' 2>/dev/null || true
        sleep 1
    fi

    restore_rootfs "$rootfs"

    if [ -d "$dir/tools" ]; then
        restore_tools "$dir/tools"
    fi

    if [ -f "$dir/projects.tar.gz" ]; then
        restore_projects "$dir/projects.tar.gz"
    fi

    rm -f "$marker"

    echo ""
    log "═══ 还原完成 ═══"
    log "  rootfs 已覆盖"
    if [ -d "$dir/tools" ]; then
        log "  tools 已恢复"
    fi
    if [ -f "$dir/projects.tar.gz" ]; then
        log "  项目已恢复"
    fi
    log "  请重启终端或执行 aidev-auto-bootstrap 完成初始化"
    echo ""
}

# ── 删除备份 ──────────────────────────────────────────────
cmd_delete() {
    [ -z "$1" ] && die "请指定备份名称。使用 aidev-backup list 查看可用备份。"
    local name="$1"
    local dir="$BACKUP_DIR/$name"
    [ -d "$dir" ] || die "备份 '$name' 不存在"

    local size
    size=$(du -sh "$dir" 2>/dev/null | cut -f1)
    echo ""
    echo -n "确定要删除备份 '$name' (${size})? [y/N] "
    read -r ans
    case "$ans" in
        [Yy]|[Yy][Ee][Ss]) ;;
        *) die "已取消" ;;
    esac

    rm -rf "$dir"
    log "已删除: $name"
}

# ── 主入口 ────────────────────────────────────────────────
case "${1:-help}" in
    create)
        shift
        cmd_create "$@"
        ;;
    list)
        cmd_list
        ;;
    info)
        shift
        cmd_info "$@"
        ;;
    restore)
        shift
        cmd_restore "$@"
        ;;
    delete)
        shift
        cmd_delete "$@"
        ;;
    help|--help|-h)
        echo "用法: aidev-backup <子命令> [参数]"
        echo ""
        echo "子命令:"
        echo "  create [--quick|--all]    创建备份"
        echo "    --quick      仅备份 rootfs (跳过 tools)"
        echo "    --all        备份 rootfs + tools + 项目"
        echo "  list                      列出所有备份"
        echo "  info <name>              查看备份详情"
        echo "  restore <name>           还原备份"
        echo "  delete <name>            删除备份"
        echo ""
        echo "环境变量:"
        echo "  BACKUP_DIR    备份存储目录 (默认: /sdcard/AIDev/backups)"
        echo ""
        echo "示例:"
        echo "  aidev-backup create            # rootfs + tools"
        echo "  aidev-backup create --quick    # 仅 rootfs"
        echo "  aidev-backup create --all      # rootfs + tools + 项目"
        echo "  aidev-backup list              # 查看备份列表"
        echo "  aidev-backup restore ubuntu-20260705_123000"
        echo "  aidev-backup delete ubuntu-20260705_123000"
        ;;
    *)
        die "未知子命令: $1\n  用法: aidev-backup help"
        ;;
esac
