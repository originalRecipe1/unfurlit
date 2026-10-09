#!/usr/bin/env python3
"""Stage the gallery-dl runtime from checked-out sources; no downloads or build backends."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("app/gallery-dl/sources.json")
PACKAGES = {"gallery_dl", "requests", "urllib3", "idna", "certifi", "charset_normalizer"}


def read_manifest(root: Path) -> list[dict]:
    specs = json.loads((root / MANIFEST).read_text())
    if len(specs) != len(PACKAGES) or {s["package"] for s in specs} != PACKAGES:
        raise ValueError("Review changes to the six gallery-dl runtime packages")
    for spec in specs:
        if not re.fullmatch(r"\d+(?:\.\d+)+", spec["version"]):
            raise ValueError(f"Unexpected release version: {spec['version']}")
        if not re.fullmatch(r"[0-9a-f]{40}", spec["commit"]):
            raise ValueError("Expected a pinned source commit")
    return specs


def checked_path(root: Path, relative: str) -> Path:
    path = Path(relative)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError(f"Unsafe source path: {relative}")
    result = root / path
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Source path escapes checkout: {relative}")
    return result


def excluded(path: Path) -> bool:
    return "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo", ".pyx", ".pxd"}


def stage(root: Path, output: Path) -> None:
    specs = read_manifest(root)
    # Read everything before touching the output, so missing submodules fail clearly.
    files: dict[str, bytes] = {}
    for spec in specs:
        checkout = checked_path(root, spec["submodule"])
        package = checked_path(checkout, spec["source"])
        if not (package / "__init__.py").is_file():
            raise ValueError(f"Missing {package}; run git submodule update --init --recursive")
        for file in sorted(package.rglob("*")):
            relative = file.relative_to(package)
            if file.is_symlink():
                raise ValueError(f"Unexpected source symlink: {file}")
            if not file.is_file() or excluded(relative):
                continue
            files[f"{spec['package']}/{relative.as_posix()}"] = file.read_bytes()
        for name in spec["licenses"]:
            license_file = checked_path(checkout, name)
            files[f"licenses/{spec['package']}/{name}"] = license_file.read_bytes()
        if spec["package"] == "urllib3":
            if "urllib3/_version.py" in files:
                raise ValueError("urllib3 now supplies _version.py; review the generated-file step")
            # Mirror vcs-versioning's release-wheel output, without running Hatch/pip.
            template = (root / "app/gallery-dl/urllib3_version.py.in").read_text()
            version = spec["version"]
            files["urllib3/_version.py"] = template.replace("@VERSION@", repr(version)).replace(
                "@VERSION_TUPLE@", repr(tuple(map(int, version.split("."))))).encode()
    if output.exists():
        shutil.rmtree(output)
    for name, content in sorted(files.items()):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    print(f"Staged {len(files)} source and license files from {len(specs)} pinned packages")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    stage(ROOT, args.output)


if __name__ == "__main__":
    main()
