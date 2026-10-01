#!/usr/bin/env python3
"""Synchronize extractor notices with the pinned catalog and EJS npm lockfile."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def updated_notices(notices: str, versions: str, lock: dict) -> str:
    # A changed dependency set needs a license/inclusion review before the bot
    # can update it. Version and SPDX license changes for known packages are read
    # from the same lockfile used to build the solver.
    packages = lock["packages"]
    # devOptional can also mean an optional dependency of a non-dev package.
    # Keep those in the review set; only dev:true is exclusively development use.
    runtime_packages = {name for name, package in packages.items() if name and not package.get("dev", False)}
    expected_packages = {"node_modules/astring", "node_modules/meriyah"}
    if runtime_packages != expected_packages:
        unexpected = [
            f"{name} (devOptional: may be used at runtime)" if packages[name].get("devOptional") else name
            for name in sorted(runtime_packages - expected_packages)
        ]
        missing = sorted(expected_packages - runtime_packages)
        raise RuntimeError(
            f"EJS runtime dependencies changed; unexpected: {', '.join(unexpected) or 'none'}; "
            f"missing: {', '.join(missing) or 'none'}; review and update the notices generator",
        )

    def catalog_version(key: str) -> str:
        match = re.search(rf'^{key} = "([^"]+)"$', versions, re.MULTILINE)
        if match is None:
            raise RuntimeError(f"Missing version catalog entry: {key}")
        return match.group(1)

    rows = {
        "yt-dlp": (catalog_version("ytDlpEngine"), "Unlicense"),
        "yt-dlp-ejs, bundled YouTube JavaScript challenge solver": (catalog_version("ytDlpEjs"), "Unlicense"),
    }
    for name in ("astring", "meriyah"):
        package = packages[f"node_modules/{name}"]
        rows[f"{name}, bundled in the EJS solver"] = (package["version"], package["license"])

    for component, (version, license_name) in rows.items():
        replacement = f"| {component} | {version} | {license_name} |"
        notices, count = re.subn(
            rf"^\| {re.escape(component)} \|[^\n]*$",
            lambda _: replacement,
            notices,
            flags=re.MULTILINE,
        )
        if count != 1:
            raise RuntimeError(f"Expected exactly one notice row for {component}")
    return notices


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail on stale notices without changing files")
    args = parser.parse_args()
    notices_file = ROOT / "THIRD_PARTY_NOTICES.md"
    original = notices_file.read_text(encoding="utf-8")
    updated = updated_notices(
        original,
        (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"),
        json.loads((ROOT / "third_party/yt-dlp-ejs/package-lock.json").read_text(encoding="utf-8")),
    )
    if args.check:
        if updated != original:
            raise SystemExit("Extractor notices are stale; run python3 scripts/update_yt_dlp_notices.py")
    elif updated != original:
        notices_file.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
