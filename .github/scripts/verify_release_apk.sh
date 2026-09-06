#!/usr/bin/env bash
# Asserts, against a BUILT APK, the safety claims the README makes in prose.
#
# The point is the artifact, not the green tick. The README's claims are about the source; what
# lands on someone's phone is a binary produced by CI. Nothing connected the two, so a reader had
# to trust the maintainer, the runner and the build chain on faith. This checks the thing people
# actually install, after R8, and writes a report a non-programmer can read.
#
# Usage: verify_release_apk.sh <path-to-apk> [report-output-path]
set -uo pipefail

APK="${1:?usage: verify_release_apk.sh <apk> [report]}"
REPORT="${2:-verification-report.txt}"

BUILD_TOOLS="$(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/build-tools" -maxdepth 1 -type d | sort -r | head -1)"
AAPT2="$BUILD_TOOLS/aapt2"
[ -x "$AAPT2" ] || { echo "::error::aapt2 not found under $BUILD_TOOLS"; exit 2; }
[ -f "$APK" ] || { echo "::error::APK not found: $APK"; exit 2; }

FAILED=0
: > "$REPORT"

say()  { echo "$*" | tee -a "$REPORT"; }
pass() { say "  PASS  $*"; }
fail() { say "  FAIL  $*"; FAILED=1; }

say "Mindful Scroll - release APK verification"
say "APK:    $APK"
say "SHA256: $(sha256sum "$APK" | cut -d' ' -f1)"
say "Built:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
say ""
say "Every claim below is checked against the APK itself, after minification -"
say "not against the source it was built from."
say ""

# ---------------------------------------------------------------------------
# 1. Permissions: exactly the two we document, and nothing else.
# ---------------------------------------------------------------------------
say "1. Permissions the app can ever hold"
# The full set, including what dependencies add. Listing only "our" two would have been a nicer
# story and a false one: a reader running `dumpsys package` sees this list, not our intentions.
# Provenance verified against the manifest merger report:
#   PACKAGE_USAGE_STATS      ours     - foreground time for the dashboard
#   RECEIVE_BOOT_COMPLETED   ours     - re-arm the daily maintenance job after reboot
#   WAKE_LOCK                androidx.work:work-runtime  - finish a background job
#   ACCESS_NETWORK_STATE     androidx.work:work-runtime  - evaluate network CONSTRAINTS on jobs.
#                            Reads connectivity state; cannot transmit. Without INTERNET the OS
#                            blocks all network I/O regardless.
#   FOREGROUND_SERVICE       androidx.work, androidx.lifecycle:lifecycle-process
#   DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION  androidx.core - self-defined, signature-level,
#                            used to keep its own runtime receivers un-exported
#
# Any addition to this list fails the build. That is the point: a dependency bump that silently
# introduces a permission should be a release blocker, not something discovered by a user.
EXPECTED_PERMS="android.permission.PACKAGE_USAGE_STATS
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.WAKE_LOCK
android.permission.ACCESS_NETWORK_STATE
android.permission.FOREGROUND_SERVICE
com.mindfulscroll.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
ACTUAL_PERMS="$("$AAPT2" dump permissions "$APK" 2>/dev/null \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u)"

say "  declared: $(echo "$ACTUAL_PERMS" | tr '\n' ' ')"
if [ "$(echo "$EXPECTED_PERMS" | sort)" = "$ACTUAL_PERMS" ]; then
    pass "exactly the documented permission set, nothing added by a dependency bump"
else
    fail "permission set changed. expected:"
    say "$(echo "$EXPECTED_PERMS" | sed 's/^/          /')"
fi

# Named individually as well as by the set comparison above: these are the ones a reader is
# actually frightened of, and "INTERNET is absent" is a stronger sentence than "the set matches".
if echo "$ACTUAL_PERMS" | grep -qx "android.permission.INTERNET"; then
    fail "android.permission.INTERNET IS DECLARED - the app can reach the network"
else
    pass "android.permission.INTERNET absent - the OS makes network access impossible, which is"
    say "        the claim everything else rests on. ACCESS_NETWORK_STATE below reads connectivity"
    say "        state for job scheduling and cannot transmit anything."
fi

for forbidden in android.permission.SYSTEM_ALERT_WINDOW android.permission.READ_CONTACTS \
                 android.permission.QUERY_ALL_PACKAGES android.permission.RECORD_AUDIO \
                 android.permission.CAMERA android.permission.READ_SMS \
                 android.permission.ACCESS_FINE_LOCATION android.permission.READ_EXTERNAL_STORAGE; do
    if echo "$ACTUAL_PERMS" | grep -qx "$forbidden"; then
        fail "$forbidden is declared"
    else
        pass "$forbidden absent"
    fi
done
say ""

