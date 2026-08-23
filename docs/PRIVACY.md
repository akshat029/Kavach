# Kavach privacy policy

**Last updated:** with the 0.1.0-alpha release.

This is referenced from `AndroidManifest.xml` and is the authoritative statement of what
Kavach does with your data.

---

## The short version

Kavach has no servers. There is no account, no telemetry, no analytics, no crash reporting
and no sync. Nothing about you is transmitted anywhere, because there is nowhere for it to
be transmitted to.

An ad blocker that phones home would be a contradiction, so this one cannot.

---

## What Kavach stores, and where

Everything below lives in Kavach's private app storage on your device
(`/data/data/com.kavach.app/`), which no other app can read.

| Data | Where | Why it exists |
|---|---|---|
| Per-app zone and network mode | Room — `app_policy` | So policy survives a reboot |
| Your allow/block rules | Room — `domain_rule` | So your decisions persist |
| Connection log — timestamp, app, domain, query type, verdict, reason | Room — `connection_log` | So you can see what your apps are doing and debug a broken app |
| Global settings and DoH choice | DataStore — `kavach_settings` | Your preferences |
| Downloaded blocklists | `filesDir/blocklists/` | Plain text domain lists |

`android:allowBackup="false"` is set in the manifest. Your policy database is deliberately
excluded from Android's cloud backup, so it is never uploaded to Google Drive.

---

## The connection log deserves its own section

The log is the most sensitive thing Kavach holds. **It is a record of which apps contacted
which domains and when** — in aggregate, a detailed picture of how you use your phone.

So, precisely:

- It is stored **only** on your device, in app-private storage.
- It is **never** transmitted anywhere. There is no upload path in the code.
- It is **capped at 20,000 rows** and trimmed automatically. It does not grow forever.
- It can be **turned off entirely** in Settings → Log connections. With logging off,
  filtering continues to work and nothing is written.
- It can be **cleared at any time** from the Log screen.
- Uninstalling Kavach deletes it, along with everything else.

If you would rather it did not exist, turn it off. The app is fully functional without it;
you simply lose the ability to see why something was blocked.

---

## What leaves your device

Exactly two things, both initiated by you or by your own settings:

**1. DNS queries, to your chosen DoH resolver.**

When an app is allowed to reach a domain, Kavach forwards that DNS query over HTTPS to the
resolver you selected in Settings — Cloudflare, Quad9, Mullvad or AdGuard. This is the same
query your phone would have made anyway, except encrypted instead of plaintext.

The resolver operator sees the domain being looked up and your IP address. They do **not**
see which app asked, because Kavach does not tell them. Their privacy policy applies to
that data, not this one. If you do not want to trust any of them, that decision is yours to
make and the list is in Settings.

Queries that Kavach **blocks are never forwarded**. A blocked domain is answered locally.
The resolver never learns that the query happened.

**2. Blocklist downloads.**

Once a day, on unmetered networks, Kavach fetches two plain text files from
`raw.githubusercontent.com`. This is an ordinary HTTPS GET. It sends no identifiers, no
query parameters and no information about you or your device beyond what any HTTPS request
necessarily reveals. It can be disabled in Settings, in which case the bundled lists are
used instead.

That is the complete list. There is no third item.

---

## Permissions, and why each one exists

| Permission | Reason |
|---|---|
| `INTERNET` | Forward allowed DNS queries and download blocklists |
| `ACCESS_NETWORK_STATE` | Attribute a packet to the app that sent it, on Android 10+ |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the tunnel alive. Android requires a visible notification for this, which is why you see one. |
| `POST_NOTIFICATIONS` | Show that tunnel notification |
| `RECEIVE_BOOT_COMPLETED` | Restart the shield after a reboot, only if you enabled it |
| `QUERY_ALL_PACKAGES` | List your installed apps so you can assign zones to them. The list is read on-device and never leaves it. |
| `BIND_VPN_SERVICE` | Required by Android to create the tunnel |
| `BIND_DEVICE_ADMIN` | Required only for the optional managed profile feature |

---

## What Kavach is *not* doing

Worth stating explicitly, because a VPN-based app could plausibly do all of these:

- It does **not** route your general internet traffic. Only DNS enters the tunnel; see
  ARCHITECTURE.md §3.1. Your web browsing, video calls and file transfers never pass through
  Kavach's code at all.
- It does **not** intercept or decrypt TLS. No certificate is generated, and none is
  installed into your trust store. Kavach cannot read the contents of anything you send.
- It does **not** read your files, contacts, messages, location, camera or microphone. It
  does not request those permissions and could not use them if it had them.
- It does **not** contain any advertising SDK, analytics SDK or crash reporter. You can
  verify this: the complete dependency list is in `app/build.gradle.kts` and is short enough
  to read in a minute.

---

## The managed profile

If you use the optional isolation feature, Kavach becomes the profile owner of a work
profile on your device. This grants it device-policy privileges *within that profile only*.

Kavach uses them for exactly three things: setting the profile name, blocking cross-profile
sharing and clipboard, and — when you explicitly ask — hiding an app or denying it a
permission.

It does not and cannot read data inside the profile. Removing the profile
(Isolation → Remove profile) wipes everything inside it, which is why that action requires
a separate confirmation.

---

## Children

Kavach is not directed at children and collects nothing from anyone, including children.

---

## Changes

This file is versioned in Git alongside the code. Any change to it is a visible commit in
the public history, so you can diff it. There is no silently updated web page.

---

## Contact

Open an issue: https://github.com/akshat029/Kavach/issues
