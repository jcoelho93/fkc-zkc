#!/usr/bin/env bash
# Runs as a real script file (not inline YAML) deliberately: reactivecircus/android-emulator-runner's
# `script:` input executes each line of a multi-line block as its OWN separate `sh -c` invocation
# rather than one continuous shell, which silently breaks anything relying on shared state between
# lines (background-job PIDs, variables, `set -e` carrying through). A checked-in script file gets
# normal, single-process bash semantics.
set -uo pipefail # deliberately not -e: we want to capture gradle's exit code and still print logcat after a failure

adb logcat -c
adb logcat -v time > /tmp/full-logcat.txt &
LOGCAT_PID=$!

./gradlew connectedDebugAndroidTest
RESULT=$?

sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true

echo "=== Full logcat line count: $(wc -l < /tmp/full-logcat.txt) (uploaded in full as the 'full-logcat' artifact) ==="

# Print EVERY line (no keyword filtering - a curated grep has already missed the cause twice)
# from the moment ScrollMonitorServiceInstrumentedTest writes its settings through to well
# past its 30s timeout, so nothing in that window can be missed regardless of terminology.
MARKER_LINE=$(grep -n "ScrollMonitorServiceTest.*After writing settings" /tmp/full-logcat.txt | head -1 | cut -d: -f1)
if [ -n "${MARKER_LINE:-}" ]; then
    echo "=== Complete unfiltered logcat from the settings write (line $MARKER_LINE) through +2000 lines ==="
    tail -n "+${MARKER_LINE}" /tmp/full-logcat.txt | head -n 2000
else
    echo "=== Could not find the settings-write marker line - dumping last 2000 lines of logcat instead ==="
    tail -n 2000 /tmp/full-logcat.txt
fi

exit "$RESULT"