# ---------------------------------------------------------------------------
# 2. The accessibility service is configured as narrowly as documented.
# ---------------------------------------------------------------------------
say "2. What the accessibility service is allowed to do"
# AGP shortens resource file paths in release builds (res/xml/foo.xml -> res/Ab.xml), so the path
# has to be resolved through the resource table rather than guessed.
CONFIG_PATH="$("$AAPT2" dump resources "$APK" 2>/dev/null \
    | grep -A2 'xml/accessibility_service_config' | sed -n 's/.*(file) \(res\/[^ ]*\).*/\1/p' | head -1)"

if [ -z "$CONFIG_PATH" ]; then
    fail "accessibility_service_config.xml is not in the APK's resource table"
else
    say "  resource: xml/accessibility_service_config -> $CONFIG_PATH"
    CONFIG_XML="$("$AAPT2" dump xmltree --file "$CONFIG_PATH" "$APK" 2>/dev/null)"

    check_attr() {
        local attr="$1" want="$2" desc="$3"
        local got
        got="$(echo "$CONFIG_XML" | sed -n "s/.*:${attr}(0x[0-9a-f]*)=\(.*\)/\1/p" | head -1)"
        if [ "$got" = "$want" ]; then
            pass "$desc ($attr=$got)"
        else
            fail "$desc - expected $attr=$want, got '${got:-<absent>}'"
        fi
    }

    # 0x1820 = typeViewScrolled | typeWindowContentChanged | typeWindowStateChanged.
    # Asserted exactly: a partially-resolved mask fails as quietly as an empty one.
    check_attr accessibilityEventTypes "0x00001820" "subscribes to exactly three event types"
    check_attr canRetrieveWindowContent "false" "cannot read screen content"
    check_attr canRequestFilterKeyEvents "false" "cannot intercept key events"
fi
say ""

# ---------------------------------------------------------------------------
# 3. No networking code reachable in the shipped DEX.
# ---------------------------------------------------------------------------
say "3. Networking code in the compiled app"
say "  (belt-and-braces: without INTERNET the OS blocks this regardless, but a"
say "   reader can check this one without trusting our permission claim)"
DEX_STRINGS="$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | strings -n 6)"
for sym in 'Ljava/net/Socket;' 'Ljava/net/HttpURLConnection;' 'Ljavax/net/ssl/HttpsURLConnection;' \
           'Lokhttp3/' 'Lretrofit2/' 'Landroid/webkit/WebView;' 'Lcom/android/volley/'; do
    if echo "$DEX_STRINGS" | grep -qF "$sym"; then
        fail "$sym referenced in the shipped DEX"
    else
        pass "$sym not referenced"
    fi
done
say ""

# ---------------------------------------------------------------------------
# 4. Nothing native, nothing extra loaded at runtime.
# ---------------------------------------------------------------------------
say "4. Native code and runtime libraries"
# Allowlisted rather than forbidden: Compose pulls in androidx.graphics.path, so "no native code"
# was never true and claiming it would be the same mistake as the permission list. What matters is
# that no UNEXPECTED native library appears - native code is not readable by the reviewer, so a new
# one is exactly the thing worth blocking on.
ALLOWED_SO="libandroidx.graphics.path.so"
NATIVE="$(unzip -l "$APK" 2>/dev/null | awk '{print $4}' | grep -E '\.so$' | sed 's|.*/||' | sort -u || true)"
UNEXPECTED_SO="$(echo "$NATIVE" | grep -vxF "$ALLOWED_SO" || true)"
if [ -z "$NATIVE" ]; then
    pass "no native libraries bundled"
elif [ -z "$UNEXPECTED_SO" ]; then
    pass "only the expected native library ($ALLOWED_SO, from androidx.graphics via Compose)"
else
    fail "unexpected native libraries:"
    say "$(echo "$UNEXPECTED_SO" | sed 's/^/          /')"
fi

USES_LIB="$("$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null \
    | grep -c 'E: uses-library' || true)"
if [ "${USES_LIB:-0}" = "0" ]; then
    pass "no <uses-library> entries"
else
    fail "$USES_LIB <uses-library> entries in the manifest"
fi
say ""

# ---------------------------------------------------------------------------
# 5. Signing identity, when the APK is signed.
# ---------------------------------------------------------------------------
say "5. Signing identity"
APKSIGNER="$BUILD_TOOLS/apksigner"
if [ -x "$APKSIGNER" ] && "$APKSIGNER" verify --print-certs "$APK" > /tmp/certs.txt 2>/dev/null; then
    FINGERPRINT="$(sed -n 's/.*certificate SHA-256 digest: \(.*\)/\1/p' /tmp/certs.txt | head -1)"
    say "  release certificate SHA-256: $FINGERPRINT"
    pass "APK is signed - compare this fingerprint against the one published in the README"
else
    say "  unsigned (expected for PR builds; release builds are signed)"
fi
say ""

if [ "$FAILED" -eq 0 ]; then
    say "RESULT: all claims verified against this APK."
else
    say "RESULT: FAILED - at least one documented claim is not true of this APK."
fi
exit "$FAILED"
