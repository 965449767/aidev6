# aidev3 — AGENTS.md

An on-device AI dev environment for Android (Xiaomi 14 Pro / HyperOS / Android 16 / arm64-v8a). Kotlin + Jetpack Compose, single-module, bundled Ubuntu via PRoot.

## Build

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew :app:assembleDebug --no-daemon
```

- Gradle 9.1.0 / AGP 9.0.1 / Kotlin 2.0.21 / Java 17
- Aliyun Maven mirrors (`settings.gradle.kts`), no VPN needed
- Modern AGP setup with built-in Kotlin and new DSL-compatible configuration
- Debug APK → `app/build/outputs/apk/debug/app-debug.apk`, auto-copied to `/sdcard/AIDev/`（`/sdcard` 即 `/storage/emulated/0`）
- Build counter auto-increments in `app/build-counter.properties`

## 交付规则（APK 安装）

- **禁止自动安装 APK**。构建完成后，只把产物复制到 `/storage/emulated/0/AIDev/`（等价于 `/sdcard/AIDev/`）由用户自行安装。
- 除非用户**主动要求**，否则一律不要执行安装（不要用 aidev-install / installapk / am start 安装器 / 任何方式触发安装）。
- 验证类操作也只把 APK 放到上述目录，安装动作交给用户。
- Optional `downloadCurlMusl` task downloads static curl-musl for arm64 into `assets/tools/`

## Testing (run in order)

```
./gradlew :app:compileDebugKotlin --no-daemon       # Kotlin compile check
./gradlew :app:testDebugUnitTest --no-daemon        # 158 unit tests (JUnit 4 + Mockito)；已知 3 个预存失败（BuildDiagnosticsTest/BuildPreflightSourceTest/BuildRequestTrackerTest），与改动无关
./gradlew :app:assembleDebug --no-daemon             # full build
```

- Shell tests（正确入口）：`bash app/src/test/sh/run.sh`  # 65 shell 测试（含 create-compose-project 等），全过
- 注：项目无 `testShellScripts` Gradle 任务，勿用 `./gradlew :app:testShellScripts`（会报 task not found）
- Harness/doc validation: `bash scripts/harness_check.sh`

## Architecture

- **Single launcher**: `ShellActivity` (`ComponentActivity` + `setContent`, no XML layouts)
- **Navigation**: Jetpack Navigation Compose via `AppNavHost`
- **Terminal**: Termux `terminal-view` library, `EmbeddedTerminalPage` session manager
- **Ubuntu**: bundled PRoot (`--link2symlink`), readiness = `home/ubuntu-rootfs/.aidev-rootfs-ready` (NOT `etc/os-release`)
- **OpenCode**: fixed port 4096, SSE event bus for session status monitoring
- **Elevation**: Shizuku for privileged operations
- **Runtime home**: `filesDir/home`
- **Entrypoints**: `ShellActivity.kt`, `EmbeddedShellPages.kt`, `AIDevApp.kt`, `KeepAliveService.kt`

## Key constraints

- App-private scripts: run via `/system/bin/sh <script>`, NEVER execute directly
- Android `tar` may fail on hardlinks → preserve symlink fallback
- NDK r27 (r28 broken, downgraded); only `arm64-v8a`
- `useLegacyPackaging = true` for jniLibs
- No Hilt, no Retrofit, no multi-module
- No camera/mic/contacts/SMS/location permissions
- Edge-to-edge temporarily opted out (`windowOptOutEdgeToEdgeEnforcement`)

## Session workflow

1. **Start**: `current-task.md` → `.harness/session-state.json` → `.harness/session-log.md` → `docs/verification.md` → `docs/decisions.md` → `docs/error-journal.md`
2. **Plan** (via `skills/plan/`) → **Code** → **Verify** → **Handoff**
3. **Handoff**: update `current-task.md`, `.harness/session-state.json`, `.harness/session-log.md`, record decisions/errors in `docs/`

## Useful docs

| File | Content |
|------|---------|
| `docs/architecture.md` | entrypoints, runtime flow, PRoot details |
| `docs/verification.md` | validation matrix per change type |
| `docs/coding-guidelines.md` | Android, terminal, and agent conventions |
| `docs/decisions.md` | stable architecture decisions log |
| `docs/error-journal.md` | repeated bugs and non-obvious failures |
| `docs/android-guidelines.md` | HyperOS rules, permission scope, validation |
| `docs/opencode-architecture.md` | OpenCode HTTP/SSE integration reference |
| `docs/silent-install.md` | Shizuku 静默安装（App UI + 终端）的方法、修复记录、架构 |

## Skills

- `skills/start/` — session recovery procedure
- `skills/plan/` — scoped engineering plan
- `skills/review/` — diff correctness check
- `skills/commit/` — commit preparation
- `skills/handoff/` — durable state preservation
- `skills/debug/` — Android crash diagnosis (logcat via Shizuku)
