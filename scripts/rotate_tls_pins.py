#!/usr/bin/env python3
"""Refresh the Android TLS certificate pins from the live upstream hosts.

For every ``<domain>``/``<pin-set>`` pair in ``network_security_config.xml`` this
script connects to the live host, recomputes the leaf and issuing-intermediate
SPKI SHA-256 pins, and rewrites the pin-set with the fresh pins plus an
``expiration`` date set ``--lead-days`` (default 5) before the leaf
certificate's ``notAfter``. It then refreshes the "Pins last fetched" comment
and the deadline table in ``docs/PIN-ROTATION.md``.

It is the automated counterpart to the manual openssl procedure documented in
``docs/PIN-ROTATION.md``. ``.github/workflows/pin-rotation.yml`` runs it on a
schedule and opens a PR whenever the live certs differ from what is pinned, so
pins are refreshed long before the pin-expiry watchdog
(``.github/workflows/pin-expiry-check.yml``) would file an issue.

Design notes
------------
* The host list is read FROM the XML -- the XML is the single source of truth.
  Adding a ``<domain-config>`` automatically brings it under rotation.
* Edits are surgical text rewrites (not XML re-serialisation) so the extensive
  explanatory comments and formatting in the file are preserved byte-for-byte
  outside the ``expiration``/``<pin>`` values being updated.
* No third-party Python packages. ``openssl`` must be on PATH and the host must
  be reachable on :443.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

DEFAULT_NSC = "app/composeApp/src/androidMain/res/xml/network_security_config.xml"
DEFAULT_DOC = "docs/PIN-ROTATION.md"
DEFAULT_LEAD_DAYS = 5
CONNECT_TIMEOUT = 30  # seconds for the openssl s_client handshake

# Matches each "<domain ...>HOST</domain>" immediately followed by its
# "<pin-set ...>...</pin-set>" block. DOTALL so the body spans newlines.
PINSET_RE = re.compile(
    r"<domain\b[^>]*>(?P<host>[^<]+)</domain>\s*"
    r"(?P<pinset><pin-set\b[^>]*>.*?</pin-set>)",
    re.DOTALL,
)
PIN_RE = re.compile(r'(<pin digest="SHA-256">)[^<]*(</pin>)')
EXPIRATION_RE = re.compile(r'(expiration=")[^"]*(")')

# Markers delimiting the machine-maintained regions of docs/PIN-ROTATION.md.
TABLE_START = "<!-- AUTO-PIN-TABLE:START -->"
TABLE_END = "<!-- AUTO-PIN-TABLE:END -->"
DEADLINE_START = "<!-- AUTO-PIN-DEADLINE:START -->"
DEADLINE_END = "<!-- AUTO-PIN-DEADLINE:END -->"


class RotationError(RuntimeError):
    """Raised for any unrecoverable problem fetching or parsing a cert."""


def _openssl(args: list[str], stdin: bytes = b"") -> bytes:
    """Run ``openssl <args>`` feeding ``stdin``; return stdout, raise on error."""
    proc = subprocess.run(
        ["openssl", *args],
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0:
        raise RotationError(
            f"openssl {' '.join(args)} failed ({proc.returncode}): "
            f"{proc.stderr.decode(errors='replace').strip()}"
        )
    return proc.stdout


def fetch_chain(host: str) -> list[str]:
    """Return the served certificate chain (leaf first) as a list of PEM strings."""
    try:
        out = subprocess.run(
            [
                "openssl",
                "s_client",
                "-servername",
                host,
                "-connect",
                f"{host}:443",
                "-showcerts",
            ],
            input=b"",
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=CONNECT_TIMEOUT,
        ).stdout.decode(errors="replace")
    except subprocess.TimeoutExpired as exc:
        raise RotationError(f"{host}: TLS handshake timed out after {CONNECT_TIMEOUT}s") from exc
    chain = re.findall(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
        out,
        re.DOTALL,
    )
    if len(chain) < 2:
        raise RotationError(
            f"{host}: expected at least leaf + intermediate, got {len(chain)} cert(s). "
            "Did the host stop sending its intermediate?"
        )
    return chain


def spki_pin(cert_pem: str) -> str:
    """Base64 SHA-256 of the cert's DER SubjectPublicKeyInfo (the Android pin)."""
    pub_pem = _openssl(["x509", "-pubkey", "-noout"], cert_pem.encode())
    der = _openssl(["pkey", "-pubin", "-outform", "DER"], pub_pem)
    return base64.b64encode(hashlib.sha256(der).digest()).decode()


def leaf_not_after(cert_pem: str) -> datetime:
    """Parse the certificate's ``notAfter`` into a UTC datetime."""
    raw = _openssl(["x509", "-noout", "-enddate"], cert_pem.encode()).decode().strip()
    # e.g. "notAfter=Jul 31 07:40:54 2026 GMT"
    value = raw.split("=", 1)[1].strip()
    if value.endswith("GMT"):
        value = value[:-3].strip()
    # Collapse the space-padded day ("Feb  3") so %d parses uniformly.
    parsed = datetime.strptime(" ".join(value.split()), "%b %d %H:%M:%S %Y")
    return parsed.replace(tzinfo=timezone.utc)


def issuer_label(cert_pem: str) -> str:
    """A human-friendly name for the intermediate (matches the existing table)."""
    raw = _openssl(["x509", "-noout", "-subject"], cert_pem.encode()).decode().strip()
    # "subject=C=US, O=Google Trust Services, CN=WE1" (spacing varies by version)
    fields: dict[str, str] = {}
    for part in raw.split("=", 1)[1].split(","):
        if "=" in part:
            key, _, val = part.partition("=")
            fields[key.strip()] = val.strip()
    org, common = fields.get("O", ""), fields.get("CN", "")
    if org and common:
        # If the CN already carries the brand (e.g. "DigiCert EV RSA CA G2"),
        # don't double it up; otherwise prefix the org ("Google Trust Services WE1").
        if org.split()[0].lower() in common.lower():
            return common
        return f"{org} {common}"
    return common or org or "unknown"


