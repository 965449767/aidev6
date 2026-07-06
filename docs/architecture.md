# Architecture Notes

## Repository Type

aidev3 is an Android application written in Kotlin with Jetpack Compose UI.

## Main Directories

- `app/src/main/java/com/aidev/three`: Android activities, shell UI, terminal pages, settings, tasks, server center, and app navigation.
- `app/src/main/java/com/aidev/three/opencode`: OpenCode HTTP/SSE client and native panel integration.
- `app/src/main/assets/proot-libs`: PRoot support libraries copied into app private storage at runtime.
- `app/src/main/jniLibs/arm64-v8a`: native PRoot binaries bundled into the APK.
- `app/src/main/res`: Android resources.
- `app/build.gradle.kts`: Android app build configuration.

## Current Entry Points

- Launcher activity: `ShellActivity`
- Main terminal page: `EmbeddedTerminalPage` inside `EmbeddedShellPages.kt`
- App navigation: `AppNav`
- Keep-alive service: `KeepAliveService`
- Settings: `SettingsActivity`

## Important Runtime Flow

On app launch, `ShellActivity` selects the embedded terminal page and dispatches `aidev-auto-bootstrap`.

The terminal session loads `.aidevrc`, which defines shell functions for:

- `ubuntu`
- `install-ubuntu`
- `aidev-auto-bootstrap`

These functions call `dev-env/bin/aidev-ubuntu-core` through `/system/bin/sh` because Android app private directories cannot be executed directly.

Ubuntu runs through bundled PRoot with `--link2symlink`.

## Current Architecture Notes

- `ShellActivity` (`ComponentActivity` + `setContent`) is the single launcher and terminal entry.
- The project uses Jetpack Compose for all UI; no XML layouts or legacy `MainActivity`.
- Ubuntu readiness is based on `home/ubuntu-rootfs/.aidev-rootfs-ready`.
- Do not treat `etc/os-release` alone as proof that Ubuntu is ready.
- Do not execute files directly from app private storage; use `/system/bin/sh <script>`.
- Inspect nearby code before moving terminal logic.
