# Kavach

**कवच** — *armour*

A per-app DNS firewall and isolation manager for Android. No servers, no account, no
telemetry, no ads.

> **Status: `0.1.0-alpha`.** Phase 1 is complete, tested, and honest about its limits. It
> has not yet run on a wide device matrix, and managed-profile behaviour varies enormously
> between manufacturers. Treat this as a working alpha, not a finished product.

---

## The idea

The original goal was a **box**: install your apps inside Kavach, and Kavach blocks the ads
and stops one app leaking your data to another. A main house, with a kitchen inside it, and
you take dishes from the kitchen into your guest room without anything following you in.

The box is the right mental model. The wrong part was what the box would be *made of*.

An app that runs other apps inside itself — *app-level virtualization*, the technique behind
Parallel Space and VirtualApp — has to load every guest app into its own process. The
consequence is documented plainly:

> In app-level virtualization, the host app shares the same UID with all guest apps and
> relies on hooking.
>
> — Song et al., *ACM CCS 2021*

Android's entire security model is that **each app gets its own Linux UID**, enforced by the
kernel and SELinux. Loading your banking app and a random game into one shared UID, and then
re-implementing the wall between them with hand-written userspace hooks, is not a stronger
box. It is the same box with the kernel taken out of it — a strict downgrade for the exact
thing the box was for.

So Kavach keeps the box and changes what it is made of.

### What the box is made of instead

| Plane | Boundary | Enforced by | What it actually stops |
|---|---|---|---|
| **Isolation** | Android managed work profile | The kernel — a second real Linux user | App A reading App B's files, clipboard, accounts and contacts |
| **Network** | Per-app DNS firewall over `VpnService` | Kavach's policy engine | Apps reaching ad networks, trackers and analytics endpoints |

Both are real boundaries backed by something stronger than Kavach itself.

### The correction that shaped everything else

The leak you are worried about is usually **not** `App A → App B` on the filesystem. Android
already stops that. The real leak is:

```
App A ──► ad SDK ──► ad network ──┐
                                  ├──► same advertising ID ──► one profile of you
App B ──► ad SDK ──► ad network ──┘
```

That join happens on someone else's server, from two connections your phone made willingly.
Isolating storage does nothing about it. You stop it by refusing the network call — which is
what the Network plane does, per app, with a reason recorded for every decision.

---

## Zones

Every app sits in one of four zones. A zone sets sane defaults; you can override anything
per app.

| Zone | Network mode | What it means |
|---|---|---|
| 🏦 **Vault** | Allowlist only | Banking and identity. Only the app's own servers are reachable. |
| 👥 **Social** | Filtered | Works normally. Trackers and ad networks are sinkholed. |
| 🎮 **Offline** | No network | No network at all. Every lookup is refused. |
| 🌐 **Open** | Unfiltered | Kavach does not touch this app. |

New apps land in **Social** by default. That default is configurable in Settings.

---

## What it does

**Per-app rules.** Allow or block any domain for one app, or globally. A rule on
`example.com` covers `api.example.com` but deliberately **not** `notexample.com` — matching
walks label boundaries, never raw string suffixes. The most specific rule wins.

**Learning mode.** Records what *would* have been blocked without blocking it. Turn it on
for a day, look at the log, then build a Vault allowlist from evidence instead of guesswork.
The log shows `would block: Known tracker (doubleclick.net)` rather than a silent allow.

**Activity log.** Every DNS question, the app that asked, the verdict and the reason.
Searchable, filterable to refused-only, capped at 20,000 rows, deletable, and switchable off
entirely. It never leaves the device.

**Encrypted resolver.** Allowed queries go out over DNS-over-HTTPS to Cloudflare, Quad9,
Mullvad or AdGuard. **There is no plaintext fallback.** If the resolver is unreachable,
Kavach returns SERVFAIL rather than quietly leaking your queries to whatever DNS server the
network handed you. Blocked queries are answered locally and never forwarded at all.

