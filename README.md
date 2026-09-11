# Mindful Scroll

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/jcoelho93/fkc-zkc/badge)](https://scorecard.dev/viewer/?uri=github.com/jcoelho93/fkc-zkc)

A free, open-source Android app that helps you notice and interrupt compulsive
infinite-scrolling in apps like Instagram, Reddit, Facebook, TikTok and X/Twitter.

It blocks nothing by force. It watches time and scrolling in the apps *you* choose, asks
what you came for, and shows a pause screen when you cross a threshold *you* set.

No account, no cloud sync, no analytics, no ads — and no internet permission, so it
*cannot* send data anywhere. See [Privacy & permissions](#privacy--permissions).

---

## What it does today

1. **Onboarding** — grant two permissions, pick which installed apps to monitor.
2. **Intention prompt** — when a monitored app comes to the foreground, a small strip at the
   bottom asks *"What are you hoping to find?"* with chips (Connection, Entertainment,
   Distraction, Habit, Checking something specific) and an optional note. It never takes a tap
   or a keystroke from the app underneath; ignore it and it goes away. Turn it off in Settings.
3. **Thresholds** — per app, default **40 scrolls or 10 minutes of continuous foreground
   time**, whichever comes first. A scroll is one swipe: events are grouped until 800 ms of
   stillness, so a single fling counts once, and content changing on screen while you are not
   touching it does not add up.
4. **Pause screen** — a full-screen overlay built around *urge-surfing*: a slow breathing ring
   and *"notice the urge to keep scrolling — it usually peaks and fades within a minute or two
   if you watch it instead of acting on it."* After a configurable interval (default 20s) it
   recalls what you said at step 2 and asks whether you got it — *Yes / Kind of / Not really*.
   Then: close the app, or keep scrolling. Continuing gives you 5 more minutes before the pause
   returns.

   **Neither exit is ever gated.** Both buttons are live from the first frame; the interval
   reveals the question, it does not unlock anything. The old countdown and type-a-phrase gates
   were removed because raising the cost of continuing never surfaced *why* you opened the app.
   Nothing here is scored, ranked or totalled — "Not really" is not a failure, and there are no
   streaks.
5. **Dashboard** — time in app, times opened and scrolls, for today and the past 7 days,
   plus how often you closed vs. continued. Time and opens are always separate figures, because
   less time in an app can hide a checking habit that hasn't changed at all.
6. **Grayscale (optional)** — per app, show it in black and white while it is open; colour
   comes back the moment you leave. Of everything this app does, reduced colour is the
   intervention with the most consistent phone-specific trial evidence. It needs one
   permission Android only lets you grant **from a computer**
   (`adb shell pm grant com.mindfulscroll.app android.permission.WRITE_SECURE_SETTINGS`), and
   until then the switches do nothing. It uses Android's own colour correction, so while it is on it
   covers the whole screen, not just the app's own window, and it never touches colour
   correction you already had on.
7. **Settings** — a short menu grouped by what each setting affects: the question when you open
   an app, the pause, and your apps. Each row shows its current value and opens its own page,
   including per-app thresholds set with sliders and a **Diagnostics** screen for when detection
   misbehaves.

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

One more is **optional**, and only matters if you want grayscale:

| Permission | Why it is needed | Notes |
|---|---|---|
| **Modify secure system settings** (`WRITE_SECURE_SETTINGS`) | Switch Android's colour correction to grayscale while a monitored app you picked is open, and back when you leave it. | Android **cannot grant this from the phone** — only from a computer, with `adb shell pm grant com.mindfulscroll.app android.permission.WRITE_SECURE_SETTINGS`. Without it, grayscale does nothing and the rest of the app is unaffected. The app writes exactly two settings with it (`accessibility_display_daltonizer_enabled` and `accessibility_display_daltonizer`), never overwrites colour correction you turned on yourself, and puts the old values back. |

### Every permission in the shipped APK

Not just the two we ask you for — the complete list, including what libraries add, because that
is what you will see if you check with `adb shell dumpsys package com.mindfulscroll.app`.

| Permission | Source | What it allows |
|---|---|---|
| `PACKAGE_USAGE_STATS` | ours | Foreground time for your own stats |
| `RECEIVE_BOOT_COMPLETED` | ours | Re-arm the daily maintenance job after a reboot, and undo grayscale if the phone restarted while it was on |
| `WRITE_SECURE_SETTINGS` | ours | Optional grayscale. Declared, but **held only if you grant it over adb** — see above |
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
- no unexpected native libraries;
- a release is signed by exactly one certificate, the one [published below](#dont-trust-us-check-it);
- **zero known trackers**, by [Exodus Privacy](https://reports.exodus-privacy.eu.org/)'s own
  scanner and tracker database rather than ours. The scan is only reported as clean after the
  same scanner has flagged a planted list of real tracker classes, so a scan that silently read
  nothing cannot pass.

These are claims about *the artifact*, not about the source it was built from. Verifying that the
APK was built from the commit it claims — reproducible builds — is
[issue #15](../../issues/15) and is not done yet.

### Don't trust us, check it

Everything above can be checked against **the copy on your own phone**, without reading any code.

**Release signing certificate (SHA-256).** Every release is signed with this certificate, and the
release build fails if the APK's certificate is anything else:

<!-- release-cert-sha256 -->
`58e5e0954224dabfe97ecce55d2df279d8bad9199124b35912e9b095d2eca9f0`

The same value in the colon-separated form some tools print:

```
com.mindfulscroll.app
58:E5:E0:95:42:24:DA:BF:E9:7E:CC:E5:5D:2D:F2:79:D8:BA:D9:19:91:24:B3:59:12:E9:B0:95:D2:EC:A9:F0
```

If your installed copy was signed by anything else, it did not come from this project.

**On the phone alone:** [AppVerifier](https://github.com/soupslurpr/AppVerifier) shows the signing
certificate of any installed app. Copy the two lines above, share them to AppVerifier, and it
compares them against the installed Mindful Scroll.

**With a computer**, using [`adb`](https://developer.android.com/tools/adb) and `apksigner` (both
in the Android SDK's platform-tools and build-tools; no Android development needed):

```sh
# 1. Copy the app off your phone, exactly as installed.
adb shell pm path com.mindfulscroll.app        # prints package:/data/app/…/base.apk
adb pull /data/app/…/base.apk mindful-scroll.apk   # paste the path it printed

# 2. Who signed it? Compare the SHA-256 line with the fingerprint above.
apksigner verify --print-certs mindful-scroll.apk

# 3. Which permissions does it hold? Compare with the table above.
adb shell dumpsys package com.mindfulscroll.app | grep -A12 'requested permissions:'

# 4. Can it reach the internet? No output means INTERNET is not declared.
adb shell dumpsys package com.mindfulscroll.app | grep 'android.permission.INTERNET'
```

For the full report — the accessibility service's settings, networking code, trackers — run the
same script the release runs, on the APK you just pulled. It needs the Android SDK build-tools
(with `ANDROID_HOME` pointing at the SDK) and Docker:

```sh
git clone https://github.com/jcoelho93/fkc-zkc && cd fkc-zkc
REQUIRE_SIGNED=1 .github/scripts/verify_release_apk.sh /path/to/mindful-scroll.apk report.txt
```

Re-check after updates rather than once. Each update is a new APK, and the checks above only
describe the one you checked.

### What this does not protect against

The checks above are strong for what they cover. The limits are just as important:

- **The signing key being stolen.** Whoever holds it can sign an APK that Android and every
  check above accept as a genuine update. The published fingerprint cannot tell a stolen key
  from the real one; only reproducible builds, still open in [#15](../../issues/15), would let
  you confirm an APK was built from public source.
- **The maintainer or the build.** Until builds are reproducible, you are trusting that GitHub
  Actions built the APK from the commit its release names. The report checks the APK's
  *behaviour*, not where it came from.
- **A future version.** An accessibility service *can* be changed to read screen content. This
  one is not, today, and the release fails if it is. But a future maintainer could change the
  check and the setting together. That is why the checks run on each APK, and why re-checking
  on update matters.
- **A malicious dependency.** Every library in the app runs with the app's permissions. No
  `INTERNET` permission means none of them can send anything off the device, but a bad library
  could still misbehave on the device itself.
- **The phone.** A compromised OS, or another app with more access than this one, is outside
  anything this app can control.

Found a security problem? See [SECURITY.md](SECURITY.md).

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
  threshold is the reliable path. Where it does work it errs low: a swipe made while the feed is
  still moving from the last one merges into it, and so do swipes over content that never stops
  changing.
- One continuous session per app at a time; no cross-app "total scrolling today" limit.
- Session, grace-period and the scheduled time check live in memory in the accessibility service
  (a coroutine `delay()`). They are not restored if the process is killed mid-session — the next
  scroll or foreground change re-arms them, but a session that never scrolls and outlives a
  process death won't trigger.
- Nothing yet reads back the captured intentions — they are stored, but not surfaced.
- No launcher badge or notification summarising the day.
- Grayscale switches on the same foreground changes the rest of the app uses. Anything Android
  reports as leaving the app brings colour back, and it returns when the app next reports itself
  in front.

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
