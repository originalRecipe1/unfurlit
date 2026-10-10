#!/usr/bin/env python3
"""Pin six explicit upstream tags, regenerate gallery-dl, and verify against release wheels."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import urllib.request

from check_gallery_dl import ENGINE, updated_notices, verify
from prepare_gallery_dl_sources import MANIFEST, ROOT, read_manifest


def tag_version(tag: str) -> str:
    if not re.fullmatch(r"v?\d+(?:\.\d+){1,3}", tag):
        raise ValueError(f"Expected a stable release tag, got {tag!r}")
    # certifi's tag is zero-padded (2026.07.22), its package version is not.
    return ".".join(str(int(n)) for n in tag.removeprefix("v").split("."))


def select_wheel(metadata: dict, package: str, version: str) -> dict:
    wheels = [f for f in metadata["urls"] if f["packagetype"] == "bdist_wheel"
              and f["filename"] == f"{package}-{version}-py3-none-any.whl" and not f.get("yanked", False)]
    if len(wheels) != 1:
        raise ValueError(f"Expected one non-yanked pure-Python wheel for {package} {version}")
    wheel = wheels[0]
    if not wheel["url"].startswith("https://files.pythonhosted.org/") or not re.fullmatch(r"[a-f0-9]{64}", wheel["digests"]["sha256"]):
        raise ValueError("Invalid wheel URL or digest")
    return {"url": wheel["url"], "sha256": wheel["digests"]["sha256"]}


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def apply_update(root: Path, old: list[dict], new: list[dict], build_and_compare) -> None:
    """Leave failed upgrades out of the working tree; never patch a submodule file."""
    paths = [MANIFEST, Path("gradle/libs.versions.toml"), Path("THIRD_PARTY_NOTICES.md")]
    original = {p: (root / p).read_bytes() for p in paths}
    engine = root / ENGINE
    original_engine = engine.read_bytes() if engine.exists() else None
    heads = {s["submodule"]: git(root / s["submodule"], "rev-parse", "HEAD") for s in old}
    try:
        for spec in new:
            git(root / spec["submodule"], "checkout", "--detach", spec["commit"])
        (root / MANIFEST).write_text(json.dumps(new, indent=2) + "\n")
        version = next(s["version"] for s in new if s["package"] == "gallery_dl")
        catalog, count = re.subn(r'^galleryDl = "[^"]+"$', f'galleryDl = "{version}"',
                                original[paths[1]].decode(), flags=re.MULTILINE)
        if count != 1:
            raise ValueError("Expected one galleryDl catalog version")
        (root / paths[1]).write_text(catalog)
        (root / paths[2]).write_text(updated_notices(original[paths[2]].decode(), new))
        build_and_compare()
    except BaseException:
        for path, data in original.items():
            (root / path).write_bytes(data)
        for path, head in heads.items():
            git(root / path, "checkout", "--detach", head)
        if original_engine is None:
            engine.unlink(missing_ok=True)
        else:
            engine.write_bytes(original_engine)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gallery-dl", "requests", "urllib3", "idna", "certifi", "charset-normalizer"):
        parser.add_argument("--" + name, required=True, metavar="TAG")
    args = parser.parse_args()
    try:
        old = read_manifest(ROOT)
        # Tags may be fetched, but no existing work may be overwritten on checkout/rollback.
        if git(ROOT, "status", "--porcelain", "--untracked-files=all"):
            raise ValueError("Start with a clean worktree (including submodules)")
        new = copy.deepcopy(old)
        for spec in new:
            tag = getattr(args, spec["package"])
            version = tag_version(tag)
            checkout = ROOT / spec["submodule"]
            if git(checkout, "status", "--porcelain", "--untracked-files=all"):
                raise ValueError(f"Dirty submodule: {spec['submodule']}")
            # Use the reviewed manifest URL, not an arbitrary configured local remote.
            git(checkout, "fetch", "--no-tags", spec["repo"], f"refs/tags/{tag}")
            commit = git(checkout, "rev-parse", "FETCH_HEAD^{commit}")
            with urllib.request.urlopen(f"https://pypi.org/pypi/{spec['package']}/{version}/json", timeout=60) as response:
                wheel = select_wheel(json.load(response), spec["package"], version)
            if tag == spec["tag"] and (commit != spec["commit"] or wheel != spec["wheel"]):
                raise ValueError(f"Pinned immutable release changed: {spec['package']} {tag}")
            spec.update(tag=tag, version=version, commit=commit, wheel=wheel)

        def build_and_compare():
            subprocess.run([str(ROOT / "gradlew"), ":app:preparePinnedGalleryDl"], cwd=ROOT, check=True)
            verify(ROOT, ROOT / ENGINE, ROOT / "build/gallery-dl-wheel-check")

        apply_update(ROOT, old, new, build_and_compare)
        print("Source pins, catalog and notices updated; engine regenerated and wheel comparison passed.")
        print("Review git diff (including submodule pins); app release numbering is unchanged. No commit created.")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
