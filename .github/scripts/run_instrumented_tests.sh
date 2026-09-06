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

# Generous next to the ~4 minutes a healthy run takes, but far below the job timeout, so a hang
# fails as a hang instead of as an unexplained cancellation.
GRADLE_TIMEOUT=20m

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

# Wrapped in `timeout` because connectedAndroidTest does NOT fail fast when the app process dies
# on startup: the instrumentation never reports back and gradle waits indefinitely. That once
# burned the job's entire 45-minute budget to deliver a crash that had happened in the first
# 30 seconds. A bounded wait turns that into a fast, legible failure with the logcat still
# attached below.
echo "=== Running: ./gradlew ${GRADLE_ARGS[*]} (bounded at ${GRADLE_TIMEOUT}) ==="
timeout --signal=TERM "$GRADLE_TIMEOUT" ./gradlew "${GRADLE_ARGS[@]}"
RESULT=$?
if [ "$RESULT" -eq 124 ]; then
    echo "::error::gradle exceeded ${GRADLE_TIMEOUT} and was killed. The instrumentation almost"
    echo "::error::certainly never reported back - check the crash section below before anything else."
fi

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
    # No marker means the suite never got as far as enabling the service - almost always a build
    # failure (gradle's own error is printed above, and is the thing worth reading). Dumping the
    # usual 2000 lines here buries that error under unrelated system-app chatter from the
    # emulator's boot, so this case gets a much smaller window.
    echo "=== The suite never reached the settings write, so gradle most likely failed before"
    echo "=== instrumentation started - read the gradle error above. Last 300 logcat lines follow"
    echo "=== only in case the app crashed on launch."
    tail -n 300 "$LOGCAT_FILE"
fi

# Asked first and separately, because "our process died" invalidates every other signal below
# and is otherwise a handful of lines buried in tens of thousands. A crash inside the app or the
# test runner - a stripped class, a failed Hilt injection - reads as an inexplicable hang at the
# gradle level, which is exactly how the release variant failed the first time it ran.
echo "=== Did the app or test process crash? (FATAL / NoClassDefFoundError / ClassNotFound) ==="
grep -nE "FATAL EXCEPTION|NoClassDefFoundError|ClassNotFoundException|Process: com\.mindfulscroll" "$LOGCAT_FILE" \
    | head -n 60 || echo "(no crash signatures found)"

# The overlay evidence is deliberately pulled out separately: it is a handful of lines in a
# 100k-line log, and it is the whole answer to "did the pause screen actually appear?".
echo "=== Overlay render evidence (OverlayController / OverlayRenderTest) ==="
grep -E "Overlay (rendered|NOT rendered|render watcher failed)|OverlayRenderTest" "$LOGCAT_FILE" || echo "(no overlay render lines found)"

# Printed last, and in these words, because everything above is thousands of lines of context:
# on a failure the reader needs the verdict at the bottom of the log, not scrolled off the top.
if [ "$RESULT" -ne 0 ]; then
    echo "=== FAILED: ./gradlew ${GRADLE_ARGS[*]} exited $RESULT (variant: $VARIANT) ==="
else
    echo "=== PASSED: ./gradlew ${GRADLE_ARGS[*]} (variant: $VARIANT) ==="
fi

exit "$RESULT"
