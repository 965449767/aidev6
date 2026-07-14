package com.aidev.six

// Extracted from UbuntuBootstrapScripts.kt — pure agent script definitions (no behavior).
// Split out to shrink the god-object; script content is byte-for-byte identical.

internal val UBUNTU_AGENT_HOST_SCRIPTS: Map<String, String> = mapOf(
        "aidev-current-project" to """#!/system/bin/sh
            pwd
            [ -d .git ] && git status --short --branch 2>/dev/null
            [ -f package.json ] && node -e "const p=require('./package.json'); console.log('package:',p.name||'-'); console.log('scripts:',Object.keys(p.scripts||{}).join(','))" 2>/dev/null
            [ -f pyproject.toml ] && echo "python: pyproject.toml"
            [ -f requirements.txt ] && echo "python: requirements.txt"
            [ -f build.gradle ] || [ -f build.gradle.kts ] && echo "gradle project"
            [ -f go.mod ] && echo "go module"
            [ -f Cargo.toml ] && echo "rust cargo"
        """.trimIndent(),
        "aidev-agent-context" to """#!/system/bin/sh
            echo "== AIDev Agent Context =="
            echo "time: $(date '+%F %T')"
            echo "pwd: $(pwd)"
            echo "version: ${'$'}{AIDEV_VERSION:-unknown}"
            echo
            echo "== project =="
            /system/bin/sh "${'$'}AIDEV_BIN/aidev-current-project"
            echo
            echo "== files =="
            find . -maxdepth 2 -type f 2>/dev/null | sed 's#^\./##' | head -120
            echo
            echo "== recent git =="
            git status --short --branch 2>/dev/null || true
            git diff --stat 2>/dev/null | head -80 || true
            echo
            echo "== tasks =="
            /system/bin/sh "${'$'}AIDEV_BIN/task-list" 2>/dev/null || true
        """.trimIndent(),
        "aidev-agent-context-file" to """#!/system/bin/sh
            out="aidev-agent-context.txt"
            /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-context" > "${'$'}{out}"
            echo >> "${'$'}{out}"
            echo "== recent errors ==" >> "${'$'}{out}"
            /system/bin/sh "${'$'}AIDEV_BIN/aidev-agent-summary" >> "${'$'}{out}" 2>/dev/null || true
            echo "已导出上下文文件：$(pwd)/${'$'}{out}"
        """.trimIndent(),
        "aidev-agent-summary" to """#!/system/bin/sh
            log="$(ls -t "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -1)"
            [ -n "${'$'}{log}" ] || { echo "暂无任务日志"; exit 0; }
            echo "== log =="
            echo "${'$'}{log}"
            echo
            echo "== recent commands =="
            grep -iE "command|running|exec|npm |python|gradle|go |cargo|git " "${'$'}{log}" 2>/dev/null | tail -20 || true
            echo
            echo "== recent errors =="
            grep -iE "error|failed|exception|traceback|cannot|not found|denied" "${'$'}{log}" 2>/dev/null | tail -40 || true
            echo
            echo "== possible modified files =="
            grep -iE "modified|created|updated|wrote|write|saved|changed" "${'$'}{log}" 2>/dev/null | tail -30 || true
        """.trimIndent(),
        "aidev-agent-log" to """#!/system/bin/sh
            ls -lt "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -20
        """.trimIndent(),
        "aidev-agent-tail" to """#!/system/bin/sh
            log="$(ls -t "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -1)"
            [ -n "${'$'}{log}" ] || { echo "暂无任务日志"; exit 1; }
            tail -f "${'$'}{log}"
        """.trimIndent(),
        "list-listen-ports" to """#!/system/bin/sh
            if command -v ss >/dev/null 2>&1; then
              ss -tlnp 2>/dev/null
            elif command -v netstat >/dev/null 2>&1; then
              netstat -tlnp 2>/dev/null
            else
              echo "=== /proc/net/tcp ==="
              cat /proc/net/tcp 2>/dev/null | head -40 || echo "(无法读取)"
              echo "=== /proc/net/tcp6 ==="
              cat /proc/net/tcp6 2>/dev/null | head -40 || true
            fi
        """.trimIndent(),
        "task-list" to """#!/system/bin/sh
            dir="${'$'}{AIDEV_HOME}/tasks"
            [ -d "${'$'}{dir}" ] || { echo "暂无任务"; exit 0; }
            for meta in "${'$'}{dir}"/*.meta; do
              [ -f "${'$'}{meta}" ] || continue
              id="${'$'}{meta##*/}"; id="${'$'}{id%.meta}"
              name=""; pid=""; cmd=""
              while IFS='=' read -r k v; do
                case "${'$'}{k}" in
                  name) name="${'$'}{v}" ;;
                  pid) pid="${'$'}{v}" ;;
                  cmd) cmd="${'$'}{v}" ;;
                esac
              done < "${'$'}{meta}"
              if [ -n "${'$'}{pid}" ]; then
                kill -0 "${'$'}{pid}" 2>/dev/null && status="运行中" || status="已结束"
              else
                status="未知"
              fi
              echo "${'$'}{id}  ${'$'}{status}  ${'$'}{name:-${'$'}{cmd:0:40}}"
            done
        """.trimIndent(),
        "task-run" to """#!/system/bin/sh
            [ $# -ge 2 ] || { echo "用法: task-run <name> <command>"; exit 1; }
            name="${'$'}{1}"; shift
            # strip / to prevent path traversal
            name="$(printf '%s' "${'$'}name" | tr -d '/')"
            dir="${'$'}{AIDEV_HOME}/tasks"
            mkdir -p "${'$'}{dir}"
            id="$(date +%s)_${'$'}{name}"
            log="${'$'}{dir}/${'$'}{id}.log"
            {
              echo "name=${'$'}{name}"
              echo "id=${'$'}{id}"
              echo "started=$(date '+%F %T')"
              echo "cmd=${'$'}{*}"
            } > "${'$'}{dir}/${'$'}{id}.meta"
            nohup /system/bin/sh -c "${'$'}{*}" > "${'$'}{log}" 2>&1 &
            pid=$!
            echo "pid=${'$'}{pid}" >> "${'$'}{dir}/${'$'}{id}.meta"
            echo "任务已启动: ${'$'}{name} (PID ${'$'}{pid})"
        """.trimIndent(),
)

