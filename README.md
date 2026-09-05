# Mindful Scroll

Mindful Scroll is a free, open-source Android app that helps you notice and
interrupt compulsive infinite-scrolling in apps like Instagram, Reddit,
Facebook, TikTok, and X/Twitter. It does not block anything by force - it
watches for scroll gestures and time spent in the apps *you* choose, and
shows a short pause screen when you cross a threshold *you* set, asking you
to make a deliberate choice about what happens next.

There is no account, no cloud sync, no analytics, and no ads. Mindful
Scroll cannot send data anywhere even if it wanted to - see [Privacy](#privacy--permissions)
below.

## How it works

1. **Onboarding** walks you through granting two Android permissions and
   choosing which installed apps to monitor.
2. An **Accessibility Service** watches for scroll gestures
   (`TYPE_VIEW_SCROLLED`) inside monitored apps only, while they're in the
   foreground.
3. Each monitored app has a configurable threshold - by default, **40
   scrolls or 10 minutes of continuous foreground time, whichever comes
   first**.
4. Crossing the threshold shows a **full-screen interruption overlay**:
   your scroll count and session time, a reflective prompt ("What are you
   looking for right now?"), and two choices - close the app, or continue
   after a deliberate friction step (a 10-second countdown, or typing a
   short phrase, depending on your settings).
5. A **Dashboard** shows your scroll counts and time-in-app for today and
   the past 7 days, plus how often you closed the app vs. chose to
   continue.

### How scroll + foreground detection works (and avoids false positives)

The trickiest part of this app is reliably knowing "which app is in the
foreground, and is this scroll event actually happening inside it." The
approach:

- **Foreground tracking** uses `TYPE_WINDOW_STATE_CHANGED` accessibility
  events, which fire the instant a new window becomes active and carry
  that window's package name. This is tracked live in the service - no
  polling, no lag.
- **Scroll counting** only happens when a `TYPE_VIEW_SCROLLED` event's
  package name matches the currently tracked foreground package **and**
  that package is in your monitored list. Two deliberate guards against
  false positives:
  - Mindful Scroll's own package is never eligible to be monitored (it's
    excluded from the app picker, with a second explicit check as a
    backstop), so scrolling this app's own dashboard or settings screens
    never counts as a "scroll."
  - System UI (notification shade, recents) reports its own package name
    on its own window-state-change events, so it never matches a
    monitored app's package either - and switching to it correctly ends
    the "continuous foreground time" session clock, consistent with the
    threshold being about *continuous* time in the app.
- `UsageStatsManager` is used only for the aggregate "time in app" numbers
  shown on the dashboard, not for live detection - the accessibility
  events are more responsive for that.
- A single finger-swipe can fire several matching events (nested scroll
  containers, feed-of-feeds layouts), so events for the same app within
  ~300ms of each other are treated as one logical scroll. This is a
  tunable MVP judgment call, not a precise measurement.

**Scroll *counting* cannot be trusted as the only trigger.** `TYPE_VIEW_SCROLLED`
is a legacy `View.scrollBy()`-driven event: `ScrollView`/`ListView` fire
it reliably, but `RecyclerView` (most modern feed apps) and Jetpack
Compose's `LazyColumn` move child content directly instead of calling
`View.scrollBy()`, so they often don't fire it - confirmed empirically,
not assumed: `ScrollEventDetectionTest` (see Testing below) scrolls a
real Compose `LazyColumn` 15 times on an emulator and checks which
`AccessibilityEvent` types actually arrive. The result was zero of both
`TYPE_VIEW_SCROLLED` and the `TYPE_WINDOW_CONTENT_CHANGED` fallback
`ScrollMonitorService` also listens for. Both are still wired up (they
may help for legacy View-based feeds, and cost nothing when they don't
fire), but scroll count is a best-effort number for Compose-heavy apps,
not a guarantee.

Because of that, **the time-based half of the threshold is checked on
its own schedule, independent of scroll events entirely**: entering a
monitored app's foreground arms a one-shot delayed check (`delay()` in
the service's coroutine scope) for its configured time threshold (or the
active grace-period deadline, if shorter), which re-evaluates and shows
the overlay if the session is still live when it fires. Scroll events,
when they do arrive, check the same threshold immediately instead of
waiting - whichever path notices first wins. Earlier versions of this
service only ever evaluated the threshold from inside the scroll-event
handler, which meant the time-based trigger silently never fired for any
app that doesn't emit scroll events - this is why.

