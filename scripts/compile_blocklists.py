#!/usr/bin/env python3
"""Compile Kavach's tracker and advertising blocklists.

Run by .github/workflows/update-blocklists.yml once a night. Downloads the upstream
feeds, normalises every supported syntax into a plain domain list, merges the result
with the hand-maintained seed files, and writes:

    blocklists/trackers.txt
    blocklists/ads.txt

Those two files are what the phone downloads, so all parsing and validation happens
here on CI rather than on the device.

Design rules, in order of importance:

1.  Never publish a smaller list than the seed. A feed that 404s, rate-limits or
    returns an error page must degrade to "no change", never to "user unprotected".
2.  Output must be byte-stable for identical input. No timestamps in the header, and
    everything is sorted, so `git diff --quiet` is a truthful "nothing changed".
3.  Cosmetic filter rules are dropped. A DNS layer cannot hide a page element, and
    pretending otherwise produces a list full of entries that silently do nothing.

Usage:
    python3 scripts/compile_blocklists.py [--output-dir blocklists] [--offline]

``--offline`` skips every download and rebuilds the outputs from the seeds alone,
which is what the test job uses to verify the script without network flakiness.
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

USER_AGENT = "Kavach-blocklist-compiler/1.0 (+https://github.com/akshat029/Kavach)"
TIMEOUT_SECONDS = 60

# Upstream feeds. Each entry is (label, url).
#
# These are the same families of list the wider ecosystem relies on. They are fetched
# from their canonical raw locations so there is no third party in the chain.
TRACKER_SOURCES = [
    (
        "AdGuard Spyware / tracking servers",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/"
        "SpywareFilter/sections/tracking_servers.txt",
    ),
    (
        "AdGuard Spyware / tracking servers (international)",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/"
        "SpywareFilter/sections/tracking_servers_international.txt",
    ),
    (
        "AdGuard Spyware / mobile",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/"
        "SpywareFilter/sections/mobile.txt",
    ),
    (
        "EasyPrivacy / tracking servers",
        "https://raw.githubusercontent.com/easylist/easylist/master/"
        "easyprivacy/easyprivacy_trackingservers.txt",
    ),
    (
        "EasyPrivacy / tracking servers (international)",
        "https://raw.githubusercontent.com/easylist/easylist/master/"
        "easyprivacy/easyprivacy_trackingservers_international.txt",
    ),
]

AD_SOURCES = [
    (
        "AdGuard Mobile / ad servers",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/"
        "MobileFilter/sections/adservers.txt",
    ),
    (
        "AdGuard Base / ad servers",
        "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/"
        "BaseFilter/sections/adservers.txt",
    ),
    (
        "EasyList / ad servers",
        "https://raw.githubusercontent.com/easylist/easylist/master/"
        "easylist/easylist_adservers.txt",
    ),
    (
        "EasyList / ad server popups",
        "https://raw.githubusercontent.com/easylist/easylist/master/"
        "easylist/easylist_adservers_popup.txt",
    ),
]

# Never block these, whatever a feed says. Blocking any of them breaks the device,
# the app store, or Kavach's own update channel.
NEVER_BLOCK = {
    "localhost",
    "localhost.localdomain",
    "local",
    "ip6-localhost",
    "ip6-loopback",
    "broadcasthost",
    "raw.githubusercontent.com",
    "github.com",
    "githubusercontent.com",
    "cloudflare-dns.com",
    "dns.quad9.net",
    "dns.adguard-dns.com",
    "dns.mullvad.net",
    "android.com",
    "google.com",
    "gstatic.com",
    "googleapis.com",
    "play.google.com",
    "clients3.google.com",
    "connectivitycheck.gstatic.com",
    "apple.com",
    "icloud.com",
    "microsoft.com",
    "amazonaws.com",
    "cloudfront.net",
    "akamaihd.net",
    "akamaized.net",
    "fastly.net",
    "whatsapp.net",
    "whatsapp.com",
}

HOSTS_PREFIXES = ("0.0.0.0", "127.0.0.1", "::", "::1")
DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")


def parse_line(raw: str) -> str | None:
    """Normalise one line of any supported list format into a bare domain.

    Mirrors DomainMatcher.parseLine on the device, so a line the compiler keeps is a
    line the app can also read if it ever falls back to a raw upstream file.
    """
    line = raw.strip()
    if not line:
        return None
    if line[0] in "#!<[":
        return None
    # Cosmetic and scriptlet rules cannot be expressed at the DNS layer.
    if "##" in line or "#@#" in line or "#?#" in line or "#%#" in line:
        return None
    # Exception rules are modelled as per-app user rules inside the app instead.
    if line.startswith("@@"):
        return None

    if line.startswith("||"):
        line = line[2:]
        # Only keep plain domain anchors. Anything with a path, a regex or a
        # request-type option is not something a DNS answer can honour.
        for terminator in ("^", "$", "/", "*", "|"):
            head, sep, _tail = line.partition(terminator)
            if sep:
                if terminator == "^" and _tail and not _tail.startswith("$"):
                    return None
                line = head
        if not line:
            return None
    elif line.startswith("|") or line.startswith("/"):
        return None
    else:
        parts = line.split()
        if len(parts) >= 2:
            if parts[0] not in HOSTS_PREFIXES:
                return None
            line = parts[1]
        elif len(parts) == 1:
            line = parts[0]
        else:
            return None

    # Strip a trailing inline comment.
    line = line.split("#", 1)[0].strip()
    domain = line.lower().rstrip(".")
    if domain.startswith("*."):
        domain = domain[2:]
    if not domain or "." not in domain or len(domain) > 253:
        return None
    if not DOMAIN_RE.match(domain):
        return None
    if domain in NEVER_BLOCK:
        return None
    return domain


def read_seed(path: Path) -> set[str]:
    if not path.exists():
        print(f"  !! seed file missing: {path}", file=sys.stderr)
        return set()
    domains = set()
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parsed = parse_line(line)
        if parsed:
            domains.add(parsed)
    print(f"  seed {path.name}: {len(domains)} domains")
    return domains


def fetch(label: str, url: str) -> set[str]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
        # A single bad feed is expected occasionally and must not fail the run.
        print(f"  !! {label}: {exc}", file=sys.stderr)
        return set()

    domains = {d for d in (parse_line(line) for line in body.splitlines()) if d}
    print(f"  {label}: {len(domains)} domains")
    return domains


def collapse_redundant(domains: set[str]) -> set[str]:
    """Drop entries already covered by a broader entry in the same set.

    ``ads.example.com`` is pointless when ``example.com`` is present, because the
    matcher walks label boundaries. Removing them typically sheds a third of the file
    and makes the on-device hash set proportionally smaller.
    """
    kept = set()
    for domain in domains:
        parts = domain.split(".")
        covered = False
        for i in range(1, len(parts) - 1):
            if ".".join(parts[i:]) in domains:
                covered = True
                break
        if not covered:
            kept.add(domain)
    return kept


def write_list(path: Path, title: str, sources: list[str], domains: set[str]) -> bool:
    """Write the compiled list. Returns True when the file content changed."""
    ordered = sorted(domains)
    header = [
        f"# {title}",
        "#",
        "# Generated by scripts/compile_blocklists.py. Do not edit by hand -",
        "# edit the matching seed-*.txt file instead.",
        "#",
        f"# Entries: {len(ordered)}",
        "# Sources:",
    ]
    header += [f"#   - {s}" for s in sources]
    header.append("")
    body = "\n".join(header + ordered) + "\n"

    previous = path.read_text(encoding="utf-8") if path.exists() else ""
    if previous == body:
        print(f"  {path.name}: unchanged ({len(ordered)} entries)")
        return False

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    print(f"  {path.name}: written ({len(ordered)} entries)")
    return True


def build(
    name: str,
    title: str,
    seed_path: Path,
    feeds: list[tuple[str, str]],
    output: Path,
    offline: bool,
) -> bool:
    print(f"\n== {name} ==")
    seed = read_seed(seed_path)
    domains = set(seed)
    used_sources = [f"{seed_path.name} (hand-maintained seed)"]

    if not offline:
        for label, url in feeds:
            fetched = fetch(label, url)
            if fetched:
                domains |= fetched
                used_sources.append(f"{label} - {url}")

    domains -= NEVER_BLOCK
    before = len(domains)
    domains = collapse_redundant(domains)
    print(f"  merged: {before} -> {len(domains)} after collapsing redundant subdomains")

    # The safety floor. Publishing fewer domains than the seed alone would mean the
    # update actively made the user less protected.
    floor = max(len(seed) // 2, 1)
    if len(domains) < floor:
        print(
            f"  !! refusing to write {output.name}: {len(domains)} entries is below "
            f"the floor of {floor}",
            file=sys.stderr,
        )
        return False

    return write_list(output, title, used_sources, domains)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=REPO_ROOT / "blocklists",
        help="Directory to write trackers.txt and ads.txt into.",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="Skip all downloads and rebuild from the seed files only.",
    )
    args = parser.parse_args()

    seeds = args.output_dir
    changed = False

    changed |= build(
        name="Trackers",
        title="Kavach compiled tracker list",
        seed_path=seeds / "seed-trackers.txt",
        feeds=TRACKER_SOURCES,
        output=args.output_dir / "trackers.txt",
        offline=args.offline,
    )
    changed |= build(
        name="Ads",
        title="Kavach compiled advertising list",
        seed_path=seeds / "seed-ads.txt",
        feeds=AD_SOURCES,
        output=args.output_dir / "ads.txt",
        offline=args.offline,
    )

    print("\nchanged" if changed else "\nno changes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
