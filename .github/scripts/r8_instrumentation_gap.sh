#!/usr/bin/env bash
# How far apart are the shipped release APK and the release APK the instrumented suite runs on?
#
# app/proguard-rules-instrumentation.pro keeps kotlin.** and kotlinx.coroutines.** whole (plus a
# few single classes) while the release variant is being instrumented, because the test apk
# resolves those against the app apk. So `instrumented-tests (release)` does NOT run the shipped
# artifact: the stdlib and coroutines are not shrunk there, and keeping them whole also changes
# R8's optimisation decisions downstream, so some of the app's own classes are inlined in one
# build and kept in the other. This script puts a number on that gap instead of leaving it as
# prose that rots on the next dependency bump.
#
# Usage:
#   r8_instrumentation_gap.sh                         # builds both variants itself, then diffs
#   r8_instrumentation_gap.sh SHIPPED_USAGE INSTRUMENTED_USAGE [SHIPPED_MAPPING]
#
# The no-argument form runs `./gradlew assembleRelease` twice (plain, then with
# -Pmindfulscroll.testBuildType=release) from the repo root, so JAVA_HOME must point at JDK 17.
#
# Reading usage.txt (see CLAUDE.md): class lines are unindented, member lines are indented. A class
# line with a trailing ':' means "class kept, some members removed"; only a class line WITHOUT a
# colon is a fully-removed class, and that is all this script counts. A class listed as removed
# may simply have been inlined into its callers, so for the app's own classes it also checks the
# shipped mapping.txt for inlined frames before calling anything gone.
#
# Informational only: it never fails on the numbers, only on missing inputs.

set -euo pipefail

APP_PACKAGE="com.mindfulscroll.app"

if [ "$#" -eq 0 ]; then
  cd "$(git rev-parse --show-toplevel)"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  out=app/build/outputs/mapping/release
  ./gradlew -q assembleRelease
  cp "$out/usage.txt" "$work/shipped-usage.txt"
  cp "$out/mapping.txt" "$work/shipped-mapping.txt"
  ./gradlew -q assembleRelease -Pmindfulscroll.testBuildType=release
  cp "$out/usage.txt" "$work/instrumented-usage.txt"
  "$0" "$work/shipped-usage.txt" "$work/instrumented-usage.txt" "$work/shipped-mapping.txt"
  exit
fi

if [ "$#" -lt 2 ]; then
  echo "usage: $0 [SHIPPED_USAGE INSTRUMENTED_USAGE [SHIPPED_MAPPING]]" >&2
  exit 2
fi

shipped_usage="$1"
instrumented_usage="$2"
shipped_mapping="${3:-}"
for f in "$shipped_usage" "$instrumented_usage" ${shipped_mapping:+"$shipped_mapping"}; do
  [ -f "$f" ] || { echo "missing input: $f" >&2; exit 2; }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Fully-removed classes: unindented lines with no trailing colon.
fully_removed() { grep -v '^[[:space:]]' "$1" | grep -v ':$' | LC_ALL=C sort -u; }

fully_removed "$shipped_usage" > "$tmp/shipped"
fully_removed "$instrumented_usage" > "$tmp/instrumented"
LC_ALL=C comm -23 "$tmp/shipped" "$tmp/instrumented" > "$tmp/gap"     # shrunk as shipped, kept when instrumented
LC_ALL=C comm -13 "$tmp/shipped" "$tmp/instrumented" > "$tmp/reverse" # the other way round

count() { wc -l < "$1" | tr -d ' '; }

# Buckets are ordered: a class lands in the first one whose prefix matches.
bucket() {
  awk -v app="$APP_PACKAGE." '
    index($0, app) == 1                     { b["app (" substr(app, 1, length(app) - 1) ")"]++; next }
    index($0, "kotlinx.coroutines.") == 1   { b["kotlinx.coroutines"]++; next }
    index($0, "kotlin.") == 1               { b["kotlin (stdlib)"]++; next }
    index($0, "androidx.compose.") == 1     { b["androidx.compose"]++; next }
    index($0, "androidx.") == 1             { b["androidx (other)"]++; next }
                                            { b["other"]++ }
    END { for (k in b) printf "%7d  %s\n", b[k], k }
  ' "$1" | sort -rn
}

echo "R8: shipped release vs instrumented release (fully-removed classes only)"
echo
printf '%7s  %s\n' "$(count "$tmp/shipped")" "fully removed in the shipped build"
printf '%7s  %s\n' "$(count "$tmp/instrumented")" "fully removed in the instrumented build"
printf '%7s  %s\n' "$(count "$tmp/gap")" "removed in shipped, KEPT in instrumented  <- the gap"
printf '%7s  %s\n' "$(count "$tmp/reverse")" "removed in instrumented, kept in shipped"
echo
echo "The gap, by package:"
bucket "$tmp/gap"

grep -F "$APP_PACKAGE." "$tmp/gap" > "$tmp/app" || true
if [ -s "$tmp/app" ]; then
  echo
  echo "App classes removed in shipped but kept in instrumented:"
  # Three different outcomes that usage.txt alone reports identically as "removed":
  #   - its methods appear as inline frames inside other classes -> inlined into callers
  #   - it is only named in other methods' signatures             -> class-inlined: R8 stopped
  #     allocating it and folded its fields into the caller's locals, so the code survives but
  #     the class does not
  #   - it is not named anywhere in mapping.txt                   -> actually gone
  while IFS= read -r cls; do
    if [ -z "$shipped_mapping" ]; then
      echo "  $cls"
    elif grep -qF " $cls." "$shipped_mapping"; then
      echo "  $cls  (inlined into its callers)"
    elif grep -qF "$cls" "$shipped_mapping"; then
      echo "  $cls  (class-inlined: named only in callers' signatures)"
    else
      echo "  $cls  (no trace in the shipped mapping.txt)"
    fi
  done < "$tmp/app"
fi
