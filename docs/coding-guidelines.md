# Coding Guidelines

## General Rules

- Keep changes small and scoped.
- Prefer existing project style over new style.
- Use Kotlin for new Android code.
- Treat Java as legacy maintenance only.
- Do not introduce new dependencies without a clear reason.
- Do not perform unrelated refactors.
- Update validation notes when behavior changes.

## Android Rules

See `docs/android-guidelines.md` for:
- Target device, architecture, and entry points
- HyperOS runtime rules and permissions
- Ubuntu/PRoot behavior rules
- Validation commands

## PRoot Rules

- Inside PRoot's glibc rootfs, `/system/bin/*` binaries (bionic-linked) **cannot execute**. Always sanitize `PATH` to remove `/system/*` entries inside rootfs `.bashrc`.
- Do NOT use `sed`, `tr`, `grep` in `.bashrc` — they may be from `/system/bin/` and fail. Use pure-bash while-read loops with `case` and parameter expansion instead.
- Function override pattern for redirecting `/system/bin/sh` → `/bin/sh`:
  ```bash
  __f() { while IFS= read __l; do case "$__l" in */system/bin/sh*) echo "${__l%%/system/bin/sh*}/bin/sh${__l#*/system/bin/sh}";; *) echo "$__l";; esac; done; }
  eval "$(declare -f | __f)"
  unset -f __f
  ```
- PATH cleanup pattern for stripping `/system/*`:
  ```bash
  _p="$PATH"; PATH=""
  while [ -n "$_p" ]; do _e="${_p%%:*}"; case "$_e" in /system/*) ;; *) PATH="${PATH:+$PATH:}$_e" ;; esac; [ "$_p" = "$_e" ] && _p="" || _p="${_p#*:}"; done
  unset _p _e
  ```
- Auto-bootstrap must have exactly **one** trigger: the shell entry script (`.aidev_shell_entry`). Never write `aidev-auto-bootstrap\r` directly to the terminal via Kotlin's `send()` or `write()` — this can inject commands into a running PRoot process.

## Kotlin Rules

- In `"""..."""` triple-quoted strings, every `$` sign that must appear literally in output must be written as `${'$'}`. This applies to shell code embedded in Kotlin strings.
  - `$__l` → `${'$'}__l`
  - `${PATH:-default}` → `${'$'}{PATH:-default}`
  - `$AIDEV_HOME` → `${'$'}AIDEV_HOME`

## Agent Notes

- Inspect relevant files before editing.
- Match naming, formatting, and error-handling style already present.
- Record non-obvious failures in `docs/error-journal.md`.
- Record stable architecture decisions in `docs/decisions.md`.
- Follow `docs/git-workflow.md` before any commit, tag, reset, cleanup, or backup operation.
