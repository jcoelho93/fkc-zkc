# Contributing to Mindful Scroll

How the app works underneath, how to build and test it, and how releases are cut. For what the
app does and why, see [README.md](README.md).

Project conventions - including the traps that have already cost this project days - are in
[CLAUDE.md](CLAUDE.md), which is worth reading before changing anything in `accessibility/` or
`overlay/`.

## How detection works

The hard part is knowing *which app is in the foreground* and *whether this scroll happened
inside it*.

- **Foreground tracking** uses `TYPE_WINDOW_STATE_CHANGED` events, which fire the moment a
  window becomes active and carry its package name. Live, no polling.
- **Scroll counting** requires a `TYPE_VIEW_SCROLLED` event whose package matches the tracked
  foreground package *and* is on your monitored list. Mindful Scroll's own package can never be
  monitored, so its own screens never count; system UI (shade, recents) reports its own package,
  so it never matches either — and switching to it correctly ends the continuous-session clock.
- A fling fires scroll and content-changed events for its whole deceleration tail, so events are
  **grouped into swipes**: an event only counts as a new scroll after **800 ms with no event**
  from that app, and every event pushes the end of the swipe forward
  (`stats/ScrollGestureCoalescer`). One fling is one scroll whichever type it emits, and content
  that keeps changing on its own stays one swipe instead of accruing. The old rule, 300 ms from
  the last *counted* event, re-opened inside every fling: on the emulator it counted 10 settled
  swipes as 40 and kept counting an untouched animating screen (#25). The trade-off is that the
  count now errs low. Swipes that land while the feed is still moving merge into one, and so do
  swipes over content that never stops changing.
- **An open** is one foreground-entry into a monitored app: the same transition that starts a
  session and shows the intention prompt. It is counted there and nowhere else, so scrolls, a
  session restarted by "5 more minutes" and the keyboard never add opens. Days recorded before
  open counting existed hold `NULL` ("not counted"), never 0.
- **`UsageStatsManager`** is used only for the aggregate "time in app" figures on the dashboard,
  never for live detection.

**Scroll counting cannot be the only trigger.** `TYPE_VIEW_SCROLLED` is a legacy
`View.scrollBy()` event: `ScrollView`/`ListView` fire it, but `RecyclerView` and Compose's
`LazyColumn` — most modern feeds — move content without it. `ScrollEventDetectionTest` scrolls a
real Compose `LazyColumn` 15 times on an emulator and records **zero** of both that event and
the `TYPE_WINDOW_CONTENT_CHANGED` fallback. Both stay wired up (they may help legacy View-based
feeds, and cost nothing when they don't fire), but scroll count is best-effort.

So **the time half of the threshold runs on its own schedule**, independent of scroll events:
entering a monitored app arms a one-shot delayed check for its time threshold (or the shorter
grace deadline), which re-evaluates when it fires. Scroll events, when they arrive, check the
same threshold immediately — whichever path notices first wins. Earlier versions only ever
evaluated the threshold inside the scroll handler, so the time trigger silently never fired for
apps that emit no scroll events.

The pause screen and the intention prompt are both `TYPE_ACCESSIBILITY_OVERLAY` windows rather
than `SYSTEM_ALERT_WINDOW`, so **no "draw over other apps" permission is requested**.

### Diagnostics

Settings → Diagnostics shows, live: whether the service is connected, monitored apps, current
foreground package, raw counts of `TYPE_VIEW_SCROLLED` / `TYPE_WINDOW_CONTENT_CHANGED` from
*any* app, how many swipes were counted as scrolls (split by which event type opened each one),
how many events were folded into a swipe already counted, and a recent activity log. The same detail goes to
Logcat under tag `MindfulScroll`. If the interruption never fires:

- raw counters stay at **zero** while you scroll → the OS delivers neither signal for that app;
- raw counters climb but neither *scrolls counted* nor *events folded* moves → the
  foreground-matching logic is at fault. Events far outnumbering scrolls is normal: one swipe
  emits many.

**Scheduled threshold checks fired** is the third counter to read here, and for most feed apps it
is the only one that matters. Compose feeds deliver no scroll events at all, so the time half of
the threshold has to be evaluated on its own timer rather than whenever an event happens to
arrive. If this stays at zero while an app sits in the foreground past its limit, that timer is
not running — and no amount of scrolling will ever trigger the pause.

Overlays are reported as two numbers — **windows added** and **windows actually drawn**.
"Added" only means `addView()` returned; the window can still be accepted and then never laid
out, sized 0×0 or never composed, while every other counter reads like success. "Drawn" means a
real non-zero-sized frame appeared. When the two diverge, **last overlay render** says what
happened instead — each window gets two seconds to produce a frame and files its own complaint
if none arrives.

## Building

Requires **JDK 17** and the Android SDK. Min SDK 26 (Android 8.0), compile/target SDK 35.
Kotlin, Jetpack Compose, Room, WorkManager, Hilt.

```bash
./gradlew build                     # debug + release, lint
./gradlew test                      # unit tests (threshold logic, Room DAOs via Robolectric)
./gradlew connectedDebugAndroidTest # instrumented - needs an emulator/device
```

No `local.properties` is checked in: point `ANDROID_HOME` / `sdk.dir` at your SDK, or let
Android Studio configure it.

Single `:app` module, organised by feature:

```
app/src/main/kotlin/com/mindfulscroll/app/
├── accessibility/   ScrollMonitorService + foreground/permission checks
├── data/            Room entities, DAOs, database, repositories, prefs
├── intention/       The "what are you hoping to find?" prompt + its Compose UI
├── overlay/         The interruption overlay window + its Compose UI
├── stats/           Threshold logic, usage-access check, WorkManager jobs
├── ui/              Onboarding, app selection, dashboard, settings, diagnostics, nav
├── di/              Hilt modules
├── MainActivity.kt
└── MindfulScrollApp.kt
```

### CI

Three required checks on every PR against `main`:

| Check | What it runs |
|---|---|
| `build` | `./gradlew build` + unit tests; archives R8's `mapping/usage/seeds`. |
| `instrumented-tests (debug)` | The emulator suite on the debug APK. |
| `instrumented-tests (release)` | The same suite on the **R8-minified** APK. |

The release run is not a duplicate: it answers the two runtime questions no static check of the
APK can. Does the system still resolve the full event mask (asserted to be exactly
`typeViewScrolled|typeWindowContentChanged|typeWindowStateChanged` — a partially resolved mask
fails as quietly as an empty one), and does the `TYPE_ACCESSIBILITY_OVERLAY` window still draw?
Keep rules in `app/proguard-rules.pro` each name the specific thing that breaks without them.

Locally, the release variant runs with:

```bash
./gradlew connectedReleaseAndroidTest \
    -Pmindfulscroll.testBuildType=release \
    -Pmindfulscroll.signReleaseWithDebugKey=true
```

`build` also runs [`verify_release_apk.sh`](.github/scripts/verify_release_apk.sh) on the built
release APK: the permission set, the accessibility config, networking code, native libraries,
and an [Exodus Privacy](https://reports.exodus-privacy.eu.org/) tracker scan. The scan runs in
Exodus's own Docker image, pinned by digest in the script, against their live tracker database.
So it needs network access to `reports.exodus-privacy.eu.org`, and it fails, rather than passing
empty, if that is unreachable. Run it locally (Docker needed) with:

```bash
./gradlew assembleRelease
.github/scripts/verify_release_apk.sh app/build/outputs/apk/release/app-release-unsigned.apk report.txt
```

The release workflow runs the same script with `REQUIRE_SIGNED=1`, which also requires the APK's
signing certificate to equal the fingerprint published in the README, under
`<!-- release-cert-sha256 -->`. **Rotating the signing key therefore means updating that README
line in the same change**, or the next release fails before it publishes.

[OpenSSF Scorecard](https://scorecard.dev/) runs weekly and on every push to `main`
(`scorecard.yml`). It is not a required check: it grades the repository's process, and its
findings land under *Security → Code scanning*.

## Releases & distribution

Ships as a **signed APK on GitHub Releases**, installed and updated via Obtainium. No
gatekeeper, no review queue, updates land in place.

```bash
git tag v0.3.0 && git push origin v0.3.0
```

That triggers `.github/workflows/release-apk.yml`, which builds and publishes a signed release.
`versionCode` is derived from the tag, so it always increases — it has to, or nothing can tell a
new build from the installed one. Four repository secrets are needed
(`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`), documented at the top of that workflow. **Back up the signing key** —
it is the app's identity to Android, and replacing it forces everyone to uninstall and start
over. Its certificate fingerprint is published in the README, and a release signed by anything
else fails its verification step.

Security reports go through GitHub's private vulnerability reporting; see
[SECURITY.md](SECURITY.md).

**Not the Play Store.** Play review is strict about the Accessibility API being used for
anything other than assisting users with disabilities, and this app isn't eligible for the
`isAccessibilityTool` declaration (reserved for genuine disability tools). Listing it would mean
an accessibility declaration, a prominent in-app disclosure and an affirmative consent flow,
under the tighter review in force since January 2026 — with real rejection risk at the end.

**F-Droid** is a good later addition (GPL-3.0 fits, and a self-hosted repo would reuse the same
signed artifacts). Not set up yet.

## Test coverage

Thin, and honestly so: JVM unit tests for threshold logic and the Room DAOs (Robolectric), plus
the instrumented suite in `app/src/androidTest` (runs on both variants) and
`app/src/androidTestDebug` (debug-only, for tests needing debug components or with no reason to
run twice). There are no UI flow tests for onboarding, app selection or the dashboard.

Two things worth knowing before writing an instrumented test here:

- **`adb screencap` cannot capture `TYPE_ACCESSIBILITY_OVERLAY` windows.** They are provably on
  screen and come out blank, so "I could not see it" is not evidence of anything.
  `OverlayPreviewActivity` (debug-only) hosts overlay composables in an ordinary window for
  visual review.
- The accessibility node tree *can* see those windows, so overlay UI is testable by tapping even
  though it cannot be photographed - but `uiautomator dump` only walks the **active** window, and
  a deliberately non-focusable overlay is never active.

## Writing user-facing copy

Anything the app says about the user's own behaviour must be **neutral and informational**:
reflection, stats, the pause screen, the intention prompt, notifications. It reports what happened.
It never implies the user failed, broke something, or should feel bad, and it doesn't praise
them either.

This isn't only about being polite. Guilt and nagging have small average effects in the
research, and they can **backfire through reactance**, entrenching the very behaviour they target.
It also protects the data. If a chip reads as an admission, people learn to tap a nicer one,
and the weekly report ends up comparing answers that were chosen to look good rather than
true ones.

### Checklist

Check every new or changed string against these before merging:

- [ ] **States a fact, not a verdict.** A count, a duration, a comparison with the user's
      *own* stated intention. No "too much", "wasted", "only", "again".
- [ ] **No failure or rule language.** Not "broke", "failed", "exceeded your limit", "blew
      your goal", "cheated". A threshold is a setting the user chose, not a rule they broke.
- [ ] **No praise either.** "Great job!" and "Well done" judge in the other direction, and they
      make the answers that don't get praised feel like failures.
- [ ] **No "should".** Copy doesn't tell the user what to want. Suggestions are
      offered as options ("Close Instagram" / "Keep scrolling"), with equal visual weight.
- [ ] **No urgency.** No exclamation marks, no countdown pressure, no red or alarm colours for
      the user's own numbers.
- [ ] **No scores.** No streaks, points, badges, ranks or "addiction score". These are permanently out of
      scope (see [CLAUDE.md](CLAUDE.md)).

### Before and after

| Don't | Do |
|---|---|
| "You broke your goal again." | "You opened Instagram 14 times this week, 3 more than last week." |
| "You wasted 2 hours on Reddit today 😬" | "2 h 05 min in Reddit today." |
| "Only 20% of your Instagram visits were worth it." | "On Instagram you said *connection* on 60% of opens, and felt you got it 1 time in 5." |
| "Great job! You closed the app!" | *(no comment. The choice is recorded, not graded)* |

### Enforced partly by a test

`CopyToneTest` scans the string literals in the app's user-facing packages for the clearest
violations: failure words, praise, "should", streak/score vocabulary. It fails the build on a
match. It catches words, not tone, so it doesn't replace the checklist. A line that needs one of
those words legitimately can carry a `// copy-tone: ok - <why>` comment, and the reason goes in
the PR.
