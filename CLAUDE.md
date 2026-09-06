# Mindful Scroll (fkc-zkc)

Android FOSS app that interrupts compulsive scrolling via an `AccessibilityService`, and is
moving from pure friction towards helping the user understand *why* they opened the app.
Everything is on-device: no network permission, no analytics, no telemetry, ever.

## How we work

The developer gives a prompt; Claude implements it **on a branch** and opens a **PR**. Never commit
directly to `main`.

1. Branch off `main` with a short descriptive name.
2. Implement, and verify locally before pushing (see *Local loop* — it is fast, use it).
3. Open the PR, then **enable auto-merge**: `gh pr merge <n> --auto --squash`.
   Squash, not merge — `--merge` is rejected outright (see *Branch rules* below).
4. **Watch the GitHub Actions runs until they finish**, and confirm the PR actually merged.
   A PR left open, or auto-merge that silently did nothing, is an unfinished task.
5. If CI fails, fix it on the same branch and keep watching. Do not hand back a red PR.

### Watch for review comments too

While watching a PR, poll for new comments from the developer and act on them — a comment left
while CI runs must not be merged straight past. Both kinds matter: conversation comments and
inline review comments on specific lines.

```sh
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)   # capture before the wait loop starts
# conversation tab
gh pr view "$PR" --json comments \
  --jq ".comments[] | select(.createdAt > \"$SINCE\") | \"\(.author.login): \(.body)\""
# inline review comments, which the above does NOT include
gh api "repos/{owner}/{repo}/pulls/$PR/comments" \
  --jq ".[] | select(.created_at > \"$SINCE\") | \"\(.user.login) @ \(.path):\(.line): \(.body)\""
```

Fold both into the same loop that waits on CI, so one poll covers checks, comments and merge
state. When something arrives:

- **A concrete change request** → make it on the same branch and push. CI re-runs and
  auto-merge picks it up; say what was changed and where.
- **A question** → answer it, in the PR thread if it is about the code under review.
- **Something that changes the goal of the task** → stop. Disable auto-merge
  (`gh pr merge "$PR" --disable-auto`) so a half-right change cannot merge itself while the
  question is open, then ask.

If a comment arrives after the PR has already merged, follow up in a new branch and PR rather
than pushing to `main`.

### Subagents share this working tree

A subagent launched into this repo gets the **same checkout**, not a copy. One running
`git checkout main` will switch the branch out from under whatever else is in progress, and
work in flight ends up committed to the wrong branch.

Either give the agent an isolated worktree (`isolation: "worktree"`), or do not run one
concurrently with your own uncommitted changes here. Before committing after any concurrent
agent has run, check `git branch --show-current` is still what you expect.

### Ask first, or just ship?

- **Well-specified and deterministic** → implement it, open the PR, get it merged. Do not
  stop to ask permission for work that was clearly described.
- **The end state is genuinely open** ("make the report nicer", a design with several valid
  shapes, a decision that changes what gets built) → implement a reasonable first pass, then
  **ask whether it is enough before opening the PR**. Do not guess at a finish line and
  present it as done.

Report honestly. If something is unverified, say so. Do not describe a green tick as proof of
something it does not prove — that mistake has cost this project several days.

### PR hygiene

- One PR per coherent change. Stacked branches are fine; retarget to `main` once the base merges.
- The workflows trigger on `pull_request` with `branches: [main]`. A PR opened against any
  other base **runs no CI at all**, and retargeting fires an `edited` event that the default
  trigger types ignore — close/reopen the PR to force a run.
- `delete_branch_on_merge` is on, so merged branches clean themselves up.

## Local loop

There is a full local toolchain. Use it — it turns a 5–15 minute CI round into seconds.

```sh
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"          # matches CI; system JDKs are 11 and 21
export ANDROID_HOME="$HOME/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"
emulator -avd mindful -no-window -no-audio -no-boot-anim &   # AVD matches CI exactly: API 30, google_apis, x86_64, pixel_5
```

| Command | Typical | Catches |
|---|---|---|
| `./gradlew compileDebugKotlin` | 1.5–12s | compile errors |
| `./gradlew test` | ~10s warm | unit + Robolectric |
| `./gradlew assembleRelease` | ~1m30s | **R8 runs** — minification failures, keep-rule mistakes |
| `./gradlew connectedDebugAndroidTest` | ~30s warm | device behaviour |

**Never** put `org.gradle.java.home` in the repo's `gradle.properties` (committed — a
machine-local path breaks CI) or in `~/.gradle/gradle.properties` (global — forces JDK 17 on
every other project on this machine). Export per-command. `local.properties` holds only
`sdk.dir` and is gitignored.

## CI

### Branch rules

`main` is guarded by **two overlapping mechanisms**, and both apply — the stricter wins:

