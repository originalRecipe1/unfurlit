"""The WASM substitution must not silently update EJS or unrelated dependencies."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("prepare_ejs_wasm", ROOT / "scripts/prepare_ejs_wasm.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class WasmRollupTest(unittest.TestCase):
    def setUp(self):
        source = ROOT / "third_party/yt-dlp-ejs"
        self.manifest = json.loads((source / "package.json").read_text())
        self.lock = json.loads((source / "package-lock.json").read_text())

    def test_replacement_preserves_every_unrelated_lock_entry_and_source_manifests(self):
        original_manifest, original_lock = deepcopy(self.manifest), deepcopy(self.lock)
        manifest, lock = prepare.wasm_manifests(self.manifest, self.lock)
        self.assertEqual(original_manifest, self.manifest)
        self.assertEqual(original_lock, self.lock)
        self.assertEqual("npm:@rollup/wasm-node@4.52.5", manifest["devDependencies"]["rollup"])
        self.assertEqual(original_manifest.get("overrides"), manifest.get("overrides"))
        self.assertEqual(prepare.WASM_ROLLUP, lock["packages"]["node_modules/rollup"])
        for name, package in original_lock["packages"].items():
            if name.startswith("node_modules/@rollup/rollup-"):
                self.assertNotIn(name, lock["packages"])
            elif name not in ("", "node_modules/rollup"):
                self.assertEqual(package, lock["packages"][name], name)
        self.assertEqual(original_manifest["dependencies"], manifest["dependencies"])

    def test_upstream_rollup_update_requires_a_matching_reviewed_wasm_pin(self):
        for change in ("manifest", "root lock", "package lock"):
            manifest, lock = deepcopy(self.manifest), deepcopy(self.lock)
            if change == "manifest":
                manifest["devDependencies"]["rollup"] = "4.53.0"
            elif change == "root lock":
                lock["packages"][""]["devDependencies"]["rollup"] = "4.53.0"
            else:
                lock["packages"]["node_modules/rollup"]["version"] = "4.53.0"
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, "version changed"):
                prepare.wasm_manifests(manifest, lock)

    def test_git_checkout_is_rejected_before_any_files_are_changed(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            (directory / ".git").write_text("gitdir: /a/submodule\n")
            path = directory / "package.json"
            path.write_text(json.dumps(self.manifest))
            original = path.read_bytes()
            with self.assertRaisesRegex(ValueError, "Git checkout"):
                prepare.prepare(directory)
            self.assertEqual(original, path.read_bytes())