The interruption overlay itself is drawn as a `TYPE_ACCESSIBILITY_OVERLAY`
window rather than a `SYSTEM_ALERT_WINDOW` - any process that owns a
currently-enabled accessibility service is allowed to add this window
type, so no separate "draw over other apps" permission is requested.

### Diagnostics

Settings has a **Diagnostics** screen (backed by `ServiceDiagnostics`, an
in-memory singleton the accessibility service updates live) showing, in
real time: whether the service is connected, which apps are monitored,
the current foreground package, raw counts of `TYPE_VIEW_SCROLLED` /
`TYPE_WINDOW_CONTENT_CHANGED` events seen from *any* app, how many of
those were actually counted as scrolls, and a recent activity log. If the
interruption never fires, this is the first place to look: if both raw
counters stay at zero while you scroll a monitored app, the OS isn't
delivering either signal for it and detection needs a different approach
for that specific app; if the raw counters climb but nothing gets
counted, the foreground-matching logic is the problem instead. The same
detail is also logged to Logcat under the tag `MindfulScroll`.

## Privacy & permissions

Mindful Scroll requests no network permission of any kind - it is
architecturally incapable of sending data anywhere. Everything is stored
in a local Room (SQLite) database on your device only, and cloud
backup/device-transfer is explicitly disabled for the app's data
(`android:allowBackup="false"` in `AndroidManifest.xml`).

Two permissions are required, both explained in plain language during
onboarding:

