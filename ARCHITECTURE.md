# Kavach architecture

How it works, and why each trade-off was made. Section 9 lists what Kavach does **not** stop.

---

## 1. The design constraint

Kavach began as a container: run other apps inside it, and filter everything they do. That
design was rejected before a line of code was written, for one reason.

App-level virtualization loads guest apps into the host process. Every guest therefore runs
under **the host's UID**:

> In app-level virtualization, the host app shares the same UID with all guest apps and
> relies on hooking.
>
> — Song et al., *ACM CCS 2021*

Android's app sandbox is built on exactly the thing that destroys:

> Android assigns a unique user ID (UID) to each Android app and runs it in its own process
> … If app A tries to do something malicious, such as read app B's data … it's prevented
> from doing so.
>
> — *Android app sandbox*, source.android.com

So a container would have taken a kernel-enforced, SELinux-backed boundary and replaced it
with userspace hooks maintained by one person. For a project whose stated purpose is *stop
one app leaking data to another*, that is not a compromise. It is an inversion.

**The decision:** keep the box as the user-facing metaphor, and build it out of boundaries
Android already enforces. Kavach never loads another app's code. It never asks for root. It
never installs a certificate.

---

## 2. Four planes

```
┌─────────────────────────────────────────────────────────────────┐
│  Plane 3 — Interface            zones, rules, log, learning mode  │
│  Compose UI + ViewModels                                          │
├─────────────────────────────────────────────────────────────────┤
│  Plane 2 — Identity             ROADMAP phase 2                   │
│  per-zone ad ID, permission clamping                              │
├─────────────────────────────────────────────────────────────────┤
│  Plane 1 — Network              SHIPPED                           │
│  VpnService ─ DNS-only TUN ─ PolicyEngine ─ DoH                   │
├─────────────────────────────────────────────────────────────────┤
│  Plane 0 — Isolation            SHIPPED (optional)                │
│  managed work profile, kernel-enforced, sealed both directions    │
└─────────────────────────────────────────────────────────────────┘
```

Planes 0 and 1 are independent. Neither requires the other; each is useful alone. Plane 3
makes one zone assignment drive both, which is the part nothing else on Android currently
does.

---

## 3. The network path

### 3.1 DNS-only routing — the load-bearing decision

`KavachVpnService` establishes a TUN interface but adds **only the virtual DNS address to
the route table**. There is no default route.

| | |
|---|---|
| IPv4 client / DNS | `198.18.71.1` / `198.18.71.53`, prefix `/32` |
| IPv6 client / DNS | `fd6b:6176:6163:68::1` / `fd6b:6176:6163:68::53`, prefix `/128` |
| MTU | `1500` |
| Max DNS payload | `MTU - 48` |
| Metering | `setMetered(false)` on API 29+ |

The IPv4 range is RFC 5737/benchmark documentation space and the IPv6 range is RFC 4193
unique-local, so neither can collide with a real destination.

**The consequence, stated plainly:** only DNS packets enter Kavach's code. All other traffic
— every TCP connection, every video stream, every file upload — flows natively through the
OS and is never seen, touched, buffered or slowed by this app.

**Why that is the right call for v1:**

- No userspace TCP/IP stack. A half-written TCP state machine is the most common way an
  Android firewall becomes unusably slow or silently drops connections.
- Battery cost is near zero. Nothing is proxied.
- The failure mode is benign. If Kavach crashes, the tunnel drops and normal DNS resumes.
  Nothing is left half-proxied.

**The cost, stated equally plainly:** an app that connects to a hard-coded IP address never
asks a DNS question and therefore cannot be refused. This is surfaced in the README, in this
file, and inside the app on the dashboard. Closing it is Phase 1.5.

Non-UDP packets arriving on the TUN are dropped, and that is documented at the drop site.

### 3.2 Packet flow

