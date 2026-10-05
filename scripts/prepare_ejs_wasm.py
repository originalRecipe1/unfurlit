#!/usr/bin/env python3
"""Replace native Rollup only in an exported EJS build tree, retaining all other pins."""

import argparse
from copy import deepcopy
import json
from pathlib import Path


WASM_ROLLUP = {
    "name": "@rollup/wasm-node",
    "version": "4.52.5",
    "resolved": "https://registry.npmjs.org/@rollup/wasm-node/-/wasm-node-4.52.5.tgz",
    "integrity": "sha512-ldY4tEzSMBHNwB8TfRpi7RRRjjyfKlwjdebw5pS1lu0xaY3g4RDc6ople2wEYulVOKVeH7ZJwRx0iw4pGtjMHg==",
    "dev": True,
    "license": "MIT",
    "dependencies": {"@types/estree": "1.0.8"},
    "bin": {"rollup": "dist/bin/rollup"},
    "engines": {"node": ">=18.0.0", "npm": ">=8.0.0"},
    "optionalDependencies": {"fsevents": "~2.3.2"},
}


def wasm_manifests(manifest: dict, lock: dict) -> tuple[dict, dict]:
    version = WASM_ROLLUP["version"]
    native = lock["packages"]["node_modules/rollup"]
    if (manifest["devDependencies"]["rollup"] != version or native["version"] != version
            or lock["packages"][""]["devDependencies"]["rollup"] != version):
        raise ValueError("Rollup version changed; review and update the matching WASM version and integrity pin")
    if native["dependencies"] != WASM_ROLLUP["dependencies"]:
        raise ValueError("Rollup dependencies changed; review the WASM lockfile replacement")
    manifest, lock = deepcopy(manifest), deepcopy(lock)
    alias = f"npm:@rollup/wasm-node@{version}"
    # A direct dependency alias also satisfies the plugins' Rollup peers. npm 9
    # rejects this alias in an `overrides` entry with "Invalid comparator".
    manifest["devDependencies"]["rollup"] = alias
    lock["packages"][""]["devDependencies"]["rollup"] = alias
    lock["packages"]["node_modules/rollup"] = deepcopy(WASM_ROLLUP)
    for name in list(lock["packages"]):
        if name.startswith("node_modules/@rollup/rollup-"):
            del lock["packages"][name]
    return manifest, lock


def prepare(directory: Path) -> None:
    if (directory / ".git").exists():
        raise ValueError("Refusing to modify a Git checkout; pass the exported EJS build directory")
    manifest_path = directory / "package.json"
    lock_path = directory / "package-lock.json"
    manifest, lock = wasm_manifests(
        json.loads(manifest_path.read_text()), json.loads(lock_path.read_text()),
    )
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    lock_path.write_text(json.dumps(lock, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    prepare(parser.parse_args().directory)
