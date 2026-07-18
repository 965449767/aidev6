# Test: create-compose-project
SCRIPT="$ASSETS_DIR/create-compose-project.sh"

# no args -> error about missing project name
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "错误" "no args should show error"
assert_contains "$output" "项目名称" "error should mention project name"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"
assert_contains "$output" "create-compose-project" "help should mention script name"
assert_contains "$output" "包名" "help should list package option"

# unknown option -> error
output=$(bash "$SCRIPT" --bogus 2>&1 || true)
assert_contains "$output" "错误" "unknown option should show error"

# ── 生成产物正确性：AGP 9 built-in Kotlin 撞名防护 ──
# 回归：脚手架曾漏配 android.builtInKotlin=false，导致 kotlin.android 与 AGP 9 内置
# Kotlin 撞名 "Cannot add extension with name 'kotlin'"，任何新项目都编译失败。
# 用独立临时 HOME 放置最小模板，输出到 /workspace 下的临时目录，实跑生成并校验产物。
GEN_TMP="$(mktemp -d)"
GEN_HOME="$GEN_TMP/home"
mkdir -p "$GEN_HOME/.gradle/template-wrapper/gradle/wrapper"
printf '#!/bin/sh\n' > "$GEN_HOME/.gradle/template-wrapper/gradlew"
: > "$GEN_HOME/.gradle/template-wrapper/gradle/wrapper/gradle-wrapper.jar"
GEN_PROJ="cpptest_$$"
GEN_DIR="/workspace/$GEN_PROJ"
rm -rf "$GEN_DIR"

HOME="$GEN_HOME" bash "$SCRIPT" -o /workspace "$GEN_PROJ" >/dev/null 2>&1 || true

if [ -f "$GEN_DIR/gradle.properties" ]; then
    props="$(cat "$GEN_DIR/gradle.properties")"
    assert_contains "$props" "android.builtInKotlin=false" "gradle.properties must disable AGP9 built-in Kotlin"
    assert_contains "$props" "android.newDsl=false" "gradle.properties must set newDsl=false"
    # 与三插件写法自洽：app 模块仍应用 kotlin.android
    appbuild="$(cat "$GEN_DIR/app/build.gradle.kts" 2>/dev/null)"
    assert_contains "$appbuild" "org.jetbrains.kotlin.android" "app build should apply kotlin.android"
    assert_contains "$appbuild" "org.jetbrains.kotlin.plugin.compose" "app build should apply compose plugin"
    # activity-compose 不在 Compose BOM 管理范围，必须带显式版本，否则解析失败
    assert_contains "$appbuild" "androidx.activity:activity-compose:1.9.3" "activity-compose must have explicit version (not BOM-managed)"
else
    fail "project generation did not produce gradle.properties at $GEN_DIR"
fi

rm -rf "$GEN_DIR" "$GEN_TMP"