```
app ──► libc getaddrinfo ──► DNS packet to 198.18.71.53
                                    │
                              [ TUN read ]
                                    │
                            Packets.parseUdp()          reject fragments, non-UDP, runts
                                    │
                            DnsMessage.parseQuestion()  reject compression pointers
                                    │
                            UidResolver.uidFor()        getConnectionOwnerUid (A10+)
                                    │                   /proc/net/{udp,udp6} fallback
                                    │
                            PolicyEngine.evaluate()     ten-branch precedence
                                    │
                    ┌───────────────┴───────────────┐
                  BLOCK                           ALLOW
                    │                               │
            DnsMessage.buildSinkhole        DohResolver.resolve()
            A    → 0.0.0.0                  HTTPS POST, socket protected
            AAAA → ::                        from the tunnel
            else → NXDOMAIN                          │
                    │                               │
                    └───────────────┬───────────────┘
                                    │
                          Packets.buildUdpReply()      endpoints swapped,
                                    │                  checksums recomputed
                              [ TUN write ]
                                    │
                            ConnectionLogger.record()  batched, off the hot path
```

### 3.3 Why sinkhole rather than drop

A blocked lookup is answered with `0.0.0.0` / `::` rather than silently discarded.

Dropping the packet makes the app wait for a DNS timeout — typically five seconds, often
retried. The user experiences that as "the app is broken", not "the tracker was blocked".
An immediate unroutable answer makes the connection fail instantly, which almost every SDK
handles gracefully because it looks like ordinary offline behaviour.

Non-address query types cannot be sinkholed meaningfully, so they get `NXDOMAIN`. A query
too large for the reply buffer gets a response with the `TC` bit set, which is the correct
signal for the client to retry over TCP.

The synthesised answer drops any EDNS `OPT` record the client sent, because Kavach is not
equipped to honour the options in it.

### 3.4 DoH with no fallback

Allowed queries go out over HTTPS to Cloudflare, Quad9, Mullvad or AdGuard. `DohResolver`
uses a bootstrap resolver with hard-coded IPs for the endpoint itself — otherwise resolving
the resolver would recurse — and a socket factory that calls `VpnService.protect()` so its
own traffic does not re-enter the tunnel.

**There is no plaintext DNS fallback, and that is deliberate.** A fallback would mean that
the moment the encrypted resolver becomes unreachable — captive portal, hostile network,
deliberate interference — every query silently reverts to whatever DNS server the network
handed out. That is precisely the situation in which encryption mattered most. Kavach
returns `SERVFAIL` instead. A visible failure is better than an invisible downgrade.

Timeouts are 4 s connect, 6 s read, 8 s call.

### 3.5 UID attribution, and failing open

`UidResolver` maps a packet to the app that sent it:

- **API 29+** — `ConnectivityManager.getConnectionOwnerUid()`, wrapped because it throws
  `SecurityException` on some OEM builds.
- **Older** — scan `/proc/net/udp`, `udp6`, `tcp`, `tcp6`, with addresses encoded
  little-endian per 32-bit group.
- UID → package name is cached in a 256-entry LRU.

When attribution fails, the verdict is **allow**, with reason `UNATTRIBUTED`.

That is a deliberate fail-open. Attribution failure is a Kavach limitation, not evidence of
wrongdoing by the app. Failing closed would break arbitrary apps on arbitrary devices for
reasons no user could diagnose, and would produce exactly the "this app is broken" outcome
that makes people uninstall a firewall and never return. The event is logged so it is
visible rather than silent.

---

## 4. The policy engine

`PolicyEngine` is a pure function: `(snapshot, packageName, domain) → Verdict`. No Android
imports, no I/O, no clock. That is what makes it exhaustively testable.

### Precedence

Evaluated strictly top to bottom. The first match wins.