internal val UBUNTU_AGENT_PRIVOT_SCRIPTS: Map<String, String> = mapOf(
        "aidev-current-project" to """#!/bin/sh
            pwd
            [ -d .git ] && git status --short --branch 2>/dev/null
            [ -f package.json ] && node -e "const p=require('./package.json'); console.log('package:',p.name||'-'); console.log('scripts:',Object.keys(p.scripts||{}).join(','))" 2>/dev/null
            [ -f pyproject.toml ] && echo "python: pyproject.toml"
            [ -f requirements.txt ] && echo "python: requirements.txt"
            [ -f build.gradle ] || [ -f build.gradle.kts ] && echo "gradle project"
            [ -f go.mod ] && echo "go module"
            [ -f Cargo.toml ] && echo "rust cargo"
        """.trimIndent(),
        "aidev-agent-context" to """#!/bin/sh
            echo "== AIDev Agent Context =="
            echo "time: $(date '+%F %T')"
            echo "pwd: $(pwd)"
            echo "version: ${'$'}{AIDEV_VERSION:-unknown}"
            echo
            echo "== project =="
            aidev-current-project
            echo
            echo "== files =="
            find . -maxdepth 2 -type f 2>/dev/null | sed 's#^\./##' | head -120
            echo
            echo "== recent git =="
            git status --short --branch 2>/dev/null || true
            git diff --stat 2>/dev/null | head -80 || true
            echo
            echo "== tasks =="
            task-list 2>/dev/null || true
        """.trimIndent(),
        "aidev-agent-context-file" to """#!/bin/sh
            out="aidev-agent-context.txt"
            aidev-agent-context > "${'$'}{out}"
            echo >> "${'$'}{out}"
            echo "== recent errors ==" >> "${'$'}{out}"
            aidev-agent-summary >> "${'$'}{out}" 2>/dev/null || true
            echo "已导出上下文文件：$(pwd)/${'$'}{out}"
        """.trimIndent(),
        "aidev-agent-summary" to """#!/bin/sh
            log="$(ls -t "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -1)"
            [ -n "${'$'}{log}" ] || { echo "暂无任务日志"; exit 0; }
            echo "== log =="
            echo "${'$'}{log}"
            echo
            echo "== recent commands =="
            grep -iE "command|running|exec|npm |python|gradle|go |cargo|git " "${'$'}{log}" 2>/dev/null | tail -20 || true
            echo
            echo "== recent errors =="
            grep -iE "error|failed|exception|traceback|cannot|not found|denied" "${'$'}{log}" 2>/dev/null | tail -40 || true
            echo
            echo "== possible modified files =="
            grep -iE "modified|created|updated|wrote|write|saved|changed" "${'$'}{log}" 2>/dev/null | tail -30 || true
        """.trimIndent(),
        "aidev-agent-log" to """#!/bin/sh
            ls -lt "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -20
        """.trimIndent(),
        "aidev-agent-tail" to """#!/bin/sh
            log="$(ls -t "${'$'}{AIDEV_HOME}/tasks"/*.log 2>/dev/null | head -1)"
            [ -n "${'$'}{log}" ] || { echo "暂无任务日志"; exit 1; }
            tail -f "${'$'}{log}"
        """.trimIndent(),
        "list-listen-ports" to """#!/bin/sh
            if command -v ss >/dev/null 2>&1; then
              ss -tlnp 2>/dev/null
            elif command -v netstat >/dev/null 2>&1; then
              netstat -tlnp 2>/dev/null
            else
              echo "=== /proc/net/tcp ==="
              cat /proc/net/tcp 2>/dev/null | head -40 || echo "(无法读取)"
              echo "=== /proc/net/tcp6 ==="
              cat /proc/net/tcp6 2>/dev/null | head -40 || true
            fi
        """.trimIndent(),
        "task-list" to """#!/bin/sh
            dir="${'$'}{AIDEV_HOME}/tasks"
            [ -d "${'$'}{dir}" ] || { echo "暂无任务"; exit 0; }
            for meta in "${'$'}{dir}"/*.meta; do
              [ -f "${'$'}{meta}" ] || continue
              id="${'$'}{meta##*/}"; id="${'$'}{id%.meta}"
              name=""; pid=""; cmd=""
              while IFS='=' read -r k v; do
                case "${'$'}{k}" in
                  name) name="${'$'}{v}" ;;
                  pid) pid="${'$'}{v}" ;;
                  cmd) cmd="${'$'}{v}" ;;
                esac
              done < "${'$'}{meta}"
              if [ -n "${'$'}{pid}" ]; then
                kill -0 "${'$'}{pid}" 2>/dev/null && status="运行中" || status="已结束"
              else
                status="未知"
              fi
              echo "${'$'}{id}  ${'$'}{status}  ${'$'}{name:-${'$'}{cmd:0:40}}"
            done
        """.trimIndent(),
        "task-run" to """#!/bin/sh
            [ $# -ge 2 ] || { echo "用法: task-run <name> <command>"; exit 1; }
            name="${'$'}{1}"; shift
            # strip / to prevent path traversal
            name="$(printf '%s' "${'$'}name" | tr -d '/')"
            dir="${'$'}{AIDEV_HOME}/tasks"
            mkdir -p "${'$'}{dir}"
            id="$(date +%s)_${'$'}{name}"
            log="${'$'}{dir}/${'$'}{id}.log"
            {
              echo "name=${'$'}{name}"
              echo "id=${'$'}{id}"
              echo "started=$(date '+%F %T')"
              echo "cmd=${'$'}{*}"
            } > "${'$'}{dir}/${'$'}{id}.meta"
            nohup /bin/sh -c "${'$'}{*}" > "${'$'}{log}" 2>&1 &
            pid=$!
            echo "pid=${'$'}{pid}" >> "${'$'}{dir}/${'$'}{id}.meta"
            echo "任务已启动: ${'$'}{name} (PID ${'$'}{pid})"
        """.trimIndent(),
        "aidev-opencode" to """#!/bin/sh
            PORT=4096
            START=$(date +%s)
            PORTFILE="${'$'}{AIDEV_HOME:-/host-home}/.aidev-opencode-port"

            # Parse --port flag
            while [ $# -gt 0 ]; do
                case "$1" in
                    --port) PORT="$2"; shift 2 ;;
                    *) break ;;
                esac
            done

            # Find opencode
            OC=$(command -v opencode 2>/dev/null)
            if [ -z "${'$'}OC" ]; then
                sysnotify --priority high "OpenCode" "找不到 opencode 命令，请先运行 opencode-check" >/dev/null 2>&1
                exit 127
            fi

            # 若默认 4096 已被占用（多半是 App 侧 headless serve 抢先拉起），
            # 则让 opencode 自选空闲端口，并把终端 TUI 的真实端口写进 PORTFILE，
            # 供 App 的「发送到 OpenCode」按钮精准注入，避免打到没有 TUI 的 serve 后端。
            AUTO=0
            if [ "${'$'}PORT" = "4096" ] && command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q "[.:]4096[[:space:]]"; then
                AUTO=1
            fi

            if [ "${'$'}AUTO" = "1" ]; then
                "${'$'}OC" --hostname 127.0.0.1 "$@" &
                OCD=$!
                sleep 2
                P=$(ss -ltnp 2>/dev/null | grep "pid=${'$'}OCD" | grep -oE ':[0-9]+[[:space:]]' | grep -oE '[0-9]+' | head -1)
                [ -n "${'$'}P" ] && echo "${'$'}P" > "${'$'}PORTFILE"
                wait "${'$'}OCD"
                EC=$?
            else
                echo "${'$'}PORT" > "${'$'}PORTFILE"
                "${'$'}OC" --port "${'$'}PORT" --hostname 127.0.0.1 "$@"
                EC=$?
            fi

            # Exit notification (debounce < 5s, clock-skew guard)
            END=$(date +%s); DUR=${'$'}((END - START))
            [ ${'$'}DUR -lt 0 ] && DUR=0
            [ ${'$'}DUR -lt 5 ] && exit ${'$'}EC

            # Format duration
            if [ ${'$'}DUR -lt 60 ]; then
                DUR_STR="${'$'}{DUR}s"
            elif [ ${'$'}DUR -lt 3600 ]; then
                DUR_STR="${'$'}((DUR / 60))min ${'$'}((DUR % 60))s"
            else
                DUR_STR="${'$'}((DUR / 3600))h ${'$'}((DUR % 3600 / 60))min"
            fi

            case ${'$'}EC in
                0)   MSG="任务完成（耗时 ${'$'}{DUR_STR}）" ;;
                130) MSG="已取消（Ctrl+C，耗时 ${'$'}{DUR_STR}）" ;;
                127) MSG="找不到 opencode 命令" ;;
                *)   MSG="异常退出（code ${'$'}EC，耗时 ${'$'}{DUR_STR}）" ;;
            esac

            sysnotify --priority high "OpenCode" "${'$'}MSG" >/dev/null 2>&1
            exit ${'$'}EC
        """.trimIndent(),
)