**Isolation.** Optionally provisions an Android managed profile and seals the cross-profile
boundary — sharing and clipboard both blocked in both directions.

---

## Honest limitations

These are in the app's own UI as well as here, because a security tool that oversells itself
is worse than no security tool.

1. **This is a DNS-layer firewall, not a packet firewall.** An app that connects straight to
   a hard-coded IP address never asks a question, so it cannot be refused. Closing this is
   Phase 1.5 and it is the single most valuable thing left to build.
2. **No cosmetic filtering.** Kavach cannot hide a page element the way uBlock Origin can.
   If an app serves its ads from its own first-party domain, blocking that domain breaks the
   app. Ads inside YouTube and Instagram are served this way and will not disappear.
3. **Your DoH resolver sees your queries.** Encryption moves trust; it does not remove it.
   The resolver learns the domain and your IP. It does not learn which app asked, because
   Kavach does not tell it.
4. **A work profile is not network anonymity.** It stops file and clipboard access between
   sides. It does not stop two apps being correlated by the same IP address, the same login,
   or possibly the same advertising ID. Whether every OEM issues a genuinely separate ad ID
   per profile is **unverified** and tracked as a test-matrix item in `ROADMAP.md`.
5. **Not on Google Play, by design.** Play policy forbids using the VPN slot to filter ads in
   other apps. Sideload from the CI artifact or a GitHub Release.
6. **Alpha-grade OEM coverage.** Work-profile provisioning fails or behaves oddly on some
   Realme, Oppo and Xiaomi builds. Everything else in Kavach still works when it does.

---

## Getting a build

There is no Android SDK required on your side. **CI builds the APK.**

1. Go to the **Actions** tab → **Build** workflow.
2. Open the most recent successful run (or press **Run workflow** to start one).
3. Download the **`kavach-debug-apk`** artifact and install the APK inside.

The debug APK installs alongside a release build — it uses the application id
`com.kavach.app.debug`.

The job summary of every run prints the size and SHA-256 of each APK, so you can verify what
you downloaded.

### Optional: signed release builds

A release APK is only signed if these four repository secrets exist. Without them CI still
succeeds, prints a notice, and produces an unsigned release APK — install the debug one.

| Secret | Value |
|---|---|
| `KAVACH_KEYSTORE_BASE64` | Your `.jks` keystore, base64-encoded |
| `KAVACH_KEYSTORE_PASSWORD` | Keystore password |
| `KAVACH_KEY_ALIAS` | Key alias inside the keystore |
| `KAVACH_KEY_PASSWORD` | Key password |

### Building locally

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
git clone https://github.com/akshat029/Kavach.git
cd Kavach
gradle wrapper --gradle-version 8.9   # once; Android Studio does this for you
./gradlew testDebugUnitTest lintDebug assembleDebug
```

`gradle-wrapper.jar` is a binary and is deliberately **not** committed — this repository is
text-only. `./gradlew` detects the missing jar and tells you exactly how to create one
instead of failing with a `ClassNotFoundException`.

---

## First run

1. Open Kavach and press **Turn on shield**. Android will ask you to approve a VPN
   connection; that dialog is how `VpnService` works and cannot be skipped.
2. Go to **Apps**. Everything starts in Social.
3. Move your banking apps to **Vault**, then turn on **Learning mode** for each of them.
4. Use them normally for a day.
5. Come back to the app's detail screen. Under *Domains this app has asked for*, press
   **Allow** on the ones it genuinely needs, then turn Learning mode off.

That sequence turns Vault from "refuses everything" into a real allowlist without guessing.

---

## Project layout

```
app/src/main/java/com/kavach/app/
├── core/            Pure JVM. No Android imports. Fully unit-tested.
│   ├── model/       Zone, NetworkMode, Verdict, AppInfo
│   ├── policy/      PolicyEngine — the ten-branch precedence table
│   └── blocklist/   DomainMatcher, repository, nightly update worker
├── vpn/             TUN loop, IPv4/IPv6 UDP codec, DNS codec, DoH, UID attribution
├── data/            Room database and repositories
├── isolation/       Managed-profile provisioning and boundary sealing
├── ui/              Compose screens: dashboard, apps, app detail, log, isolation, settings
└── util/            Installed-app enumeration

