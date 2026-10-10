#!/usr/bin/env python3
"""Compare the assembled source runtime with hash-pinned wheels (verification only)."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import urllib.request
from urllib.parse import urlparse
import zipfile

from prepare_gallery_dl_sources import ROOT, read_manifest

ENGINE = Path("app/build/generated/unfurlitYtDlp/res/raw/gallerydl")
NOTICE_NAMES = {
    "gallery_dl": "gallery-dl, in the separately run image engine",
    "requests": "Requests, in the image engine",
    "urllib3": "urllib3, in the image engine",
    "idna": "idna, in the image engine",
    "certifi": "certifi, in the image engine",
    "charset_normalizer": "charset-normalizer, in the image engine",
}


def updated_notices(text: str, specs: list[dict]) -> str:
    for spec in specs:
        name = NOTICE_NAMES[spec["package"]]
        text, count = re.subn(rf"^(\| {re.escape(name)} \| )[^|]+( \|[^\n]*)$",
                             lambda m: m[1] + spec["version"] + m[2], text, flags=re.MULTILINE)
        if count != 1:
            raise ValueError(f"Expected exactly one notice row for {name}")
    return text


def check_pins(root: Path, specs: list[dict]) -> None:
    for spec in specs:
        checkout = root / spec["submodule"]
        head = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if head != spec["commit"]:
            raise ValueError(f"{spec['package']}: submodule HEAD differs from the source manifest")
        dirty = subprocess.check_output(["git", "-C", str(checkout), "status", "--porcelain",
                                         "--untracked-files=all"], text=True)
        if dirty:
            raise ValueError(f"{spec['package']}: submodule contains local changes")
    notices = (root / "THIRD_PARTY_NOTICES.md").read_text()
    if updated_notices(notices, specs) != notices:
        raise ValueError("Gallery-dl notices are stale; run scripts/update_gallery_dl.py")


def verified_wheel(spec: dict, directory: Path, *, download: bool) -> Path:
    url, expected = spec["wheel"]["url"], spec["wheel"]["sha256"]
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.netloc != "files.pythonhosted.org" or not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise ValueError("Expected a hash-pinned PyPI wheel")
    file = directory / Path(parsed.path).name
    if not file.name.endswith("-py3-none-any.whl"):
        raise ValueError("Expected a pure-Python wheel")
    if not file.exists():
        if not download:
            raise ValueError(f"Missing verification wheel: {file}")
        directory.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f"{file.name}: checksum mismatch")
        file.write_bytes(data)
    if hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        raise ValueError(f"{file.name}: checksum mismatch")
    return file


def wheel_files(spec: dict, path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as wheel:
        names = wheel.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate wheel entries in {path}")
        info = [n for n in names if n.endswith(".dist-info/WHEEL")]
        if len(info) != 1 or b"Root-Is-Purelib: true" not in wheel.read(info[0]):
            raise ValueError(f"Not a pure-Python wheel: {path}")
        package = spec["package"]
        files = {n: wheel.read(n) for n in names if n.startswith(package + "/") and not n.endswith("/")}
        if not files or any(n.endswith((".so", ".pyd", ".dll", ".dylib", ".pyc")) for n in files):
            raise ValueError(f"Missing Python package or unexpected compiled files in {path}")
        for license in spec["licenses"]:
            matches = [n for n in names if n.endswith(".dist-info/licenses/" + license)
                       or n.endswith(".dist-info/" + license)]
            if len(matches) != 1:
                raise ValueError(f"Expected one {license} in {path}")
            files[f"licenses/{package}/{license}"] = wheel.read(matches[0])
        return files


def compare(engine: Path, expected: dict[str, bytes]) -> list[str]:
    with zipfile.ZipFile(engine) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            return ["duplicate engine entries"]
        actual = {n: archive.read(n) for n in names}
    return (["missing: " + n for n in sorted(expected.keys() - actual.keys())]
            + ["unexpected: " + n for n in sorted(actual.keys() - expected.keys())]
            + ["different bytes: " + n for n in sorted(expected.keys() & actual.keys()) if expected[n] != actual[n]])


def verify(root: Path, engine: Path, wheels: Path, *, download: bool = True) -> None:
    specs = read_manifest(root)
    check_pins(root, specs)
    expected = {"__main__.py": (root / "app/gallery-dl/__main__.py").read_bytes()}
    for spec in specs:
        files = wheel_files(spec, verified_wheel(spec, wheels, download=download))
        expected.update(files)
        print(f"{spec['package']} {spec['version']}: {sum(n.endswith('.py') for n in files)} Python files, "
              f"{len(files)} package/license files")
    differences = compare(engine, expected)
    if differences:
        raise ValueError("Source/wheel comparison failed:\n" + "\n".join(differences))
    print(f"PASS: {len(expected) - 1} package/license files match all six pinned wheels byte for byte; "
          "entry point matches source; no extra files")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, default=ROOT / ENGINE)
    parser.add_argument("--wheels", type=Path, default=ROOT / "build/gallery-dl-wheel-check")
    parser.add_argument("--offline", action="store_true", help="Require all verification wheels locally")
    args = parser.parse_args()
    try:
        verify(ROOT, args.engine, args.wheels, download=not args.offline)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