| # | Branch | Result | Reason |
|---|---|---|---|
| 1 | Kavach is paused | allow | `UNMANAGED` |
| 2 | Package could not be attributed | allow | `UNATTRIBUTED` |
| 3 | Per-app rule for this domain | allow / block | `USER_ALLOW` / `USER_BLOCK` |
| 4 | Global rule for this domain | allow / block | `GLOBAL_ALLOW` / `GLOBAL_BLOCK` |
| 5 | Network mode = unfiltered | allow | `UNMANAGED` |
| 6 | Network mode = no network | block | `APP_OFFLINE` |
| 7 | Network mode = allowlist only | block | `NOT_ON_ALLOWLIST` |
| 8 | On the ad list, and ad blocking on | block | `AD_LIST` |
| 9 | On the tracker list, and tracker blocking on | block | `TRACKER_LIST` |
| 10 | Nothing matched | allow | `DEFAULT_POLICY` |

User intent outranks every automated list, which is why 3 and 4 sit above 8 and 9. Ads are
checked before trackers so a domain on both lists reports the more specific label.

**Learning mode** wraps the result: any block becomes an allow with reason `LEARNING` and a
detail of the form `would block: Known tracker (doubleclick.net)`. Allows pass through
unchanged. This is applied at the end, so it softens every branch uniformly, including
`ALLOWLIST_ONLY` and `BLOCK_ALL`.

### Domain matching

Both rules and blocklists match on **label boundaries**, walking most specific to least:

```
query:  pagead2.doubleclick.net
try:    pagead2.doubleclick.net   miss
try:            doubleclick.net   HIT → reason detail is "doubleclick.net"
```

A rule on `example.com` covers `api.example.com`, and does **not** cover `notexample.com`.
String-suffix matching would get that wrong, so it is not used. Because the walk goes most
specific first, an allow on `api.example.com` correctly overrides a block on `example.com`.

Hostnames are lowercased and stripped of a trailing dot before comparison.

---

## 5. The isolation plane

`IsolationManager` wraps `DevicePolicyManager`. Kavach becomes **profile owner** of a managed
profile — not device owner, which would require a factory reset.

Sealing the boundary is two steps, and the second is easy to miss:

1. `clearCrossProfileIntentFilters()`
2. `addUserRestriction(DISALLOW_SHARE_INTO_MANAGED_PROFILE)` **and**
   `addUserRestriction(DISALLOW_CROSS_PROFILE_COPY_PASTE)`

Step 1 alone is not enough. The system installs *default* cross-profile intent filters at
provisioning time that `clearCrossProfileIntentFilters` does not remove, so sharing keeps
working and the user believes they are sealed when they are not. Android's own CTS covers
this case. Skipping step 2 is the single most likely way to ship a boundary that looks sealed
and is not.

Every `DevicePolicyManager` call is wrapped in `runCatching`, and failures are **surfaced in
a dialog** rather than swallowed. A user who believes the boundary is sealed when it is not
is worse off than one who knows it failed.

Profile removal calls `wipeData(0)` and is behind an explicit irreversible-action
confirmation.

---

## 6. Data and threading

**Room v1**, three tables: `app_policy`, `domain_rule`, `connection_log`. `exportSchema` is
on. There is **no** `fallbackToDestructiveMigration` — a schema mistake must fail loudly in
development rather than silently delete a user's tuned ruleset in production.

**Settings** are DataStore preferences. Corrupt-preferences `IOException` is caught and
recovered as empty preferences rather than crashing on launch.

**One snapshot, four sources.** `PolicyRepository` combines policies, rules, settings and
blocklists into a single immutable `PolicySnapshot`, eagerly held in the application scope.
The VPN thread reads it from a `@Volatile` field, so the hot path never touches the database
and never blocks on a flow.

**No dependency-injection framework.** `KavachApp` holds `by lazy` singletons; components
reach them via `(application as KavachApp)`. The dependency graph is small enough that Hilt
would add build complexity and a code-generation step without removing any real problem.

**Logging is off the hot path.** `ConnectionLogger` queues into a `ConcurrentLinkedQueue`
(bounded at 4,000) and flushes in batches of up to 500 every 2 seconds, trimming to 20,000
rows every 30th flush. A busy DNS moment must never be slowed by a database write.

