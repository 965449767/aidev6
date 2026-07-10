# Error Journal

Use this file to record repeated failures, non-obvious bugs, and lessons learned.

## 2026-06-14 - Shell functions lost after `exec sh -i`

### Symptom

`ubuntu` returned `inaccessible or not found`.

### Root Cause

The shell loaded functions before `exec sh -i`. The exec replaced the process and lost previously sourced functions.

### Fix

Set `ENV` to `.aidevrc` so the interactive shell loads the functions itself.

### Prevention

Do not source shell functions before replacing the shell process.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Permission denied for app-private scripts

### Symptom

`ubuntu` existed in `dev-env/bin` but direct execution returned `Permission denied`.

### Root Cause

Android app private directories do not allow direct script execution.

### Fix

Define shell functions that call scripts through `/system/bin/sh`.

### Prevention

Never depend on executable bits for scripts under app private storage.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Ubuntu Base hardlink extraction failure

### Symptom

`tar` failed while linking files such as `usr/bin/perl5.38.2` and `usr/bin/perl`.

### Root Cause

Android `tar` and app private storage do not reliably support hardlink creation.

### Fix

Allow extraction to continue when core rootfs files exist, then replace known hardlinks with symlinks.

### Prevention

Preserve PRoot `--link2symlink` and rootfs symlink fallback logic.

### Related Files

- `app/src/main/java/com/aidev/three/EmbeddedShellPages.kt`

## 2026-06-14 - Multiple terminal entries caused inconsistent behavior

### Symptom

Different pages could enter different terminal implementations.

### Root Cause

The project retained both old `MainActivity` terminal navigation and the newer embedded terminal.

### Fix

Route terminal navigation to `ShellActivity` and unregister `MainActivity` from the manifest.

### Prevention

Use `AppNav.openTerminal` or `ShellHost.openTerminal` for terminal navigation.

### Related Files

- `app/src/main/java/com/aidev/three/AppNav.kt`
- `app/src/main/AndroidManifest.xml`

## 2026-06-17 - Corrupted file content caused conflicting imports

### Symptom

Compilation failed with hundreds of `Conflicting import` and `imports are only allowed in the beginning of file` errors, with absurd line numbers (e.g., 13001, 3543).

### Root Cause

`BackupRestorePage.kt` and the previous `MenuBottomSheet.kt` had their content repeatedly appended, creating malformed files with multiple `package` and `import` blocks. This likely happened when a prior write operation appended instead of overwriting.

### Fix

Deleted the corrupted `BackupRestorePage.kt` (it was unreferenced and untracked in Git). Overwrote `MenuBottomSheet.kt` with clean content.

### Prevention

Always verify file contents after write operations, especially when reusing file paths from previous sessions. Check file size and first few lines if compilation errors mention impossible line numbers.

### Related Files

- `app/src/main/java/com/aidev/three/BackupRestorePage.kt` (deleted)
- `app/src/main/java/com/aidev/three/MenuBottomSheet.kt`

## 2026-07-10 — `/system/bin/` binaries fail inside PRoot with "required file not found"

### Symptom

Commands like `ls`, `mkdir`, `tr`, `sed`, `grep` return `/system/bin/ls: cannot execute: required file not found` inside the PRoot environment.

### Root Cause

PRoot mounts the host `/system` partition into the glibc-based Ubuntu rootfs. Android's bionic-linked binaries at `/system/bin/*` cannot run under glibc. When `PATH` includes `/system/bin/`, the shell finds these incompatible binaries before the rootfs's own `coreutils` versions.

### Fix

Two changes:
1. In `.bashrc` (inside rootfs), strip `/system/*` entries from `PATH` on boot using a pure-bash while-read loop.
2. Install `coreutils` in the rootfs during bootstrap if `/usr/bin/ls` is missing.

### Prevention

Always sanitize `PATH` when entering PRoot. Use pure-bash string manipulation (`while IFS= read`, `case`, parameter expansion) instead of `sed`/`tr`/`grep` in `.bashrc`, because those tools may be from `/system/bin/` and fail.

### Pure-bash PATH cleanup pattern

```bash
_p="$PATH"; PATH=""
while [ -n "$_p" ]; do _e="${_p%%:*}"; case "$_e" in /system/*) ;; *) PATH="${PATH:+$PATH:}$_e" ;; esac; [ "$_p" = "$_e" ] && _p="" || _p="${_p#*:}"; done
unset _p _e
```

### Function override pattern (`/system/bin/sh` → `/bin/sh`)

