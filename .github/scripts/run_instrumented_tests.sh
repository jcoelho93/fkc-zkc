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

echo "=== Filtered logcat (broad: accessibility / bind / package manager / mindfulscroll / crashes) ==="
grep -iE "accessib|bindservice|iaccessibility|servicecon|packagemanager|activitytaskmanager|activitymanager|resolveinfo|resolveservice|mindfulscroll|scrollmonitor|androidruntime|fatal exception|denied|securityexception|not allowed|refused" /tmp/full-logcat.txt || echo "(no matching lines)"

echo ""
echo "=== Full logcat line count: $(wc -l < /tmp/full-logcat.txt) (uploaded in full as the 'full-logcat' artifact) ==="

exit "$RESULT"
