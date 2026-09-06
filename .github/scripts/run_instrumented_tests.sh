#!/usr/bin/env bash
# Runs as a real script file (not inline YAML) deliberately: reactivecircus/android-emulator-runner's
# `script:` input executes each line of a multi-line block as its OWN separate `sh -c` invocation
# rather than one continuous shell, which silently breaks anything relying on shared state between
# lines (background-job PIDs, variables, `set -e` carrying through). A checked-in script file gets
# normal, single-process bash semantics.
#
# Usage: run_instrumented_tests.sh [debug|release]
#
# The release run is not a duplicate of the debug one. It installs the R8-minified APK and asks the
# two questions that only a runtime check on the shipped artifact can answer: does
# AccessibilityManagerService still resolve the full event-type mask for the service, and does the
# TYPE_ACCESSIBILITY_OVERLAY window still draw? Both are the kind of thing minification breaks
# without an error - which is this project's established failure shape.
set -uo pipefail # deliberately not -e: we want to capture gradle's exit code and still print logcat after a failure

VARIANT="${1:-debug}"
LOGCAT_FILE="/tmp/full-logcat-${VARIANT}.txt"

case "$VARIANT" in
    debug)
        GRADLE_ARGS=(connectedDebugAndroidTest)
        ;;
    release)
        # testBuildType has to be flipped for the release androidTest variant to exist at all, and
        # an unsigned release APK cannot be installed on the emulator - hence the debug-key opt-in.
        # See the comments on both properties in app/build.gradle.kts.
        GRADLE_ARGS=(
            connectedReleaseAndroidTest
            -Pmindfulscroll.testBuildType=release
            -Pmindfulscroll.signReleaseWithDebugKey=true
        )
        ;;
    *)
        echo "::error::unknown variant '$VARIANT' (expected debug or release)"
        exit 2
        ;;
esac

adb logcat -c
adb logcat -v time > "$LOGCAT_FILE" &
LOGCAT_PID=$!

echo "=== Running: ./gradlew ${GRADLE_ARGS[*]} ==="
./gradlew "${GRADLE_ARGS[@]}"
RESULT=$?

sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true

echo "=== Full logcat line count: $(wc -l < "$LOGCAT_FILE") (uploaded in full as the 'full-logcat-${VARIANT}' artifact) ==="

# Print EVERY line (no keyword filtering - a curated grep has already missed the cause twice)
# from the moment the harness writes its settings through to well past the 30s connect timeout,
# so nothing in that window can be missed regardless of terminology.
MARKER_LINE=$(grep -n "After writing settings" "$LOGCAT_FILE" | head -1 | cut -d: -f1)
if [ -n "${MARKER_LINE:-}" ]; then
    echo "=== Complete unfiltered logcat from the settings write (line $MARKER_LINE) through +2000 lines ==="
    tail -n "+${MARKER_LINE}" "$LOGCAT_FILE" | head -n 2000
else
    echo "=== Could not find the settings-write marker line - dumping last 2000 lines of logcat instead ==="
    tail -n 2000 "$LOGCAT_FILE"
fi

# The overlay evidence is deliberately pulled out separately: it is a handful of lines in a
# 100k-line log, and it is the whole answer to "did the pause screen actually appear?".
echo "=== Overlay render evidence (OverlayController / OverlayRenderTest) ==="
grep -E "Overlay (rendered|NOT rendered|render watcher failed)|OverlayRenderTest" "$LOGCAT_FILE" || echo "(no overlay render lines found)"

exit "$RESULT"
