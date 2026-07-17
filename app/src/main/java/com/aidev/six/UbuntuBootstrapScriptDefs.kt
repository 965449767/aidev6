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
)
