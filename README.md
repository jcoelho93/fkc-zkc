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
   prompt *"What are you looking for right now?"*, and two choices: close the app, or continue.
   Both are available immediately — the countdown and typed-phrase gates were removed, because
   raising the cost of continuing never surfaced *why* you opened the app. Continuing gives you
   5 more minutes before the pause screen returns.
5. **Dashboard** — scrolls and time in app for today and the past 7 days, plus how often you
   closed vs. continued.
6. **Settings** — toggle apps, edit thresholds, and a **Diagnostics** screen
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

- **No internet permission.** `android.permission.INTERNET` is never declared, so Android itself
  makes network access impossible — the app cannot phone home even if its code tried to.
- Everything lives in a local Room (SQLite) database on your device.
- Cloud backup and device transfer are disabled for the app's data
  (`android:allowBackup="false"`).
- No third-party trackers, ad SDKs, analytics or crash reporting.

Two permissions are required, both explained in plain language during onboarding:

| Permission | Why it is needed | Notes |
|---|---|---|
| **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE`) | Notice scroll and window events in the apps you chose, and draw the prompt and pause screen. | Does **not** read screen content (`canRetrieveWindowContent` is `false`). Reacts only to scroll and window-change events. |
| **Usage access** (`PACKAGE_USAGE_STATS`) | Read how long monitored apps were in the foreground, for your own stats. | A "special access" permission: Android only lets an app link you to the system settings screen. Never auto-granted. |

### Every permission in the shipped APK

Not just the two we ask you for — the complete list, including what libraries add, because that
is what you will see if you check with `adb shell dumpsys package com.mindfulscroll.app`.

| Permission | Source | What it allows |
|---|---|---|
| `PACKAGE_USAGE_STATS` | ours | Foreground time for your own stats |
| `RECEIVE_BOOT_COMPLETED` | ours | Re-arm the daily maintenance job after a reboot |
| `WAKE_LOCK` | WorkManager | Finish a background job before the device sleeps |
| `ACCESS_NETWORK_STATE` | WorkManager | *Read* connectivity state to schedule jobs. It cannot transmit anything, and without `INTERNET` there is nothing to transmit with |
| `FOREGROUND_SERVICE` | WorkManager, Lifecycle | Run a job in the foreground |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core | Self-defined, signature-level; keeps the library's own receivers un-exported |

`ACCESS_NETWORK_STATE` is the one worth explaining rather than hiding: it looks like a network
permission and is not one. It reads whether you are on wifi; it opens no connections. `INTERNET`
is the permission that would let anything leave your device, and it is absent.

The prompt and the pause screen are drawn as `TYPE_ACCESSIBILITY_OVERLAY` windows rather than
`SYSTEM_ALERT_WINDOW`, so **no "draw over other apps" permission is requested** either.

### Don't take our word for it

Every release ships a **`verification-report.txt`**, generated from that exact APK by
[`verify_release_apk.sh`](.github/scripts/verify_release_apk.sh). It checks the claims above
against the built binary — after minification, which is what you install — and the same check
gates every pull request:

- the permission list is **exactly** the table above, so a dependency bump that adds one fails the build;
- `INTERNET` is absent;
- the accessibility service declares `canRetrieveWindowContent=false`, `canRequestFilterKeyEvents=false`, and an event mask of exactly `0x1820` (scroll, window-content, window-state — nothing else);
- no networking code (`Socket`, `HttpURLConnection`, OkHttp, Retrofit, `WebView`) survives in the compiled app;
- no unexpected native libraries.

These are claims about *the artifact*, not about the source it was built from. Verifying that the
APK was built from the commit it claims — reproducible builds — is
[issue #15](../../issues/15) and is not done yet.

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
[Building](CONTRIBUTING.md#building).

## Troubleshooting

If interruptions never fire, **Settings → Diagnostics** shows live detection state: whether
the service is connected, which apps are monitored, and how many events actually arrived.
[How to read it](CONTRIBUTING.md#diagnostics).

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
- Nothing yet reads back the captured intentions — they are stored, but not surfaced.
- No launcher badge or notification summarising the day.

**Permanently out of scope:** iOS, browser extension, cross-device sync or accounts, social or
comparison features, algorithmic feed replacement, and any gamification (streaks, points,
badges) — which would reintroduce the exact mechanic this app exists to interrupt.

## Contributing

How detection works, how to build and test, what CI enforces and how releases are cut all
live in **[CONTRIBUTING.md](CONTRIBUTING.md)**.

## License

GPL-3.0 — deliberately strong copyleft. Any derivative or redistributed version must stay open
source under the same terms, which matters for a tool whose entire pitch is "trust it because
you can read exactly what it does with your usage data." A permissive licence would allow a
closed-source fork that quietly adds tracking; GPL-3.0 makes that a licence violation rather
than just a bad look. See [`LICENSE`](./LICENSE).
