# Contributing to Kavach

---

## Before anything else

Kavach is a security tool. That changes the bar for a pull request:

1. **A feature that is 90% implemented is a bug, not a feature.** Half a userspace TCP
   stack, a blocklist path that can silently fail open, a policy branch with no test — these
   are worse than not having the feature, because the user believes they are protected.
2. **If it cannot be honest, it does not ship.** Every limitation is documented in
   `ARCHITECTURE.md`, in `README.md`, *and* in the app's own UI. New limitations get the same
   treatment. Do not quietly narrow what the app actually does.
3. **`core/` stays free of Android imports.** That constraint is what makes the policy engine,
   the DNS parser and the packet builder testable as plain JVM code. Do not break it for
   convenience.

---

## Setup

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
git clone https://github.com/akshat029/Kavach.git
cd Kavach
gradle wrapper --gradle-version 8.9   # once; Android Studio does this for you
./gradlew testDebugUnitTest
```

`gradle-wrapper.jar` is not committed — this repository is text-only. `./gradlew` detects a
missing jar and tells you how to create one rather than failing with a stack trace.

---

## Before you open a pull request

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

All three must pass. CI runs the same three.

---

## Tests

Anything in `core/` or `vpn/` that is pure logic needs a test. The existing suite sets the
standard:

- **Verify independently.** `PacketsTest` implements RFC 1071 checksums *separately* from
  `Bytes.Checksum`, so a bug in the production code cannot validate itself. Do not test a
  function by calling it twice.
- **Test the boundaries, not the happy path.** Odd-length payloads, empty payloads, zero
  checksums, truncated packets, sibling domains that must *not* match, compression pointers
  that must be rejected. The happy path rarely breaks.
- **Keep it on the JVM.** No Robolectric, no emulator. The whole suite should run in seconds.

If you add a branch to `PolicyEngine`, add its row to the precedence table in
`ARCHITECTURE.md` **and** a test asserting its position relative to the branches on either
side. Precedence bugs are silent and dangerous.

---

## Blocklists

Do not edit `blocklists/trackers.txt` or `blocklists/ads.txt` — they are generated and your
change will be overwritten by the nightly job.

Edit `blocklists/seed-trackers.txt` or `blocklists/seed-ads.txt` instead, then:

```bash
python3 scripts/compile_blocklists.py --offline
```

**Which file?** Trackers = analytics, attribution, session replay, crash telemetry.
Ads = exchanges, mediation SDKs, creative CDNs. They answer to separate user-facing toggles,
so putting an entry in the wrong one makes a toggle lie.

Adding a domain that breaks an app is the most common way to make this project worse. If you
are not sure, say so in the pull request and it can go in behind a test.

Never add anything that would break connectivity checks, app stores, the DoH resolvers, or
Kavach's own update channel. There is a `NEVER_BLOCK` set in the compiler for exactly this,
and it should grow when you find a new one.

---

## Code style

- Kotlin official style, 4 spaces, ~100 column soft limit.
- **Comments explain *why*, never *what*.** `// increment i` is noise. `// Empty-but-present
  is the normal case on CI` is the reason someone will not re-break it next month.
- Public API gets KDoc. Non-obvious internals get a comment explaining the trade-off.
- No new dependency without a reason in the pull request description. The dependency list is
  short on purpose and every addition is code you are asking users to trust.

---

## Commits

Conventional commits: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`, `ci:`.

One logical change per commit. A commit that both renames a package and fixes a bug is a
commit nobody can review or revert.

---

## Reporting a bug

Include:

- Android version, OEM and device model — managed-profile and VPN behaviour varies enormously
  between OEMs and this is usually the first useful clue
- Kavach version, from Settings
- What you expected, what happened
- **Relevant lines from the Log screen.** Redact the domains you do not want to share, but
  keep the verdict and reason — those are what identify the failing branch

If an app broke: the app's package name, and which zone it was in. "App X does not work" is
almost always "App X needs domain Y allowed", and the log says which Y.

---

## Security issues

Do not open a public issue for a vulnerability. Use GitHub's private security advisory form
on the repository instead.