---

## 7. The blocklist pipeline

```
blocklists/seed-*.txt          hand-maintained floor, in Git
         │
         ├──── merged with AdGuard + EasyList feeds ────┐
         │                                              │
   scripts/compile_blocklists.py  (nightly, on CI)      │
         │   parse → normalise → drop cosmetic rules    │
         │   → subtract NEVER_BLOCK                     │
         │   → collapse redundant subdomains            │
         │   → refuse to write below the safety floor   │
         ▼                                              │
 blocklists/{trackers,ads}.txt  ◄──────────────────────┘
         │
         ├──► app/src/main/assets/   bundled in the APK — the offline floor
         │
         └──► raw.githubusercontent.com
                     │
              BlocklistWorker   daily, unmetered, battery-not-low
                     │          .tmp write → sanity floor → atomic rename
                     ▼
              filesDir/blocklists/   preferred over the bundled copy when present
```

The device never parses an upstream feed. It only ever reads an already-validated plain
domain list, which is why `DomainMatcher` can be a simple hash set and why a malformed feed
cannot degrade protection on the phone.

The download path writes to `.tmp`, checks the line count against a floor, and only then
renames. A truncated download or an error page can therefore never replace a good list.

---

## 8. Deliberately absent

| Not built | Why |
|---|---|
| TLS interception | Would require trusting Kavach with every byte you send, and would break certificate pinning in exactly the banking apps this exists to protect |
| Cosmetic filtering | A DNS layer cannot hide a page element; claiming otherwise would be a lie in the UI |
| App-level virtualization | Collapses every guest into one UID. See §1 |
| Root or Xposed features | The people who most need this are least likely to have an unlocked bootloader |
| Analytics or crash reporting | An ad blocker that phones home is a contradiction |
| A backend of any kind | There is nothing to breach if there is no server |
| Hilt / DI framework | Build complexity with no problem to solve at this size |

---

## 9. Threat model

| Threat | Stopped? | How, or why not |
|---|---|---|
| App reads another app's files | ✅ | Android UID sandbox, reinforced by the managed profile |
| App reads the other profile's clipboard | ✅ | `DISALLOW_CROSS_PROFILE_COPY_PASTE` |
| Sharing a file into the profile | ✅ | `DISALLOW_SHARE_INTO_MANAGED_PROFILE` + cleared intent filters |
| App sends data to a known tracker domain | ✅ | Blocklist, per app |
| App sends data to a known ad network | ✅ | Blocklist, per app |
| Banking app contacts a third party | ✅ | Vault zone: allowlist only |
| A specific domain you distrust | ✅ | Per-app or global rule |
| App phones home while "offline" | ✅ | Offline zone refuses every lookup |
| Network operator reads your DNS | ✅ | DoH, with no plaintext fallback |
| App connects to a hard-coded IP | ❌ | No DNS question is asked. Phase 1.5 |
| App uses its own DoH client | ❌ | Indistinguishable from ordinary HTTPS at this layer |
| Ads served from the app's own domain | ❌ | Blocking it breaks the app |
| Two apps correlated by advertising ID | ❌ | The join happens on a remote server. Phase 2 |
| Malicious app with root, or a compromised OS | ❌ | Out of scope for any userspace app |

---

## 10. Module dependencies

```
        ui ─────────────┐
         │               │
         ▼               ▼
       data ──────────► core ◄────────── vpn
         │                                │
         └────────► isolation             └──► (Android framework only)
```

Strictly one-way. `core/` imports nothing from Android — that constraint is the reason
`PolicyEngine`, `DomainMatcher`, `DnsMessage` and `Packets` can be tested as plain JVM code
in seconds, with no emulator and no Robolectric. Breaking it for convenience would cost the
project its entire test story, so `CONTRIBUTING.md` names it as a hard rule.
