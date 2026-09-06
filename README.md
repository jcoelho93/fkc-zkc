# Mindful Scroll

A free, open-source Android app that helps you notice and interrupt compulsive
infinite-scrolling in apps like Instagram, Reddit, Facebook, TikTok and X/Twitter.

It blocks nothing by force. It watches time and scrolling in the apps *you* choose, asks
what you came for, and shows a pause screen when you cross a threshold *you* set.

No account, no cloud sync, no analytics, no ads — and no network permission, so it
*cannot* send data anywhere. See [Privacy & permissions](#privacy--permissions).

---

## What it does today

1. **Onboarding** — grant two permissions, pick which installed apps to monitor.
2. **Intention prompt** — when a monitored app comes to the foreground, a small strip at the
   bottom asks *"What are you hoping to find?"* with chips (Connection, Entertainment,
   Distraction, Habit, Checking something specific) and an optional note. It never takes a tap
   or a keystroke from the app underneath; ignore it and it goes away. Turn it off in Settings.
3. **Thresholds** — per app, default **40 scrolls or 10 minutes of continuous foreground
   time**, whichever comes first.
4. **Pause screen** — a full-screen overlay showing your scroll count and session time, the
   prompt *"What are you looking for right now?"*, and two choices: close the app, or continue
   through a friction step (a 10-second countdown, or typing "I choose to keep scrolling",
   per-app setting). Continuing gives you 5 more minutes before the pause screen returns.
5. **Dashboard** — scrolls and time in app for today and the past 7 days, plus how often you
   closed vs. continued.
6. **Settings** — toggle apps, edit thresholds and friction mode, and a **Diagnostics** screen
   for when detection misbehaves.

The chips are deliberately not framed as good or bad: "Habit" and "Distraction" are honest
answers, and nothing scores you for giving them.

## Why it works this way

Infinite feeds are not compelling by accident. Two findings explain the pull, and both shape
the design.

**Variable-ratio reinforcement.** A reward that arrives unpredictably, on an unpredictable
schedule, produces the most persistent behaviour there is — far more persistent than a reward
that arrives every time. A feed that *sometimes* has something good is therefore much harder to
put down than one that reliably does. The uncertainty is the hook, not the content.

**Reward-prediction error.** The dopamine response tracks *anticipation*, not the reward
itself. It fires before you see the next post, on the chance that it might be better than
expected. The pull lives in the next swipe rather than in what you find — which is why "just
one more" keeps working after twenty dull posts, and why scrolling can feel bad the whole time
and still continue.

Neither loop is broken by a wall. A hard block fights the compulsion at the exact moment you
want it most, and mostly teaches you to route around the block. What does interrupt the loop is
awareness inside it: a question at the moment you open the app, while your intention is still
legible to you, and a pause at a limit you chose while calm. Hence a prompt, a pause and honest
numbers — and never streaks, points or badges, which would just be another variable-ratio
schedule wearing a helpful face.

## Privacy & permissions

- **No network permission of any kind** — `android.permission.INTERNET` is deliberately never
  declared, so the app is architecturally incapable of phoning home.
- Everything lives in a local Room (SQLite) database on your device.
- Cloud backup and device transfer are disabled for the app's data
  (`android:allowBackup="false"`).
- No third-party trackers, ad SDKs, analytics or crash reporting.

Two permissions are required, both explained in plain language during onboarding:

| Permission | Why it is needed | Notes |
|---|---|---|
| **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE`) | Notice scroll and window events in the apps you chose, and draw the prompt and pause screen. | Does **not** read screen content (`canRetrieveWindowContent` is `false`). Reacts only to scroll and window-change events. |
| **Usage access** (`PACKAGE_USAGE_STATS`) | Read how long monitored apps were in the foreground, for your own stats. | A "special access" permission: Android only lets an app link you to the system settings screen. Never auto-granted. |

The only other declared permission is `RECEIVE_BOOT_COMPLETED`, which re-schedules the daily
maintenance job after a reboot.

## Installing on your phone (no computer needed)

**Recommended: [Obtainium](https://github.com/ImranR98/Obtainium).** Install Obtainium, add
this repository as an app source, and it checks [Releases](../../releases), notifies you, and
installs updates in place. Set up once; updates then need nothing from you.

Preferring it is not just convenience. Obtainium installs through Android's session-based
package installer, so:

- updates **replace** the installed app instead of being a fresh install, which means **the
  accessibility permission you grant once keeps working** instead of needing to be re-enabled
  every time; and
- the app counts as coming from an app store, which avoids Android 13+ hiding the accessibility
  toggle behind *App info → ⋮ → "Allow restricted settings"*.

**Manual:** download `app-release.apk` from a release and tap it. It works, but every install is
a fresh install, so you get both of the prompts above each time.

Either way it is a real signed release build, not the debug-keystore APK this project used to
publish — which is what Play Protect was flagging. To build it yourself, see
[Building](#building).

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

## Known gaps

- Scroll counting is best-effort and is known to be zero for Compose feeds; the time half of the
  threshold is the reliable path.
- One continuous session per app at a time; no cross-app "total scrolling today" limit.
- Session, grace-period and the scheduled time check live in memory in the accessibility service
  (a coroutine `delay()`). They are not restored if the process is killed mid-session — the next
  scroll or foreground change re-arms them, but a session that never scrolls and outlives a
  process death won't trigger.
- Apps to monitor are picked during onboarding; there is no way yet to add a newly installed app
  without reinstalling. Existing ones can be toggled and re-thresholded in Settings.
- Test coverage is thin: JVM unit tests for threshold logic and Room DAOs, plus three
  instrumented tests (`ScrollEventDetectionTest`, `ScrollMonitorServiceInstrumentedTest`,
  `OverlayRenderInstrumentedTest`). No UI flow tests for onboarding, app selection or dashboard.
- Nothing yet reads back the captured intentions — they are stored, but not surfaced.
- No launcher badge or notification summarising the day.

**Permanently out of scope:** iOS, browser extension, cross-device sync or accounts, social or
comparison features, algorithmic feed replacement, and any gamification (streaks, points,
badges) — which would reintroduce the exact mechanic this app exists to interrupt.

## License

GPL-3.0 — deliberately strong copyleft. Any derivative or redistributed version must stay open
source under the same terms, which matters for a tool whose entire pitch is "trust it because
you can read exactly what it does with your usage data." A permissive licence would allow a
closed-source fork that quietly adds tracking; GPL-3.0 makes that a licence violation rather
than just a bad look. See [`LICENSE`](./LICENSE).
