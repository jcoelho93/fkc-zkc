#!/usr/bin/env python3
"""Scan a built APK for known trackers with Exodus Privacy's own analyser, and refuse to report
"zero trackers" unless the scan demonstrably ran.

Run by verify_release_apk.sh inside the official exodus-standalone image (pinned by digest there),
which ships exodus-core and dexdump. It drives the same two calls as that image's
exodus_analyze.py - load_trackers_signatures() then tracker detection - so the matching is
Exodus's, not ours.

What this adds is the part exodus_analyze.py leaves out. Its exit code is simply "number of
trackers found", so every way of scanning nothing also exits 0 and reads as a clean result:

  - the signature download failing or coming back partial   -> no signatures, no matches
  - dexdump extracting no classes from the APK               -> nothing to match against
  - signatures and class names ceasing to match each other  -> e.g. a format change upstream

Each of those gets an explicit check below, and one is a positive control: a planted list of real
tracker class names that the scanner must flag. "0 known trackers" is only reported after the same
scanner has been seen finding them.

Usage: exodus_scan.py <apk>
Report lines go to stdout, diagnostics to stderr. Exit 0 only if every check passed.
"""
import importlib.metadata
import logging
import sys
import time
from datetime import datetime, timezone

from exodus_core.analysis.static_analysis import StaticAnalysis

# The tracker database had 432 signatures (428 usable) in September 2026. A response well below
# that is a partial or broken download, not a smaller world of trackers.
MIN_USABLE_SIGNATURES = 300

# Class names in the form dexdump emits them, for SDKs any tracker database has to know about.
# If the scanner can't flag these, it can't flag anything, and its "0 trackers" means nothing.
POSITIVE_CONTROL = [
    "com/google/firebase/analytics/FirebaseAnalytics",  # Google Firebase Analytics
    "com/google/android/gms/ads/AdView",                # Google AdMob
    "com/facebook/appevents/AppEventsLogger",           # Facebook Analytics
]

# A class that must be in any real build of this app: the accessibility service is referenced by
# the manifest, so R8 keeps its name. Finding it proves dexdump read the right DEX files.
OWN_CLASS = "com/mindfulscroll/app/accessibility/ScrollMonitorService"
MIN_CLASSES = 1000

FETCH_ATTEMPTS = 3


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: exodus_scan.py <apk>", file=sys.stderr)
        return 2
    apk = sys.argv[1]
    logging.basicConfig(level=logging.WARNING, format="exodus-core: %(message)s")

    failed = False

    def say(line: str = "") -> None:
        print(line, flush=True)

    def check(ok: bool, desc: str) -> None:
        nonlocal failed
        say(f"  {'PASS' if ok else 'FAIL'}  {desc}")
        failed |= not ok

    say(f"  scanner:    exodus-core {importlib.metadata.version('exodus_core')}")

    analysis = StaticAnalysis(apk)
    for attempt in range(1, FETCH_ATTEMPTS + 1):
        try:
            analysis.load_trackers_signatures()
            break
        except Exception as e:  # network or JSON failure: retried, then fails the check below
            print(f"signature download attempt {attempt}/{FETCH_ATTEMPTS} failed: {e!r}",
                  file=sys.stderr, flush=True)
            analysis.signatures = None
            if attempt < FETCH_ATTEMPTS:
                time.sleep(10 * attempt)

    signatures = analysis.signatures or []
    # Detection silently skips signatures of 3 characters or fewer; count only what it uses.
    usable = [s for s in signatures if len(s.code_signature) > 3]
    say(f"  signatures: {len(usable)} usable, from reports.exodus-privacy.eu.org at "
        f"{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}")
    check(len(usable) >= MIN_USABLE_SIGNATURES,
          f"tracker database downloaded in full (at least {MIN_USABLE_SIGNATURES} signatures)")
    if not usable:
        say("  ----  scan not run: there were no tracker signatures to scan with")
        return 1

    planted = analysis.detect_trackers_in_list(POSITIVE_CONTROL)
    check(len({t.id for t in planted}) == len(POSITIVE_CONTROL),
          "scanner flags planted tracker classes "
          f"({', '.join(sorted(t.name for t in planted)) or 'none'})")

    classes = analysis.get_embedded_classes()
    classes_read = len(classes) >= MIN_CLASSES and OWN_CLASS in classes
    check(classes_read,
          f"read {len(classes)} class names from the APK's DEX files, including this app's own")

    found = analysis.detect_trackers_in_list(classes)
    if not classes_read:
        # "0 trackers" in nothing is not a result; don't print it as one.
        say("  ----  tracker count not reported: the APK's classes were not read")
    elif found:
        check(False, f"{len(found)} known tracker(s) in the APK:")
        for t in found:
            say(f"          {t.name} (exodus id {t.id})")
    else:
        check(True, "0 known trackers in the APK")

    say("  Exodus matches class names against the SDKs in its database. A tracker whose classes")
    say("  were renamed would not be matched. The INTERNET check in section 1 does not depend on")
    say("  names, and it is the one that stops data leaving the device.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
