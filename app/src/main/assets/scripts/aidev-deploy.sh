#!/bin/sh
# aidev-deploy: 部署黑盒（设备侧 / Shizuku 桥）
#
# 职责：把 APK 装到真机并（可选）启动，封装 Shizuku 安装、启动、权限等复杂性。
# 这是「改码 → 构建 → 运行 → 确认不崩」闭环的部署环节，原本混在构建黑盒成功分支里，
# 现抽成独立黑盒，与构建解耦：
#
#   标准入口: aidev-deploy --apk <绝对路径> --pkg <包名> [--launch | --no-launch]
#   标准出口: 结构化 JSON 打到 stdout
#     { "apk":"...","pkg":"...","installed":true/false,
#       "launched":true/false,"activity":"<组件>|null","error":"..."|null }
#
# 内部实现：委托 aidev-install（Shizuku 静默安装）+ aidev-shizuku exec（resolve-activity / am start），
# 与 App 侧 installAndLaunch 同款 Shizuku 桥，安装后用 pm list packages 二次校验落地。

DEPLOY_SCRIPT_VERSION="aidev-deploy 1.0.0 (clean_output fix; source aidev6 f011eb1)"

set -u

APK=""
PKG=""
LAUNCH=true

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --pkg) PKG="$2"; shift 2 ;;
        --launch) LAUNCH=true; shift ;;
        --no-launch) LAUNCH=false; shift ;;
        --version|-V) echo "$DEPLOY_SCRIPT_VERSION"; exit 0 ;;
        --help|-h)
            echo "用法: aidev-deploy --apk <绝对路径> --pkg <包名> [--launch|--no-launch]"
            echo ""
            echo "  --apk <路径>   APK 绝对路径（必填）"
            echo "  --pkg <包名>   应用包名（必填，用于启动校验）"
            echo "  --launch       安装后启动（默认）"
            echo "  --no-launch    仅安装不启动"
            echo ""
            echo "标准出口: stdout 打印 JSON {apk,pkg,installed,launched,activity,error}"
            exit 0 ;;
        *) shift ;;
    esac
done

emit() {
    # $1=installed $2=launched $3=activity(|null) $4=error(|"")
    local installed="$1" launched="$2" activity="$3" err="${4:-}"
    local err_json
    if [ -z "$err" ]; then err_json="null"; else err_json="\"$err\""; fi
    printf '{"apk":"%s","pkg":"%s","installed":%s,"launched":%s,"activity":%s,"error":%s}\n' \
        "$APK" "$PKG" "$installed" "$launched" "$activity" "$err_json"
}

# aidev-shizuku 客户端会把「Shizuku 请求已发送 / 命令: / 等待执行结果...」等提示行
# 打到 stdout，污染捕获的命令真实输出。这里剥离这些 preamble，只保留目标命令输出。
clean_output() {
    grep -vE '^Shizuku 请求已发送$|^命令: |^等待执行结果' | sed 's/\r$//'
}

if [ -z "$APK" ] || [ -z "$PKG" ]; then
    emit false false null "missing --apk or --pkg"
    exit 1
fi
if [ ! -f "$APK" ]; then
    emit false false null "apk not found: $APK"
    exit 1
fi
# 包名安全校验
echo "$PKG" | grep -qE '^[a-zA-Z0-9._]+$' || { emit false false null "invalid package name: $PKG"; exit 1; }

# 1) 安装（Shizuku 静默），最多重试 2 次以容忍瞬时 Shizuku 桥抖动
INSTALL_OK=false
INSTALL_ERR=""
for try in 1 2; do
    ERR_OUT=$(aidev-install "$APK" 2>&1)
    if [ $? -eq 0 ]; then
        INSTALL_OK=true
        break
    fi
    INSTALL_ERR=$(echo "$ERR_OUT" | grep -iE "错误|Failure|INSTALL_FAILED|error|Exception|非零" | head -2 | tr '\n' ' ')
    echo "安装尝试 $try 失败: $INSTALL_ERR"
    sleep 1
done
if [ "$INSTALL_OK" != true ]; then
    emit false false null "install failed: ${INSTALL_ERR:-aidev-install exit non-zero}"
    exit 1
fi

# 2) 落地二次校验（仅参考，不致命）：pm list packages 偶发空结果会误报，
#    真正安装成功以「能否启动」为准，故校验失败不判负，只记录提示。
VERIFIED=false
for v in 1 2; do
    VERIFY=$(aidev-shizuku exec "pm list packages --user 0 | grep -i '^package:$PKG\$'" 2>/dev/null | clean_output)
    if echo "$VERIFY" | grep -qi "^package:$PKG\$"; then
        VERIFIED=true
        break
    fi
    sleep 1
done
[ "$VERIFIED" = true ] || echo "提示：pm list packages 未确认包落地（可能瞬时延迟），以启动结果为准"

# 3) 启动（可选）
LAUNCHED=false
ACTIVITY=null
if [ "$LAUNCH" = true ]; then
    for l in 1 2 3; do
        COMP=$(aidev-shizuku exec "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER --user 0 '$PKG' 2>/dev/null | tail -1" 2>/dev/null | clean_output | grep "/" | tail -1 | tr -d '\r' | xargs)
        [ -n "$COMP" ] && ACTIVITY="$COMP"
        OUT=$(aidev-shizuku launch "$PKG" 2>/dev/null | clean_output)
        if ! echo "$OUT" | grep -qiE "Error:|No activities|Exception"; then
            LAUNCHED=true
            break
        fi
        echo "启动尝试 $l 未确认，1s 后重试..."
        sleep 1
    done
    if [ "$LAUNCHED" = false ]; then
        emit true false null "launch failed: $(echo "$OUT" | head -c 200)"
        exit 1
    fi
fi

emit true "$LAUNCHED" "$ACTIVITY"
exit 0
