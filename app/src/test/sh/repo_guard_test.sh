#!/bin/bash
# Guard: 下载来源决策必须统一走 aidev-repo decide（用户「离线优先」开关的唯一入口）。
# 若任一接入点绕过 aidev-repo 直接走网络，或删掉了 STRICT 禁止网络分支，本测试变红。
# 目的：杜绝 AI 随机改写把"离线优先"决策旁路掉。

PROJECT_ROOT="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" >/dev/null 2>&1 && git rev-parse --show-toplevel 2>/dev/null)"
[ -n "$PROJECT_ROOT" ] || PROJECT_ROOT="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../../.." && pwd)"

CCP="$PROJECT_ROOT/app/src/main/assets/scripts/create-compose-project.sh"
PRECACHE="$PROJECT_ROOT/app/src/main/assets/scripts/aidev-precache.sh"
BRIDGE="$PROJECT_ROOT/app/src/main/java/com/aidev/six/build/BuildEnvironmentSetup.kt"

# --- create-compose-project: Gradle 分发 ---
assert_contains "$(cat "$CCP" 2>/dev/null)" "aidev-repo decide android-gradle" \
    "create-compose-project 必须走 aidev-repo decide（Gradle 分发）"
assert_contains "$(cat "$CCP" 2>/dev/null)" "已禁止网络" \
    "create-compose-project 必须保留 STRICT 禁止网络分支"

# --- aidev-precache: Maven 基线 ---
assert_contains "$(cat "$PRECACHE" 2>/dev/null)" "aidev-repo decide android-maven-baseline" \
    "aidev-precache 必须走 aidev-repo decide（Maven 基线）"
assert_contains "$(cat "$PRECACHE" 2>/dev/null)" "已禁止网络" \
    "aidev-precache 必须保留 STRICT 禁止网络分支"

# --- BuildEnvironmentSetup.kt: JDK（重构后 JDK 决策由 BuildBridgeService 迁至此文件）---
assert_contains "$(cat "$BRIDGE" 2>/dev/null)" "aidev-repo decide android-jdk17" \
    "BuildEnvironmentSetup.kt 必须走 aidev-repo decide（JDK）"
assert_contains "$(cat "$BRIDGE" 2>/dev/null)" "已禁止网络" \
    "BuildEnvironmentSetup.kt 必须保留 STRICT 禁止网络分支"
