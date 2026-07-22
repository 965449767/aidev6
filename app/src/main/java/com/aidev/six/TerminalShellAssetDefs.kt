package com.aidev.six

// Extracted from TerminalShellAssets.kt — pure system-script definitions (no behavior).
// Split out to shrink the god-object; content is byte-for-byte identical.

internal fun systemScriptContent(name: String): String = when (name) {
            "sysnotify" -> """#!/bin/sh
                # AIDev system notification script
                # Usage: sysnotify [--priority min|low|default|high|max] [--ongoing] [--alert-once] <title> <message>
                PRIORITY=""
                ONGOING="false"
                ALERT_ONCE="false"
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --priority) PRIORITY="${'$'}2"; shift 2 ;;
                        --ongoing)  ONGOING="true"; shift ;;
                        --alert-once) ALERT_ONCE="true"; shift ;;
                        --help|-h)
                            echo "Usage: sysnotify [--priority min|low|default|high|max] [--ongoing] [--alert-once] <title> <message>"
                            exit 0 ;;
                        *) break ;;
                    esac
                done
                if [ ${'$'}# -lt 2 ]; then
                    echo "Usage: sysnotify [options] <title> <message>"
                    exit 1
                fi
                TITLE="${'$'}1"; shift
                MSG="${'$'}*"
                json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
                TITLE_ESC=$(printf '%s' "${'$'}TITLE" | json_escape)
                MSG_ESC=$(printf '%s' "${'$'}MSG" | json_escape)
                PRIORITY_ESC=$(printf '%s' "${'$'}PRIORITY" | json_escape)
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-notify"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"title\":\"${'$'}TITLE_ESC\",\"message\":\"${'$'}MSG_ESC\",\"priority\":\"${'$'}PRIORITY_ESC\",\"ongoing\":${'$'}ONGOING,\"alert_only_once\":${'$'}ALERT_ONCE}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo '{"status":"success","action":"notification sent"}'
                """.trimIndent()

            "screencap" -> """#!/bin/sh
                # AIDev screenshot script
                # Usage: screencap [output_path]
                OUT="${'$'}{1:-/sdcard/screenshot_$(date +%Y%m%d_%H%M%S).png}"
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                echo "{\"action\":\"screencap\",\"path\":\"${'$'}OUT\"}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"screencap requested\",\"path\":\"${'$'}OUT\"}"
                """.trimIndent()

            "volume" -> """#!/bin/sh
                # AIDev volume control script
                # Usage: volume [media|ring|alarm|call] [0-15|+|-]
                STREAM="${'$'}{1:-media}"
                VAL="${'$'}2"
                case "${'$'}STREAM" in
                    media)  CODE=3 ;;
                    ring)   CODE=2 ;;
                    alarm)  CODE=4 ;;
                    call)   CODE=0 ;;
                    *) echo '{"status":"error","error":"stream must be media|ring|alarm|call"}'; exit 1 ;;
                esac
                if [ -z "${'$'}VAL" ]; then
                    CUR=$(/system/bin/dumpsys audio 2>/dev/null | grep -i "${'$'}STREAM" | head -1)
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"volume\":\"${'$'}CUR\"}"
                elif [ "${'$'}VAL" = "+" ] || [ "${'$'}VAL" = "-" ]; then
                    KEY=$(if [ "${'$'}VAL" = "+" ]; then echo 24; else echo 25; fi)
                    /system/bin/input keyevent "${'$'}KEY"
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"action\":\"${'$'}VAL\"}"
                else
                    REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                    mkdir -p "${'$'}REQ_DIR"
                    echo "{\"action\":\"volume\",\"stream\":${'$'}CODE,\"volume\":${'$'}VAL}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo "{\"status\":\"success\",\"stream\":\"${'$'}STREAM\",\"volume\":${'$'}VAL}"
                fi
                """.trimIndent()

            "brightness" -> """#!/bin/sh
                # AIDev brightness control script
                # Usage: brightness [0-255|auto]
                VAL="${'$'}1"
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                if [ -z "${'$'}VAL" ]; then
                    CUR=$(/system/bin/settings get system screen_brightness 2>/dev/null || echo "unknown")
                    echo "{\"status\":\"success\",\"brightness\":${'$'}CUR}"
                elif [ "${'$'}VAL" = "auto" ]; then
                    echo "{\"action\":\"brightness\",\"auto\":true}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo '{"status":"success","mode":"auto"}'
                else
                    echo "{\"action\":\"brightness\",\"brightness\":${'$'}VAL}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                    echo "{\"status\":\"success\",\"brightness\":${'$'}VAL}"
                fi
                """.trimIndent()

            "sysclip" -> """#!/bin/sh
                # AIDev clipboard script
                # Usage: sysclip set <text>
                #        sysclip get  (仅 API 31+/前台 Activity 可用，当前环境不支持读取)
                CMD="${'$'}{1:-}"
                if [ "${'$'}CMD" = "get" ]; then
                    echo '{"status":"error","error":"clipboard read not supported on this device (Android 安全限制)"}'
                    exit 1
                fi
                shift 2>/dev/null
                TEXT="${'$'}*"
                json_escape() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
                REQ_DIR="${'$'}{AIDEV_HOME}/.aidev-cmd"
                mkdir -p "${'$'}REQ_DIR"
                ESCAPED=$(printf '%s' "${'$'}TEXT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' 2>/dev/null)
                if [ -z "${'$'}ESCAPED" ]; then
                    ESCAPED=$(printf '%s' "${'$'}TEXT" | json_escape)
                    ESCAPED="\"${'$'}ESCAPED\""
                fi
                echo "{\"action\":\"clipboard\",\"text\":${'$'}ESCAPED}" > "${'$'}REQ_DIR/req-$(date +%s%N).json"
                echo "{\"status\":\"success\",\"action\":\"clipboard set\"}"
                """.trimIndent()

            "aidev-proxy" -> """#!/bin/sh
                # AIDev proxy manager (tinyproxy)
                # Usage: aidev-proxy [start|stop|restart|status]
                CONF="${'$'}{AIDEV_HOME}/tinyproxy.conf"
                PIDFILE="/tmp/tinyproxy.pid"
                mkdir -p "${'$'}{AIDEV_HOME}/proxy-cache"
                case "${'$'}1" in
                    start)
                        if [ -f "${'$'}PIDFILE" ] && kill -0 $(cat "${'$'}PIDFILE") 2>/dev/null; then
                            echo "proxy already running (pid $(cat ${'$'}PIDFILE))"
                            exit 0
                        fi
                        cat > "${'$'}CONF" << EOF
Port 18080
Listen 127.0.0.1
Timeout 30
Syslog Off
LogLevel Warning
PidFile ${'$'}PIDFILE
XTinyProxy Off
CacheDir ${'$'}{AIDEV_HOME}/proxy-cache
CacheSize 500
CacheMaxExpire 1440
EOF
                        tinyproxy -c "${'$'}CONF" && echo "proxy started (port 18080)" && \
                        echo "export GRADLE_OPTS=\"${'$'}GRADLE_OPTS -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080\"" || \
                        echo "proxy start failed" ;;
                    stop)
                        [ -f "${'$'}PIDFILE" ] && kill $(cat "${'$'}PIDFILE") 2>/dev/null && rm -f "${'$'}PIDFILE" && echo "proxy stopped" || echo "proxy not running" ;;
                    restart) "${'$'}0" stop && "${'$'}0" start ;;
                    status)
                        if [ -f "${'$'}PIDFILE" ] && kill -0 $(cat "${'$'}PIDFILE") 2>/dev/null; then
                            echo "proxy running (pid $(cat ${'$'}PIDFILE), port 18080)"
                            echo "cache: ${'$'}{AIDEV_HOME}/proxy-cache"
                            echo "usage: GRADLE_OPTS with proxy settings"
                        else
                            echo "proxy not running"
                        fi ;;
                    *) echo "Usage: aidev-proxy [start|stop|restart|status]" ;;
                esac
                """.trimIndent()
            else -> "#!/bin/sh\necho 'Unknown command: $name'\n"
}
