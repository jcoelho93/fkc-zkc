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
- Nested scroll containers can fire several events per swipe, so events from the same app within
  ~300 ms are treated as one scroll. A tunable MVP judgment call, not a measurement.
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
*any* app, how many were counted as scrolls, and a recent activity log. The same detail goes to
Logcat under tag `MindfulScroll`. If the interruption never fires:

- raw counters stay at **zero** while you scroll → the OS delivers neither signal for that app;
- raw counters climb but nothing is counted → the foreground-matching logic is at fault.

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
over.

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