blocklists/          seed-*.txt are hand-maintained; the rest is generated
scripts/             compile_blocklists.py
.github/workflows/   build.yml, update-blocklists.yml
```

The dependency direction is one-way: `ui → data → core` and `vpn → core`. `core/` imports
nothing from Android, which is why the interesting logic can be tested in seconds on the JVM.

---

## Blocklists

199 tracker domains and 219 ad domains ship bundled in the APK, so a fresh install is
protected before it has ever reached the network. A nightly workflow merges the
hand-maintained seeds with AdGuard and EasyList feeds and republishes the compiled lists.

The compiler follows three rules, in priority order:

1. **Never publish a smaller list than the seed.** A feed that 404s, rate-limits or returns
   an error page degrades to *no change*, never to *user unprotected*.
2. **Byte-stable output for identical input.** No timestamp in the header, everything sorted,
   so `git diff --quiet` is a truthful "nothing changed".
3. **Cosmetic filter rules are dropped.** A DNS layer cannot hide a page element, and
   pretending otherwise fills the list with entries that silently do nothing.

To change a list, edit `blocklists/seed-trackers.txt` or `blocklists/seed-ads.txt` — never
the compiled files — then run:

```bash
python3 scripts/compile_blocklists.py --offline
```

---

## Testing

```bash
./gradlew testDebugUnitTest
```

60+ pure-JVM tests, no emulator, no Robolectric. Two of them are worth knowing about:

- **`PolicyEngineTest`** covers all ten precedence branches. A wrong branch here does not
  crash — it quietly allows traffic while the UI reports the app is protected. That failure
  mode is worse than having no firewall, so the coverage is exhaustive rather than
  representative.
- **`PacketsTest`** implements RFC 1071 checksums *independently* of the production code.
  Validating output with the same routine that produced it would pass even if that routine
  were wrong, and a bad checksum produces packets the kernel discards silently — the hardest
  class of VPN bug to diagnose.

---

## Roadmap

| Phase | Status | Summary |
|---|---|---|
| 1 — DNS boundary | ✅ shipped | Everything described above |
| 1.5 — Packet blocking | next | Userspace TCP/UDP relay, IP rules, SNI recovery, kill-switch |
| 2 — Identity plane | planned | Per-zone advertising ID, permission clamping, "what can this app see about me?" |
| 3 — Fusion | planned | One zone assignment drives isolation, network and identity at once; the Data Passport prompt |
| 4 — Durability | planned | Policy export/import, shareable zone profiles, Obtainium releases, reproducible builds, Hindi |

Full detail, including what is explicitly *not* planned and why, is in `ROADMAP.md`.

---

## Documentation

- **`ARCHITECTURE.md`** — how it works, why each trade-off was made, and a threat model that
  lists what Kavach does *not* stop
- **`ROADMAP.md`** — phased plan
- **`docs/PRIVACY.md`** — exactly what is stored and the only two things that leave the device
- **`CONTRIBUTING.md`** — standards for changes to a security tool

---

## Licence

MIT. See `LICENSE`.

The compiled blocklists incorporate data from AdGuard Filters (CC BY-SA 3.0) and EasyList
(GPL-3.0 / CC BY-SA 3.0). Those are data, not code, and are not linked into the binary.

---

## Acknowledgements

The design owes a great deal to work that came first: **RethinkDNS** and **NetGuard** for
proving what a per-app Android firewall can be, **Shelter** and **Island** for the
managed-profile approach, and **AdGuard** and **EasyList** for the filter data.
