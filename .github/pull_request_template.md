<!-- What changed and why. Link the issue: "Closes #NN". -->

### Checklist

- [ ] Verified locally (`./gradlew test`, plus `assembleRelease` / `connectedDebugAndroidTest` where relevant). If something is unverified, the PR says so.
- [ ] **User-facing copy** (reflection, stats, pause, prompt, notifications) checked against the [neutral-tone checklist](../blob/main/CONTRIBUTING.md#writing-user-facing-copy): facts, not verdicts; no failure words, praise, "should", urgency or scores.
- [ ] Failures route through `ServiceDiagnostics` rather than being swallowed.
- [ ] Schema changes: migration DDL copied from Room's exported schema in `app/schemas/`, with a migration test.
