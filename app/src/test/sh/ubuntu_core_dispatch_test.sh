# Regression test: aidev-ubuntu-core dispatch must forward "$@" for commands
# that take positional arguments. Missing "${@}" silently drops all args and
# makes the target script see empty $1/$2 -> prints usage (see 2026-07-17 fix).
#
# aidev-ubuntu-core is generated from Kotlin (UbuntuBootstrapScripts.kt), not from
# assets/scripts, so we assert against the generator source to catch regressions
# in CI even before rootfs deployment.

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SELF_DIR/../../main/java/com/aidev/six/UbuntuBootstrapScripts.kt"

if [ ! -f "$SRC" ]; then
    fail "generator source not found: $SRC"
    return 0 2>/dev/null || true
fi

# Commands that REQUIRE positional args (must dispatch with "${@}").
ARG_CMDS="aidev-gen aidev-error-why aidev-index \
android-sh aidev-clean aidev-backup aidev-logcat aidev-anr aidev-tombstone \
aidev-crash-why aidev-dumpsys aidev-install aidev-deploy setup-dev-env \
aidev-apk-info aidev-verify-run"

for cmd in $ARG_CMDS; do
    # Extract the dispatch line for this command.
    line="$(grep -E "run_ubuntu_command \"/usr/local/bin/$cmd\"" "$SRC" | head -1)"
    if [ -z "$line" ]; then
        fail "no dispatch line for $cmd"
        continue
    fi
    # The dispatch must end with "${@}" before the closing ";;".
    # In the Kotlin source the dollar is escaped as ${'$'}@" so match that literal.
    case "$line" in
        *'"${'"'"'$'"'"'}@" ;;'*) ok ;;
        *) fail "dispatch for $cmd does not forward args: $line" ;;
    esac
done

# Sanity: commands that take NO args must NOT forward "${@}" (avoid noise).
NOARG_CMDS="check-dev-env repair-dev-env"
for cmd in $NOARG_CMDS; do
    line="$(grep -E "run_ubuntu_command \"/usr/local/bin/$cmd\"" "$SRC" | head -1)"
    if [ -z "$line" ]; then
        fail "no dispatch line for $cmd"
        continue
    fi
    case "$line" in
        *'"${'"'"'$'"'"'}@" ;;'*) fail "dispatch for $cmd unexpectedly forwards args: $line" ;;
        *) ok ;;
    esac
done
