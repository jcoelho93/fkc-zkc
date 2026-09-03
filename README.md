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
- A single finger-swipe can fire several `TYPE_VIEW_SCROLLED` events
  (nested scroll containers, feed-of-feeds layouts), so scroll events for
  the same app within ~300ms of each other are treated as one logical
  scroll. This is a tunable MVP judgment call, not a precise measurement.

The interruption overlay itself is drawn as a `TYPE_ACCESSIBILITY_OVERLAY`
window rather than a `SYSTEM_ALERT_WINDOW` - any process that owns a
currently-enabled accessibility service is allowed to add this window
type, so no separate "draw over other apps" permission is requested.

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
./gradlew build   # assembles debug + release, runs lint
./gradlew test    # unit tests (threshold logic, Room DAOs via Robolectric)
```

There is no `local.properties` checked in - point `ANDROID_HOME` /
`sdk.dir` at your SDK install, or open the project in Android Studio and
let it configure this for you.

CI (`.github/workflows/android.yml`) runs `./gradlew build` and
`./gradlew test` on every push and pull request against `main`.

## Distribution

Play Store review is known to be strict about apps that use the
Accessibility API for anything other than assisting users with
disabilities, and an app whose *core* feature is accessibility-service-based
monitoring is a plausible rejection/removal target even with a clear,
honest description. To avoid that friction for this MVP, the plan is to
ship via **F-Droid and GitHub Releases (sideloaded APK)** rather than the
Play Store. This may be revisited later, but isn't a goal for the MVP.

## MVP limitations / known gaps

This is a first pass, not a finished product:

- Scroll counting is a coarse debounce-based approximation, not a
  precise measurement - see the detection notes above.
- Only one continuous foreground session is tracked per app at a time; no
  cross-app "total scrolling today" limit.
- Session/grace-period state (e.g. the "5 more minutes" countdown window)
  lives in memory in the accessibility service and is not restored if the
  service process is killed and restarted mid-grace.
- No instrumented (UI) tests yet - only JVM unit tests for threshold
  logic and Room DAOs.
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
