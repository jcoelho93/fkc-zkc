# Security

Mindful Scroll asks for an accessibility service, the most powerful permission a normal Android app
can hold. A security problem in it matters more than in most apps of its size, so reports are
welcome and taken seriously.

## Reporting a vulnerability

Report it privately through GitHub:
**[Security → Report a vulnerability](https://github.com/jcoelho93/fkc-zkc/security/advisories/new)**.

Only the maintainer can read the report, and the fix can be discussed and prepared in a private
advisory before anything is public. Please don't open a public issue for something exploitable.

Useful to include:

- the release you installed (Android's App info screen shows it) and your Android version;
- what an attacker could do, and what they would need first (a second app, adb, physical access);
- steps to reproduce, or a proof of concept.

This is a one-maintainer project, with no bounty and no fixed response time. A fix ships as a
normal signed release, and the advisory is published once it is out.

## What counts

In scope, for example:

- the accessibility service receiving or keeping more than the README says (it declares
  `canRetrieveWindowContent="false"` and three event types);
- any way for data to leave the device, or for another app to read this app's database;
- the prompt or pause overlay being usable by another app to capture taps or text;
- a release APK that differs from what [`verify_release_apk.sh`](.github/scripts/verify_release_apk.sh)
  reports, or is signed by a certificate other than the one
  [published in the README](README.md#dont-trust-us-check-it);
- weaknesses in the build or release workflows that could put a different APK into a release.

Out of scope: problems that need an already-compromised phone or root, and the limits listed under
[*What this does not protect against*](README.md#what-this-does-not-protect-against), which are
known and documented rather than hidden.

## Supported versions

Only the latest release gets fixes. Updating in place keeps your settings and history, so there is
no reason to stay on an older one.
