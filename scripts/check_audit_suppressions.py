#!/usr/bin/env python3
"""Give the cargo-audit suppression list in ``.cargo/audit.toml`` teeth.

An ``ignore`` entry in ``.cargo/audit.toml`` silences a RustSec advisory by ID.
That is fine while the suppressed crate is genuinely unreachable, but the
suppression itself is inert text -- nothing re-checks the premise, and
``cargo audit`` gives no signal when an ignore entry stops matching anything.
Two failure modes follow, and this script closes both:

1. **The premise silently stops holding.** Advisories are ignored by ID, not by
   reachability. Cargo's v2 resolver unifies features across the whole graph,
   so adding *any* dependency that enables an optional feature pulling the
   suppressed crate in puts vulnerable code into the shipped artifact -- without
   anyone touching ``core/Cargo.toml`` or the suppression list. ``cargo audit``
   still exits 0, because the ID is still ignored.

2. **The suppression outlives the vulnerability.** Once upstream ships a fixed
   version the advisory stops matching, and the entry becomes indistinguishable
   from an unpatched CVE to the next reader. cargo-audit does not warn about
   ignore entries that match nothing -- verified: a well-formed but unmatched
   advisory ID produces no output at all.

Every entry in the ``ignore`` array must therefore carry a machine-readable
annotation on the line(s) above it::

    # suppression-meta: crate=rkyv recheck=2026-11-13
    "RUSTSEC-2026-0235",

``crate``   -- the package the advisory is filed against. Checked for
               reachability with ``cargo tree -i``.
``recheck`` -- ISO ``YYYY-MM-DD`` date by which a human must re-justify or
               remove the entry.

Exit status is 1 if any entry is missing metadata, is past its re-check date,
or names a crate that is now in the build graph. Warnings (a re-check date
inside ``--threshold-days``, or a crate reachable only under ``--all-features``)
are reported but do not fail the run.

Design notes
------------
* ``cargo tree -i <crate>`` **exits 0 whether or not the crate is found** -- it
  prints ``warning: nothing to print.`` to stderr and leaves stdout empty. The
  reachability test therefore keys off stdout being non-empty. Keying off the
  exit code would produce a check that can never fail.
* Reachability is evaluated with ``--target all`` so that a crate pulled in only
  on the Android triples is caught from a Linux CI runner.
* The authoritative list of IDs comes from ``tomllib``; the ``suppression-meta``
  pairing comes from a raw line scan. The two are cross-checked, so unusual
  formatting (several IDs on one line, say) is a hard error rather than a
  silently skipped entry.
* No third-party packages. ``cargo`` must be on PATH unless
  ``--skip-reachability`` is passed.

Wired into ``.github/workflows/security.yml`` (every PR, every push to main,
and the weekly schedule -- so the date check keeps ticking even when no one is
committing) and into the ``audit`` recipe in ``justfile``.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# `# suppression-meta: crate=rkyv recheck=2026-11-13` -- key=value pairs in any
# order, so the convention can grow a third key without breaking this parser.
META_RE = re.compile(r"^\s*#\s*suppression-meta:\s*(?P<body>.+?)\s*$")
META_PAIR_RE = re.compile(r"(?P<key>[a-z_]+)=(?P<value>\S+)")
# A quoted advisory ID on its own line inside the `ignore = [ ... ]` array.
ADVISORY_LINE_RE = re.compile(r'^\s*"(?P<id>RUSTSEC-\d{4}-\d{4})"\s*,?\s*$')


class Entry:
    """One ``ignore`` entry plus whatever metadata was found above it."""

    def __init__(self, advisory_id: str, line_no: int, meta: dict[str, str]):
        self.advisory_id = advisory_id
        self.line_no = line_no
        self.meta = meta


def parse_entries(config_path: Path) -> list[Entry]:
    """Pair each advisory ID in the ignore array with its suppression-meta line.

    Raises ``SystemExit`` if the line scan and ``tomllib`` disagree about which
    IDs are present -- that means the file is formatted in a way this parser
    would misread, and silently checking a subset would be worse than stopping.
    """
    raw = config_path.read_text(encoding="utf-8")

    declared = tomllib.loads(raw).get("advisories", {}).get("ignore", [])
    if not isinstance(declared, list):
        sys.exit(f"{config_path}: [advisories] ignore must be an array")

    entries: list[Entry] = []
    pending: dict[str, str] = {}
    for line_no, line in enumerate(raw.splitlines(), start=1):
        meta_match = META_RE.match(line)
        if meta_match:
            pending.update(
                {m["key"]: m["value"] for m in META_PAIR_RE.finditer(meta_match["body"])}
            )
            continue

        advisory_match = ADVISORY_LINE_RE.match(line)
        if advisory_match:
            entries.append(Entry(advisory_match["id"], line_no, pending))
            pending = {}

    scanned_ids = sorted(e.advisory_id for e in entries)
    declared_ids = sorted(str(i) for i in declared)
    if scanned_ids != declared_ids:
        sys.exit(
            f"{config_path}: could not pair every ignore entry with its line.\n"
            f"  tomllib sees: {declared_ids}\n"
            f"  line scan sees: {scanned_ids}\n"
            "Put exactly one quoted advisory ID per line inside `ignore = [ ... ]`."
        )
    return entries


def cargo_tree_reachable(crate: str, manifest: Path, all_features: bool) -> bool:
    """True if ``crate`` resolves into the dependency graph.

    ``cargo tree -i`` exits 0 in both directions, so presence is determined by
    stdout being non-empty -- see the module docstring.
    """
    cmd = [
        "cargo",
        "tree",
        "--invert",
        crate,
        "--manifest-path",
        str(manifest),
        "--target",
        "all",
    ]
    if all_features:
        cmd.append("--all-features")

    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError:
        sys.exit("cargo not found on PATH (pass --skip-reachability to skip this check)")

    # A genuine failure (bad manifest, offline registry) must not be read as
    # "crate absent" -- that would turn a broken check into a silent pass.
    if proc.returncode != 0 and "nothing to print" not in proc.stderr:
        sys.exit(
            f"`{' '.join(cmd)}` failed with exit {proc.returncode}:\n{proc.stderr.strip()}"
        )
    return bool(proc.stdout.strip())


def annotate(level: str, message: str) -> None:
    """Emit a GitHub Actions annotation when running in CI, plain text locally."""
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level}::{message}")
    else:
        print(f"{level.upper()}: {message}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--config",
        type=Path,
        default=REPO_ROOT / ".cargo" / "audit.toml",
        help="path to audit.toml (default: <repo>/.cargo/audit.toml)",
    )
    parser.add_argument(
        "--manifest-path",
        type=Path,
        default=REPO_ROOT / "core" / "Cargo.toml",
        help="workspace manifest to resolve against (default: <repo>/core/Cargo.toml)",
    )
    parser.add_argument(
        "--threshold-days",
        type=int,
        default=30,
        help="warn when a re-check date is within this many days (default: 30)",
    )
    parser.add_argument(
        "--today",
        type=dt.date.fromisoformat,
        default=dt.datetime.now(dt.timezone.utc).date(),
        help="override today's date (YYYY-MM-DD), for testing",
    )
    parser.add_argument(
        "--skip-reachability",
        action="store_true",
        help="skip the cargo tree checks (for environments without cargo)",
    )
    args = parser.parse_args()

    if not args.config.is_file():
        # No config is a valid state: it means nothing is suppressed.
        print(f"No {args.config} — no suppressions to verify.")
        return 0

    entries = parse_entries(args.config)
    if not entries:
        print(f"{args.config}: ignore list is empty — nothing to verify.")
        return 0

    errors: list[str] = []
    summary: list[str] = []

    # Repo-relative when the config lives in the tree (the normal case), absolute
    # otherwise — `relative_to` raises rather than falling back on its own.
    try:
        config_label = args.config.resolve().relative_to(REPO_ROOT)
    except ValueError:
        config_label = args.config

    for entry in entries:
        where = f"{config_label}:{entry.line_no}"
        crate = entry.meta.get("crate")
        recheck_raw = entry.meta.get("recheck")

        if not crate or not recheck_raw:
            errors.append(
                f"{where}: {entry.advisory_id} is missing its `# suppression-meta:` "
                "line. Every ignore entry needs "
                "`# suppression-meta: crate=<name> recheck=YYYY-MM-DD` directly above it."
            )
            continue

        try:
            recheck = dt.date.fromisoformat(recheck_raw)
        except ValueError:
            errors.append(
                f"{where}: {entry.advisory_id} has recheck={recheck_raw!r}, "
                "which is not an ISO YYYY-MM-DD date."
            )
            continue

        days_left = (recheck - args.today).days
        if days_left < 0:
            errors.append(
                f"{where}: {entry.advisory_id} (crate `{crate}`) passed its re-check "
                f"date {recheck} {-days_left} day(s) ago. Confirm the advisory is still "
                "unreachable and push the date out, or delete the entry."
            )
        elif days_left <= args.threshold_days:
            annotate(
                "warning",
                f"{entry.advisory_id} (crate `{crate}`) is due for re-check in "
                f"{days_left} day(s), on {recheck}.",
            )

        if args.skip_reachability:
            summary.append(f"- `{entry.advisory_id}` — `{crate}`, re-check {recheck} (reachability not checked)")
            continue

        if cargo_tree_reachable(crate, args.manifest_path, all_features=False):
            errors.append(
                f"{where}: {entry.advisory_id} suppresses an advisory against `{crate}`, "
                f"but `{crate}` is NOW IN THE BUILD GRAPH — the non-reachability premise "
                "for this suppression no longer holds and the advisory is live in shipped "
                f"code. Reproduce with:\n"
                f"    cargo tree -i {crate} --manifest-path {args.manifest_path} --target all"
            )
            continue

        if cargo_tree_reachable(crate, args.manifest_path, all_features=True):
            annotate(
                "warning",
                f"`{crate}` is absent from the default build graph but reachable under "
                f"--all-features. {entry.advisory_id} stays suppressed for shipped code, "
                "but a feature flip would make it live.",
            )

        summary.append(
            f"- `{entry.advisory_id}` — `{crate}` not in build graph, re-check {recheck} "
            f"({days_left} day(s) away)"
        )

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write("## cargo-audit suppression guard\n\n")
            fh.write("\n".join(summary) if summary else "_no verified entries_")
            fh.write("\n")
            if errors:
                fh.write("\n### Violations\n\n")
                for err in errors:
                    fh.write(f"- {err}\n")

    for line in summary:
        print(line)

    if errors:
        for err in errors:
            annotate("error", err)
        print(
            f"\n{len(errors)} suppression(s) in {config_label} are no longer justified.",
            file=sys.stderr,
        )
        return 1

    print(f"\nAll {len(entries)} suppression(s) in {config_label} still justified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