```bash
__f() { while IFS= read __l; do case "$__l" in */system/bin/sh*) echo "${__l%%/system/bin/sh*}/bin/sh${__l#*/system/bin/sh}";; *) echo "$__l";; esac; done; }
eval "$(declare -f | __f)"
unset -f __f
```

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`

## 2026-07-10 — Duplicate auto-bootstrap triggers cause terminal noise inside PRoot

### Symptom

On first app launch, terminal shows garbled overlapping text, error `cannot open aidev-ubuntu-core: No such file`, and automatic `compiler` command execution after exiting PRoot.

### Root Cause

Three independent paths triggered `aidev-auto-bootstrap` at startup:
1. Shell entry script (`.aidev_shell_entry`) — correct, runs before interactive shell
2. `maybeAutoBootstrapUbuntu()` in `SessionManager.kt` — directly writes to terminal via `currentTerminalSession?.write()`
3. `TerminalCommandBus.post("aidev-auto-bootstrap")` in `ShellActivity.kt` — consumed by `consumePendingCommand()` which also writes to terminal

When path 1 already entered PRoot, paths 2 and 3 wrote raw commands into the PRoot shell. Inside PRoot, `$AIDEV_BIN` pointed to the host path (not `/host-home/`), causing "No such file".

### Fix

Removed `maybeAutoBootstrapUbuntu()` (entire function), its three call sites in `EmbeddedShellPages.kt`, the `autoBootstrapDispatched` flag, and `TerminalCommandBus.post("aidev-auto-bootstrap")` in `ShellActivity.kt`. Shell entry script is now the sole auto-bootstrap path.

### Prevention

- Only one mechanism should manage startup auto-enter.
- `write()` to the terminal session is dangerous — it bypasses the shell entry script and can write into a running PRoot process.
- Shell entry script runs inside the session process and has access to correct paths.

### Race condition analysis (no fix needed)

`TerminalShellAssets.ensure()` runs on `Dispatchers.IO` inside `ensureSession()`. `doCreateSession()` runs **after** `ensure()` completes (on `withContext(Dispatchers.Main)`). So `aidev-ubuntu-core` always exists when the shell entry script checks for it. No actual race exists.

### Related Files

- `app/src/main/java/com/aidev/six/ShellActivity.kt`
- `app/src/main/java/com/aidev/six/EmbeddedShellPages.kt`
- `app/src/main/java/com/aidev/six/terminal/SessionManager.kt`
- `app/src/main/java/com/aidev/six/TerminalShellAssets.kt`
- `app/src/main/java/com/aidev/six/TerminalCommandBus.kt`

## 2026-07-10 — Kotlin `${'$'}` escaping for shell code in triple-quoted strings

### Symptom

Kotlin compiler errors like `Unresolved reference '__l'` on lines containing shell variable references like `$__l`.

### Root Cause

In Kotlin `"""..."""` strings, `$` followed by an identifier or `${}` starts a template expression. Shell code like `"$__l"` causes Kotlin to look for a Kotlin variable named `__l`.

### Fix

Replace each `$` that must appear literally in the output with `${'$'}`. Examples:
- `$__l` → `${'$'}__l`
- `${__l%%pattern*}` → `${'$'}{__l%%pattern*}`
- `$PATH` → `${'$'}PATH`

### Prevention

When writing shell code inside Kotlin `"""..."""` strings, always use `${'$'}` for every `$` sign. This applies to both simple variables (`$var`) and complex expressions (`${var:-default}`).

### Related Files

- `app/src/main/java/com/aidev/six/UbuntuBootstrapScripts.kt`

## Deferred — `LinearProgressIndicator(float)` deprecated

### Symptom

Compile warning at `BackupRestoreDialog.kt:90` and `SFtpPanel.kt:105`:
`'fun LinearProgressIndicator(progress: Float, ...)' is deprecated. Use the overload that takes progress as a lambda.`

### Fix

`SFtpPanel.kt:105` fixed during Phase 3 SFTP work. `BackupRestoreDialog.kt:90` still uses the float overload.

### Deferred

Not a blocker — warning only. Fix when touching the file next.

## Deferred — Compose UI tests require Android runtime

### Symptom

`createComposeRule()` from `androidx.compose.ui:ui-test-junit4` throws `NullPointerException` in JVM unit tests (needs `ComposeUiTest` Android runtime). Four new test files (`SftpConnectionTest`, `TransferTaskTest`, `AuthTypeTest`, `DialogTypeTest`) had to fall back to pure JUnit data class tests.

### Root Cause

Compose UI tests need either:
- `androidTest` source set (device/emulator instrumentation tests), or
- Robolectric for JVM unit tests (not yet configured)

### Related Files

- `app/src/test/java/com/aidev/three/SftpConnectionTest.kt`
- `app/src/test/java/com/aidev/three/TransferTaskTest.kt`
- `app/src/test/java/com/aidev/three/AuthTypeTest.kt`
- `app/src/test/java/com/aidev/three/navigation/DialogTypeTest.kt`

## Deferred — Minor warnings (no functional impact)

1. **`GitStateTest.kt:27,33`** — `@OptIn(ExperimentalCoroutinesApi::class)` missing
2. **`DialogTypeTest.kt:46`** — `is DialogType` check always true (warning only)
3. **`AndroidManifest.xml:32`** — `android:extractNativeLibs` should move to build config per AGP 9