- **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE`): lets the app
  notice scroll gestures in the apps you choose to monitor, and draw the
  pause screen. It does not read screen content (`canRetrieveWindowContent`
  is `false`) and only reacts to scroll and window-change events.
- **Usage access** (`PACKAGE_USAGE_STATS`): lets the app read how long
  monitored apps have been in the foreground, for your own stats. This is
  a "special access" permission - Android only lets an app declare it and
  link the user to the system settings screen to turn it on; it is never
  auto-granted.

## Installing on your phone (no computer needed)

**Recommended: [Obtainium](https://github.com/ImranR98/Obtainium).** Install
Obtainium, add this repository as an app source, and it will check for new
[Releases](../../releases), notify you, and install them in place. Set up
once, then updates need nothing from you.

The reason to prefer it over downloading the APK by hand isn't just
convenience. Obtainium installs through Android's session-based package
installer, so:

- updates replace the installed app instead of being a fresh install, which
  means **the accessibility permission you grant once keeps working** rather
  than needing to be re-enabled every time; and
- the app is treated as coming from an app store, which avoids Android 13+
  putting the accessibility toggle behind *App info -> ⋮ -> "Allow
  restricted settings"*.

You can still download `app-release.apk` from a release and tap it, but a
manual install is a fresh install and you'll get both of the prompts above
each time.

Either way this is a real signed release build, not the debug-keystore APK
this project used to publish - which is what Play Protect was flagging.
To build it yourself instead, see [Building](#building) below.

## Tech stack

- Kotlin, Jetpack Compose
- Min SDK 26 (Android 8.0), compile/target SDK 35
- Room for local storage, WorkManager for daily maintenance, Hilt for DI
- No third-party trackers, ad SDKs, analytics, or crash reporting

## Project structure

Single `:app` module, organized by feature area rather than by layer:

```
app/src/main/kotlin/com/mindfulscroll/app/
├── accessibility/   ScrollMonitorService + foreground/permission checks
├── data/            Room entities, DAOs, database, repositories, prefs
├── overlay/         The interruption overlay window + its Compose UI
├── stats/           Threshold logic, usage-access check, WorkManager jobs
├── ui/              Onboarding, app selection, dashboard, settings, nav
├── di/              Hilt modules
├── MainActivity.kt
└── MindfulScrollApp.kt
```

## Building

Requires JDK 17 and the Android SDK (compile/target SDK 35; install via
Android Studio's SDK Manager or `sdkmanager`).

```bash
./gradlew build                     # assembles debug + release, runs lint
./gradlew test                      # unit tests (threshold logic, Room DAOs via Robolectric)
./gradlew connectedDebugAndroidTest # instrumented tests - needs a running emulator/device
```

There is no `local.properties` checked in - point `ANDROID_HOME` /
`sdk.dir` at your SDK install, or open the project in Android Studio and
let it configure this for you.

CI runs three workflows on every push/PR against `main`:
`.github/workflows/android.yml` (`./gradlew build` + `./gradlew test`),
`.github/workflows/instrumented-tests.yml` (`connectedDebugAndroidTest`
on a real emulator via `reactivecircus/android-emulator-runner`), and
`.github/workflows/release-apk.yml` (publishes the debug APK, `main` only).

The instrumented tests exist specifically to verify device-only behavior
that unit tests can't reach - see `ScrollEventDetectionTest` in
`app/src/androidTest`, which checks empirically (by scrolling a real
Compose `LazyColumn` and inspecting which `AccessibilityEvent`s actually
fire) rather than assuming.

## Distribution

Ships as a **signed APK on GitHub Releases**, installed and updated via
[Obtainium](https://github.com/ImranR98/Obtainium). No gatekeeper, no review
queue, and updates land in place.

Cutting a release:

```
git tag v0.2.0
git push origin v0.2.0
```

That triggers `.github/workflows/release-apk.yml`, which builds a signed
release APK and publishes it as `v0.2.0`. `versionCode` is derived from the
tag, so it increases on every release - it has to, or nothing can tell a new
build from the installed one. The workflow needs four repository secrets
(`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`); they're documented at the top of that file. The
signing key is the app's identity to Android - back it up, because replacing
it forces everyone to uninstall and start over rather than update.

**Not the Play Store.** Play review is strict about the Accessibility API
being used for anything other than assisting users with disabilities. This
app isn't eligible for the `isAccessibilityTool` declaration (that's reserved
for genuine disability tools), so listing it would require an accessibility
declaration, a prominent in-app disclosure and an affirmative consent flow,
under the tighter review that took effect in January 2026 - with real
rejection risk at the end of it.

**F-Droid** remains a good later addition: the GPL-3.0 license fits, and a
self-hosted repo would reuse the same signed artifacts. Not set up yet.

## MVP limitations / known gaps

This is a first pass, not a finished product:

- Scroll counting is a best-effort signal, not a precise measurement, and
  is known to be zero for Compose-based feeds - see the detection notes
  above. The time-based half of the threshold is the reliable path.
- Only one continuous foreground session is tracked per app at a time; no
  cross-app "total scrolling today" limit.
- Session/grace-period state, and the scheduled time-threshold check
  itself, live in memory in the accessibility service (a plain
  coroutine `delay()`) and are not restored if the service process is
  killed and restarted mid-session - the next scroll or foreground change
  re-arms it, but a session that both never scrolls and outlives a
  process death in between won't trigger.
- Instrumented test coverage is limited to `ScrollEventDetectionTest`
  (validates the accessibility-event detection mechanism itself) - no UI
  flow tests (onboarding, app selection, dashboard) yet, only JVM unit
  tests for threshold logic and Room DAOs plus that one instrumented test.
- The app-selection list is shown once during onboarding; there is no way
  yet to add a newly installed app to the monitored list without
  reinstalling. Existing monitored apps can be toggled on/off and
  re-thresholded from Settings.
- No launcher-icon-badge or notification summarizing today's stats.

### Explicitly out of scope for this MVP (future work)

- iOS / Screen Time integration
- Browser extension
- Cross-device sync or accounts of any kind
- Social or comparison features
- Algorithmic feed replacement

## License

GPL-3.0. This project uses a strong copyleft license deliberately: any
derivative or redistributed version of Mindful Scroll must also stay open
source under the same terms, which matters for a tool whose entire pitch
is "trust it because you can read exactly what it does with your usage
data." A permissive license (MIT/Apache) would allow a closed-source fork
that quietly adds tracking - GPL-3.0 makes that a license violation, not
just a bad look. See [`LICENSE`](./LICENSE).