def _rewrite_block(match: re.Match, info: dict) -> str:
    """Swap the expiration + two pins inside one matched pin-set block."""
    host = match.group("host").strip()
    data = info[host]
    pinset = match.group("pinset")
    pinset = EXPIRATION_RE.sub(
        lambda m: f"{m.group(1)}{data['expiration']}{m.group(2)}", pinset, count=1
    )
    pins = iter([data["leaf"], data["intermediate"]])
    pinset = PIN_RE.sub(lambda m: f"{m.group(1)}{next(pins)}{m.group(2)}", pinset)
    return match.group(0).replace(match.group("pinset"), pinset)


def _render_doc(doc: str, hosts: list[str], info: dict) -> str:
    """Regenerate the auto-maintained table and deadline line between markers."""
    earliest_exp = min(info[h]["expiration"] for h in hosts)
    rows = [
        "| Host | Leaf notAfter | `expiration` in XML | Issuer (intermediate) |",
        "|------|---------------|---------------------|-----------------------|",
    ]
    earliest_host = None
    # Soonest deadline first, matching the doc's existing convention.
    for host in sorted(hosts, key=lambda h: info[h]["expiration"]):
        data = info[host]
        exp = data["expiration"]
        if exp == earliest_exp and earliest_host is None:
            earliest_host = host
            exp = f"**{exp}**"
        rows.append(
            f"| {host} | {data['not_after'].date().isoformat()} | {exp} | {data['issuer']} |"
        )
    table = "\n".join(rows)
    deadline = (
        f"**The driving deadline is {earliest_host} on {earliest_exp}.** A release "
        "with refreshed pins MUST be on GitHub Releases (and have had time to "
        "propagate to F-Droid + users) before that date, or pinning silently "
        f"disables for {earliest_host}."
    )

    def _replace_region(text: str, start: str, end: str, body: str) -> str:
        pattern = re.compile(re.escape(start) + r".*?" + re.escape(end), re.DOTALL)
        if not pattern.search(text):
            print(f"  ! marker pair {start} .. {end} not found in doc; skipping", file=sys.stderr)
            return text
        return pattern.sub(f"{start}\n{body}\n{end}", text)

    doc = _replace_region(doc, TABLE_START, TABLE_END, table)
    doc = _replace_region(doc, DEADLINE_START, DEADLINE_END, deadline)
    return doc


def rotate(nsc_path: Path, doc_path: Path, lead_days: int, today: str, dry_run: bool) -> bool:
    """Refresh pins in the NSC file and doc. Returns True if anything changed."""
    nsc_text = nsc_path.read_text()
    hosts = [m.group("host").strip() for m in PINSET_RE.finditer(nsc_text)]
    if not hosts:
        raise RotationError(f"No <domain>/<pin-set> blocks found in {nsc_path}")

    info: dict[str, dict] = {}
    for host in hosts:
        print(f"-> {host}")
        chain = fetch_chain(host)
        not_after = leaf_not_after(chain[0])
        expiration = (not_after.date() - timedelta(days=lead_days)).isoformat()
        info[host] = {
            "leaf": spki_pin(chain[0]),
            "intermediate": spki_pin(chain[1]),
            "not_after": not_after,
            "expiration": expiration,
            "issuer": issuer_label(chain[1]),
        }
        print(
            f"   leaf={info[host]['leaf']} notAfter={not_after.date()} "
            f"expiration={expiration} issuer={info[host]['issuer']}"
        )

    new_nsc = PINSET_RE.sub(lambda m: _rewrite_block(m, info), nsc_text)
    pins_changed = new_nsc != nsc_text
    if pins_changed:
        # Only bump the "last fetched" stamp when a pin/expiration actually
        # moved, so a no-op run leaves a clean (zero-diff) tree.
        new_nsc = re.sub(
            r"(Pins last fetched:\s*)\d{4}-\d{2}-\d{2}",
            lambda m: f"{m.group(1)}{today}",
            new_nsc,
        )

    changed = pins_changed
    if pins_changed and not dry_run:
        nsc_path.write_text(new_nsc)

    if doc_path.exists():
        doc_text = doc_path.read_text()
        new_doc = _render_doc(doc_text, hosts, info)
        if new_doc != doc_text:
            changed = True
            if not dry_run:
                doc_path.write_text(new_doc)

    print("CHANGED" if changed else "NO CHANGES")
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nsc", default=DEFAULT_NSC, type=Path, help="network_security_config.xml path")
    parser.add_argument("--doc", default=DEFAULT_DOC, type=Path, help="PIN-ROTATION.md path")
    parser.add_argument("--lead-days", default=DEFAULT_LEAD_DAYS, type=int, help="days before notAfter to set expiration")
    parser.add_argument("--today", default=datetime.now(timezone.utc).date().isoformat(), help="date for the 'Pins last fetched' stamp (YYYY-MM-DD)")
    parser.add_argument("--dry-run", action="store_true", help="report what would change without writing files")
    args = parser.parse_args(argv)

    try:
        rotate(args.nsc, args.doc, args.lead_days, args.today, args.dry_run)
    except RotationError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    # Success is always 0 (changed or not). The workflow detects changes via
    # `git diff` so "no rotation needed" is a clean no-op, not a failure.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
