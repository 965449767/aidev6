# Test: aidev-build-request
SCRIPT="$ASSETS_DIR/aidev-build-request.sh"

# 当前契约：缺 --project 必须报错并提示用法（不再无参提交，见强制 --project 改动）
output=$(bash "$SCRIPT" 2>&1 || true)
assert_contains "$output" "未指定项目目录" "no project should error"
assert_contains "$output" "用法" "error should show usage"

# --no-install 但缺 --project 同样报错
output=$(bash "$SCRIPT" --no-install 2>&1 || true)
assert_contains "$output" "未指定项目目录" "--no-install without project should still error"

# --help flag
output=$(bash "$SCRIPT" --help 2>&1 || true)
assert_contains "$output" "用法" "--help should show usage"

# 带 --project 时项目目录不存在应报错而非阻塞
output=$(bash "$SCRIPT" --project /workspace/__nonexist_probe__ 2>&1 || true)
assert_contains "$output" "不存在" "nonexistent project dir should error"

# ═══════════════════════════════════════════════════════════
# validate_build_config 测试
# ═══════════════════════════════════════════════════════════

# 辅助：创建标准 mock 项目
make_mock_project() {
    local dir="$1"
    mkdir -p "$dir/gradle/wrapper" "$dir/app"
    cat > "$dir/gradle/wrapper/gradle-wrapper.properties" <<'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-bin.zip
EOF
    cat > "$dir/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
EOF
    cat > "$dir/app/build.gradle.kts" <<'EOF'
android {
    namespace = "com.example.test"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
}
EOF
    cat > "$dir/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        mavenCentral()
    }
}
rootProject.name = "test"
include(":app")
EOF
    cat > "$dir/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.nonTransitiveRClass=true
android.builtInKotlin=false
android.newDsl=false
android.aapt2DaemonMode=false
EOF
}

# Test 1: 标准项目通过校验
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "配置校验通过" "validate_pass: standard project passes"
rm -rf "$TMPDIR"

# Test 2: 缺少 gradle/wrapper/gradle-wrapper.properties
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
rm "$TMPDIR/gradle/wrapper/gradle-wrapper.properties"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "缺少 gradle/wrapper" "validate_fail_missing_gwp: missing gradle-wrapper.properties"
rm -rf "$TMPDIR"

# Test 3: 错误的 Gradle 版本
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
sed -i 's/gradle-9\.1\.0/gradle-8.0.0/' "$TMPDIR/gradle/wrapper/gradle-wrapper.properties"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "Gradle 版本被修改" "validate_fail_wrong_gradle: wrong Gradle version"
rm -rf "$TMPDIR"

# Test 4: 错误的 compileSdk
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
sed -i 's/compileSdk = 36/compileSdk = 34/' "$TMPDIR/app/build.gradle.kts"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "compileSdk = 36" "validate_fail_wrong_sdk: wrong compileSdk"
rm -rf "$TMPDIR"

# Test 5: 缺少 android.aapt2DaemonMode=false
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
grep -v 'android.aapt2DaemonMode=false' "$TMPDIR/gradle.properties" > "$TMPDIR/gradle.properties.tmp"
mv "$TMPDIR/gradle.properties.tmp" "$TMPDIR/gradle.properties"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "aapt2DaemonMode" "validate_fail_missing_aapt2: missing aapt2DaemonMode"
rm -rf "$TMPDIR"

# Test 6: 缺少 app/build.gradle.kts
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
rm "$TMPDIR/app/build.gradle.kts"
output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
assert_contains "$output" "缺少 app/build.gradle.kts" "validate_fail_missing_app_build: missing app/build.gradle.kts"
rm -rf "$TMPDIR"

# Test 7: .build-config.json 哈希校验（sha256sum 可用时）
if command -v sha256sum >/dev/null 2>&1; then
    TMPDIR=$(mktemp -d)
    make_mock_project "$TMPDIR"
    # 创建正确的 .build-config.json
    h1=$(sha256sum "$TMPDIR/gradle/wrapper/gradle-wrapper.properties" | cut -d' ' -f1)
    h2=$(sha256sum "$TMPDIR/build.gradle.kts" | cut -d' ' -f1)
    h3=$(sha256sum "$TMPDIR/app/build.gradle.kts" | cut -d' ' -f1)
    h4=$(sha256sum "$TMPDIR/settings.gradle.kts" | cut -d' ' -f1)
    h5=$(sha256sum "$TMPDIR/gradle.properties" | cut -d' ' -f1)
    cat > "$TMPDIR/.build-config.json" << EOF
{
  "version": 1,
  "gradle": "9.1.0",
  "hashes": {
    "gradle/wrapper/gradle-wrapper.properties": "$h1",
    "build.gradle.kts": "$h2",
    "app/build.gradle.kts": "$h3",
    "settings.gradle.kts": "$h4",
    "gradle.properties": "$h5"
  }
}
EOF
    output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
    assert_contains "$output" "配置校验通过" "validate_pass_hash_ok: correct hashes pass"
    # 篡改文件后哈希不匹配
    echo "// evil" >> "$TMPDIR/app/build.gradle.kts"
    output=$(bash "$SCRIPT" --validate-test "$TMPDIR" 2>&1 || true)
    assert_contains "$output" "SHA256" "validate_fail_hash: hash mismatch detected"
    rm -rf "$TMPDIR"
fi

# ═══════════════════════════════════════════════════════════
# auto_heal 自愈测试
# ═══════════════════════════════════════════════════════════

