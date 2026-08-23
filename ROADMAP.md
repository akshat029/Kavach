# Kavach roadmap

Each phase is written so it can ship on its own. Nothing here depends on a later phase
landing first, and no phase is allowed to half-land — a partially implemented network stack
is worse than none.

---

## Phase 1 — DNS boundary ✅ *shipped in 0.1.0-alpha*

The foundation.

- `VpnService` with DNS-only routing, no default route, no userspace TCP/IP stack
- IPv4 and IPv6 UDP parsing with full checksum handling
- DNS parsing, sinkhole, NXDOMAIN, REFUSED, truncation
- DNS-over-HTTPS to Cloudflare / Quad9 / Mullvad / AdGuard, no plaintext fallback
- Per-packet UID attribution → package name
- Zones: Vault, Social, Offline, Open
- Per-app and global allow/block rules with label-boundary matching
- Learning mode
- Live connection log with per-app and blocked-only filters
- Bundled + nightly-updated blocklists with an anti-regression floor
- Managed profile provisioning and cross-profile boundary sealing
- 60+ pure-JVM unit tests

---

## Phase 1.5 — From filtering to blocking

**The single most valuable next thing.** Phase 1 refuses to *resolve*; Phase 1.5 refuses to
*connect*. This closes the two limitations named on the dashboard.

### Goal

An app that hardcodes `142.250.column.x` and skips DNS entirely gets stopped.

### Work

1. **Userspace TCP/UDP relay.** Route `0.0.0.0/0` into the TUN and terminate connections in
   userspace. The pragmatic path is to port a proven implementation rather than write a TCP
   state machine from scratch — `Jigsaw-Code/outline-go-tun2socks`, which RethinkDNS forked
   as `firestack`, is the reference.
2. **IP-level rules.** `PolicyEngine` gains an `evaluateIp(packageName, ip, port)` path
   alongside the existing domain path. The precedence table is extended, not replaced.
3. **Reverse-DNS annotation.** Cache every DoH answer so an outbound connection to a raw IP
   can be labelled with the hostname that produced it. Without this, an IP-level log is
   unreadable.
4. **SNI inspection for plain-IP TLS.** Read the SNI from the ClientHello — *without*
   terminating TLS — to recover the intended hostname when DNS was bypassed.
5. **Kill-switch.** Block-on-disconnect so traffic cannot escape while the tunnel is down.

### Risks to manage

Battery, throughput, and the long tail of protocol edge cases. This phase must ship behind
a setting that defaults to off until it has been on a real device for weeks. The DNS-only
path stays as the safe default and the fallback.

---

## Phase 2 — Identity plane

Phase 1.5 stops the data leaving. Phase 2 breaks the **join key** — the shared identifier
that lets two datasets about you become one dataset about you.

This matters because blocking a tracker's domain in App A does nothing if App B is allowed
to talk to the same tracker and both report the same advertising ID. The EFF has written
repeatedly about exactly this join.

### Work

1. **Per-zone advertising ID.** Inside the managed profile, the profile owner can reset the
   ad ID. A per-zone reset schedule means Vault-zone apps and Social-zone apps never report
   the same identifier.
   *Open question that needs a real test matrix:* whether a work profile genuinely carries
   an independent GAID on every OEM. This is currently unverified and is treated as an
   assumption to be tested, not a feature to be advertised.
2. **`ANDROID_ID` separation.** Already per-profile-per-signing-key on modern Android;
   surface it so the user can see what each zone reports.
3. **Permission clamping.** Use `setPermissionGrantState` to hard-deny location, contacts,
   phone state and body sensors for whole zones at once, instead of app by app.
4. **Locale and timezone noise** for zones where it will not break the app.
5. **"What can this app see about me?"** A per-app identity report: ad ID, `ANDROID_ID`,
   granted permissions, installed-package visibility. Most of the value here is making the
   invisible visible.

### Explicitly out of scope

Sensor value spoofing and MAC randomisation beyond what the OS already does. Both need root
or a Magisk/Xposed module, which changes this from an app into a system modification and
puts it out of reach for the people who most need it.

---

## Phase 3 — Fusion

This is the phase that makes Kavach something that does not currently exist.

Today, Shelter and Island give you isolation. RethinkDNS and NetGuard give you network
control. Nobody joins them. Phase 3 makes **one zone assignment drive both planes at once**:

```
User drags WhatsApp into the Vault zone
        │
        ├─► Plane 0: moved into the managed profile, cross-profile sharing denied
        ├─► Plane 1: network mode set to allowlist-only
        ├─► Plane 2: ad ID reset, location permission hard-denied
        └─► Plane 3: one row in the UI, one thing to understand
```

### Work

1. **Zone → profile placement.** Assigning a zone installs or hides the app in the correct
   profile automatically via `setApplicationHidden`.
2. **The Data Passport.** When an app in one zone tries to reach an app in another — a share
   intent, a file handoff — Kavach shows a single prompt naming exactly what is crossing and
   in which direction, and remembers the decision. This is the user-facing form of the
   original "guest room" idea, and it is the one part of the container model that survives
   contact with reality.
3. **Zone templates.** Ship sane presets — Banking, Social, Shopping, Work — so a new user
   is protected in one tap instead of one hundred.
4. **Cross-zone traffic view.** "Which zones has this tracker been seen in?" Correlation
   made visible.

---

## Phase 4 — Durability

The unglamorous phase that decides whether anyone is still using this in two years.

1. **Export / import** of the full policy set as human-readable JSON. Your rules are yours;
   a reinstall must not cost you a month of tuning.
2. **Shareable zone profiles.** A signed, reviewable file so one person can tune "Vault
   settings for HDFC Bank" and everyone else can import it.
3. **Obtainium-native releases.** Signed GitHub Releases with a stable naming scheme so
   updates arrive automatically without an app store.
4. **Reproducible builds**, so the APK you install can be proven to match this source.
5. **Instrumented tests** on a real device matrix. Managed-profile behaviour varies enough
   between OEMs that this cannot be reasoned about — only measured. Known-bad OEM builds get
   documented rather than silently failing.
6. **Accessibility and localisation.** Starting with Hindi, since that is the first audience.

---

## Not planned

| | Why |
|---|---|
| Play Store release | Play policy forbids using the VPN slot to filter ads in other apps. Sideload-first is a deliberate choice, not a limitation. |
| TLS interception | Would require the user to trust Kavach with every byte they send, and would break certificate pinning in exactly the banking apps this exists to protect. |
| App-level virtualization | Puts every guest app under one UID and destroys the kernel-enforced boundary. This is the design Kavach explicitly rejected. See ARCHITECTURE.md §1. |
| Cloud accounts or sync | There is no backend and there will not be one. |
| Root-only features | The people who most need this are least likely to have an unlocked bootloader. |