- A **repository ruleset** (Settings → Rules), which is the one with teeth.
- Classic **branch protection**, requiring all three checks and `strict` (branch must be up to
  date with `main`).

What that means in practice:

- **Squash only.** The ruleset allows `squash` and `rebase`, not merge commits, so
  `gh pr merge --merge` fails with *"Merge method merge commits are not allowed"* no matter
  what the repo's own merge-method settings say.
- **Commits must be signed.** This is why squash is the one that actually works: GitHub signs
  the single commit it creates. A rebase would replay the branch's own unsigned commits onto
  `main` and be rejected.
- **Review threads must be resolved** before merging. An unresolved comment blocks the merge
  even with every check green.
- **Nobody bypasses** — the ruleset has no bypass actors.
- Because classic protection sets `strict`, a branch goes `BEHIND` the moment another PR
  merges. Merge `main` into the branch and push; CI re-runs and auto-merge picks it up again.

`main` is protected and **requires all three checks below to pass** before anything merges.
That is what makes `--auto` meaningful: without required checks, GitHub auto-merge has nothing
to wait for and merges immediately. `strict: true` is set, so a branch must also be up to date
with `main` — if `main` moves while CI runs, rebase or merge `main` in and let it re-run.
Admins are not enforced against, so the developer can still push
directly to `main` when needed.

Three checks, all required to be green before merge:

- **build** — `./gradlew build` + unit tests. Also archives R8's `mapping/usage/seeds` as the
  `r8-release-mapping` artifact.
- **instrumented-tests (debug)** and **instrumented-tests (release)** — the same suite on an
  emulator, twice. The release run is not a duplicate: it installs the R8-minified APK and
  answers the two questions only a runtime check on the shipped artifact can — does the system
  still resolve the full accessibility event mask, and does the overlay window still draw.

Releases are tag-triggered (`git tag v0.3.0 && git push origin v0.3.0`), signed, and published
as a GitHub Release for Obtainium. A missing keystore must keep producing an **unsigned** APK —
that is the loud failure the publish workflow's verify step depends on.

## The thing to understand about this codebase

Every serious bug here has failed **silently**: a wrong `<meta-data>` name, an overlay built on
the wrong context, a threshold only ever evaluated inside a scroll handler. Each looked exactly
like success from every counter available. So:

- **Never swallow a failure.** No bare `runCatching {}` without an `onFailure` that logs. Route
  failures through `ServiceDiagnostics` so they surface on the in-app Diagnostics screen and
  name themselves, the way `lastOverlayError` and `lastOverlayRender` do.
- **Distinguish "attempted" from "worked."** `addView()` returning is not evidence a window
  appeared — hence *overlay windows added* vs *overlay windows actually drawn*. Keep that
  discipline for anything new.
- **Assert exactly, not loosely.** The event mask is asserted `== 0x1820`, not `!= 0`: a
  partially-resolved mask fails just as quietly as an empty one.

### Traps that have already cost time

- **The overlay's context must be the running `AccessibilityService`**, not the application
  context — the window token lives there. `AccessibilityWindow` owns all of this; reuse it
  rather than writing a second copy.
- **`adb screencap` does not capture `TYPE_ACCESSIBILITY_OVERLAY` windows.** They are provably
  on screen and come out invisible. "I couldn't see it" ≠ "it didn't render". Use
  `OverlayPreviewActivity` (debug-only) to review overlay UI visually.
- **Compose `LazyColumn` fires neither `TYPE_VIEW_SCROLLED` nor `TYPE_WINDOW_CONTENT_CHANGED`.**
  Foreground *time* is the trustworthy signal; scroll count is best-effort only.
- **Our own overlay windows raise `TYPE_WINDOW_STATE_CHANGED` under this package.** Unguarded,
  the service reads that as the user leaving the monitored app.
- **Room migrations**: copy the DDL verbatim from Room's exported schema in `app/schemas/`,
  never write it by hand. Room validates on open, and every install holds the only copy of that
  person's history — `allowBackup="false"`, no server. No `fallbackToDestructiveMigration`.
- **Reading `usage.txt`**: a trailing `:` means "class kept, some members removed"; only lines
  without a colon are fully-removed classes. A class listed as removed may have been *inlined* —
  check `mapping.txt` before concluding anything broke.
- **Instrumented tests**: enabling the accessibility service must force a real off → on
  transition (writing an already-set value changes nothing and leaves it wedged in "Binding
  services"). `settings put ... ''` fails with *Bad arguments* — use `settings delete`. An
  `@After` that throws replaces the `@Before` failure that caused it, so guard teardown with
  `if (!::harness.isInitialized) return`.

## Out of scope, permanently

iOS, browser extension, cross-device sync or accounts, social features, algorithmic feed
replacement — and **any gamification** (streaks, points, badges), which would reintroduce the
exact variable-ratio mechanic this app exists to interrupt.