# Test 8: auto_heal 补回缺失的 android.aapt2DaemonMode=false
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
grep -v 'android.aapt2DaemonMode=false' "$TMPDIR/gradle.properties" > "$TMPDIR/gradle.properties.tmp"
mv "$TMPDIR/gradle.properties.tmp" "$TMPDIR/gradle.properties"
assert_contains "$(cat "$TMPDIR/gradle.properties")" "" "sanitize: property deleted"
bash "$SCRIPT" --heal-test "$TMPDIR" >/dev/null 2>&1 || true
assert_contains "$(cat "$TMPDIR/gradle.properties")" "android.aapt2DaemonMode=false" "heal_restores_aapt2: auto_heal restores deleted aapt2DaemonMode"
rm -rf "$TMPDIR"

# Test 9: auto_heal 恢复被篡改的 Gradle 版本
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
sed -i 's/gradle-9\.1\.0/gradle-8.0.0/' "$TMPDIR/gradle/wrapper/gradle-wrapper.properties"
bash "$SCRIPT" --heal-test "$TMPDIR" >/dev/null 2>&1 || true
assert_contains "$(cat "$TMPDIR/gradle/wrapper/gradle-wrapper.properties")" "gradle-9.1.0" "heal_restores_gradle: auto_heal restores correct Gradle version"
rm -rf "$TMPDIR"

# Test 10: auto_heal 恢复缺失的 android.useAndroidX=true 和 android.nonTransitiveRClass=true
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
grep -v 'android.useAndroidX=true\|android.nonTransitiveRClass=true' "$TMPDIR/gradle.properties" > "$TMPDIR/gradle.properties.tmp"
mv "$TMPDIR/gradle.properties.tmp" "$TMPDIR/gradle.properties"
bash "$SCRIPT" --heal-test "$TMPDIR" >/dev/null 2>&1 || true
props=$(cat "$TMPDIR/gradle.properties")
assert_contains "$props" "android.useAndroidX=true" "heal_restores_useAndroidX: auto_heal restores useAndroidX"
assert_contains "$props" "android.nonTransitiveRClass=true" "heal_restores_nonTransitiveRClass: auto_heal restores nonTransitiveRClass"
rm -rf "$TMPDIR"

# Test 11: auto_heal 所有关键属性缺失时也正常工作
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
echo "" > "$TMPDIR/gradle.properties"
bash "$SCRIPT" --heal-test "$TMPDIR" >/dev/null 2>&1 || true
props=$(cat "$TMPDIR/gradle.properties")
assert_contains "$props" "android.aapt2DaemonMode=false" "heal_empty_props: restores aapt2DaemonMode on empty file"
assert_contains "$props" "android.useAndroidX=true" "heal_empty_props: restores useAndroidX on empty file"
assert_contains "$props" "android.nonTransitiveRClass=true" "heal_empty_props: restores nonTransitiveRClass on empty file"
rm -rf "$TMPDIR"

# ═══════════════════════════════════════════════════════════
# lock_project 锁定测试
# ═══════════════════════════════════════════════════════════

# Test 12: --lock-project 生成 .build-config.json
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
assert_contains "$(cat "$TMPDIR/.build-config.json")" "gradle" "lock_creates_config: --lock-project generates .build-config.json"
assert_contains "$(cat "$TMPDIR/.build-config.json")" "hashes" "lock_contains_hashes: --lock-project includes hashes"
rm -rf "$TMPDIR"

# Test 13: --lock-project 设 chmod 444（文件不可写）
if [ "$(id -u)" != 0 ]; then
    TMPDIR=$(mktemp -d)
    make_mock_project "$TMPDIR"
    bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
    # 尝试写文件应被拒绝
    echo "test_write" >> "$TMPDIR/gradle.properties" 2>/dev/null && writable=1 || writable=0
    [ "$writable" -eq 0 ] && ok || fail "lock_chmod444: gradle.properties should be read-only after lock"
    rm -rf "$TMPDIR"
fi

# Test 14: --unlock-project 解锁文件
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
bash "$SCRIPT" --unlock-project "$TMPDIR" >/dev/null 2>&1 || true
# 解锁后应可写
echo "test_write" >> "$TMPDIR/gradle.properties" 2>/dev/null && ok || fail "unlock: gradle.properties should be writable after unlock"
[ ! -f "$TMPDIR/.build-config.json" ] && ok || fail "unlock: .build-config.json should be deleted"
rm -rf "$TMPDIR"

# Test 15: --unlock-project 后再次 --lock-project 重新锁定
TMPDIR=$(mktemp -d)
make_mock_project "$TMPDIR"
bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
bash "$SCRIPT" --unlock-project "$TMPDIR" >/dev/null 2>&1 || true
bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
assert_contains "$(cat "$TMPDIR/.build-config.json")" "gradle" "relock: re-lock after unlock generates .build-config.json"
rm -rf "$TMPDIR"

# ═══════════════════════════════════════════════════════════
# restore_from_manifest 恢复测试
# ═══════════════════════════════════════════════════════════

# Test 16: 锁定后 heal 仍能修复已被锁定的项目
if command -v sha256sum >/dev/null 2>&1; then
    TMPDIR=$(mktemp -d)
    make_mock_project "$TMPDIR"
    bash "$SCRIPT" --lock-project "$TMPDIR" >/dev/null 2>&1 || true
    # 篡改 gradle.properties（即使 chmod 444，root 仍可写）
    chmod +w "$TMPDIR/gradle.properties" 2>/dev/null || true
    echo "evil=true" > "$TMPDIR/gradle.properties"
    # auto_heal 应能修复
    bash "$SCRIPT" --heal-test "$TMPDIR" >/dev/null 2>&1 || true
    assert_contains "$(cat "$TMPDIR/gradle.properties")" "android.aapt2DaemonMode=false" "heal_locked: auto_heal fixes locked but tampered project"
    rm -rf "$TMPDIR"
fi
