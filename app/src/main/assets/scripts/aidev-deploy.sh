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

set -e

emit() {
    # $1=installed $2=launched $3=activity(|null) $4=error(|"")
    EMITTED=true
    local installed="$1" launched="$2" activity="$3" err="${4:-}"
    local err_json
    if [ -z "$err" ]; then err_json="null"; else err_json="\"$err\""; fi
    printf '{"apk":"%s","pkg":"%s","installed":%s,"launched":%s,"activity":%s,"error":%s}\n' \
        "$APK" "$PKG" "$installed" "$launched" "$activity" "$err_json"
}

# 任何未预期退出都尽力回传结构化 JSON，避免 DeployBridge 解析为 null → 按钮显示「未知原因」
# 注意: emit 必须在 trap 注册前定义（dash 不提升函数作用域，否则 EXIT 陷阱触发时 emit 不可见 → RC=127）
EMITTED=false
trap 'if [ "$EMITTED" != true ]; then emit false false null "aidev-deploy 异常退出(rc=$?)"; fi' EXIT

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
            echo "  或: aidev-deploy <apk路径> --pkg <包名>   （首个位置参数视为 APK 路径）"
            echo ""
            echo "  --apk <路径>   APK 绝对路径（必填，或用首个位置参数）"
            echo "  --pkg <包名>   应用包名（必填，用于启动校验）"
            echo "  --launch       安装后启动（默认）"
            echo "  --no-launch    仅安装不启动"
            echo ""
            echo "标准出口: stdout 打印 JSON {apk,pkg,installed,launched,activity,error}"
            exit 0 ;;
        # 首个未匹配的位置参数视为 APK 路径（便于 aidev-deploy /path.apk 简写）；
        # 后续多余位置参数忽略。避免历史上位置参数被静默丢弃、误报 missing --apk。
        *) [ -z "$APK" ] && APK="$1"; shift ;;
    esac
done

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

# 2) 落地二次校验（可选软校验）：aidev-install 的退出码已是宿主侧 Shizuku 安装真相
#    （宿主 Shizuku 弹窗确认后真装成功，桥会回传 pm install 结果）。A 侧经桥跑
#    `pm list packages` 常因设备不可见而拿不到输出，故不再把它当致命判据，仅作
#    可选软校验；软校验失败仅降级为警告，不推翻 aidev-install 的真实成功。
VERIFIED=false
VERIFY_ERR=""
if command -v aidev-shizuku >/dev/null 2>&1; then
    for v in 1 2 3; do
        VERIFY=$(aidev-shizuku exec "pm list packages --user 0 | grep -i '^package:$PKG\$'" 2>/dev/null | clean_output)
        if echo "$VERIFY" | grep -qi "^package:$PKG\$"; then
            VERIFIED=true
            break
        fi
        VERIFY_ERR="$VERIFY"
        sleep 1
    done
fi
if [ "$VERIFIED" != true ]; then
    echo "⚠ 落地软校验未取到 pm list 输出（终端侧设备不可见，非安装失败）；以 aidev-install 结果为准。"
fi

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

# installed 真相 = aidev-install 的退出码（宿主 Shizuku 已真装）；软校验仅提示用
emit "${INSTALL_OK:-true}" "$LAUNCHED" "$ACTIVITY"
exit 0
