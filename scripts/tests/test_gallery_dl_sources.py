"""Offline checks of staging, byte comparison, and transactional tag updates."""
import copy
import importlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).parents[1]))
prepare = importlib.import_module("prepare_gallery_dl_sources")
check = importlib.import_module("check_gallery_dl")
update = importlib.import_module("update_gallery_dl")


class SourcesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.specs = prepare.read_manifest(prepare.ROOT)
        self.write(prepare.MANIFEST, json.dumps(self.specs))
        self.write("app/gallery-dl/urllib3_version.py.in",
                   (prepare.ROOT / "app/gallery-dl/urllib3_version.py.in").read_text())
        for spec in self.specs:
            self.write(f"{spec['submodule']}/{spec['source']}/__init__.py", "# source\n")
            for name in spec["licenses"]:
                self.write(f"{spec['submodule']}/{name}", f"{spec['package']} {name}\n")

    def write(self, path, text):
        path = self.root / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_stages_data_licenses_and_generated_version_without_mutating_sources(self):
        self.write("third_party/certifi/certifi/cacert.pem", "certificate data\n")
        self.write("third_party/urllib3/src/urllib3/py.typed", "")
        self.write("third_party/urllib3/src/urllib3/contrib/worker.js", "worker source\n")
        for name in ("md.pyx", "md.pxd", "__pycache__/md.pyc", "ignored.pyo"):
            self.write("third_party/charset_normalizer/src/charset_normalizer/" + name, "excluded")
        before = {p: p.read_bytes() for p in (self.root / "third_party").rglob("*") if p.is_file()}
        out = self.root / "build/staged"
        prepare.stage(self.root, out)
        self.assertEqual("certificate data\n", (out / "certifi/cacert.pem").read_text())
        self.assertEqual("worker source\n", (out / "urllib3/contrib/worker.js").read_text())
        self.assertTrue((out / "urllib3/py.typed").is_file())
        self.assertEqual("requests NOTICE\n", (out / "licenses/requests/NOTICE").read_text())
        version = (out / "urllib3/_version.py").read_text()
        self.assertIn("__version__ = version = '2.8.0'", version)
        self.assertIn("__version_tuple__ = version_tuple = (2, 8, 0)", version)
        self.assertFalse(any(prepare.excluded(p) for p in out.rglob("*")))
        self.assertFalse(any("dist-info" in str(p) for p in out.rglob("*")))
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_missing_submodule_does_not_destroy_previous_output(self):
        self.write("build/staged/previous.py", "previous output")
        (self.root / "third_party/idna/idna/__init__.py").unlink()
        with self.assertRaisesRegex(ValueError, "submodule update"):
            prepare.stage(self.root, self.root / "build/staged")
        self.assertEqual("previous output", (self.root / "build/staged/previous.py").read_text())

    def test_rejects_source_symlinks_and_unreviewed_version_generation(self):
        link = self.root / "third_party/idna/idna/escape.py"
        link.symlink_to(self.root / prepare.MANIFEST)
        with self.assertRaisesRegex(ValueError, "symlink"):
            prepare.stage(self.root, self.root / "build/staged")
        link.unlink()
        self.write("third_party/urllib3/src/urllib3/_version.py", "upstream now supplies this")
        with self.assertRaisesRegex(ValueError, "review the generated-file step"):
            prepare.stage(self.root, self.root / "build/staged")

    def test_paths_must_stay_in_source_checkout(self):
        for path in ("../escape", "/absolute"):
            with self.assertRaises(ValueError):
                prepare.checked_path(self.root, path)


class ComparisonTest(unittest.TestCase):
    def test_changed_missing_and_unexpected_files_all_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            engine = Path(folder) / "engine.zip"
            with zipfile.ZipFile(engine, "w") as z:
                z.writestr("pkg/a.py", b"changed")
                z.writestr("pkg/extra.py", b"extra")
            errors = check.compare(engine, {"pkg/a.py": b"original", "certifi/cacert.pem": b"CA"})
            self.assertEqual(["missing: certifi/cacert.pem", "unexpected: pkg/extra.py",
                              "different bytes: pkg/a.py"], errors)
            self.assertEqual([], check.compare(engine, {"pkg/a.py": b"changed", "pkg/extra.py": b"extra"}))

    def test_bad_cached_wheel_hash_fails_without_network(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "pkg-1.0-py3-none-any.whl"
            path.write_bytes(b"tampered")
            spec = {"wheel": {"url": "https://files.pythonhosted.org/" + path.name, "sha256": "0" * 64}}
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                check.verified_wheel(spec, Path(folder), download=False)


class UpdateTest(unittest.TestCase):
    def test_tag_normalization_and_ref_injection_rejection(self):
        self.assertEqual("1.32.13", update.tag_version("v1.32.13"))
        self.assertEqual("2026.7.22", update.tag_version("2026.07.22"))
        for tag in ("main", "--help", "v1.2.3;bad", "refs/tags/v1.2.3", "1.2.3rc1"):
            with self.assertRaises(ValueError):
                update.tag_version(tag)

    def test_requires_one_non_yanked_pure_python_wheel(self):
        wheel = dict(packagetype="bdist_wheel", filename="pkg-1.2-py3-none-any.whl", yanked=False,
                     url="https://files.pythonhosted.org/pkg-1.2-py3-none-any.whl", digests={"sha256": "a" * 64})
        self.assertEqual({"url": wheel["url"], "sha256": "a" * 64}, update.select_wheel({"urls": [wheel]}, "pkg", "1.2"))
        for bad in ([], [wheel, wheel], [dict(wheel, yanked=True)], [dict(wheel, filename="pkg-1.2-cp312-manylinux.whl")]):
            with self.assertRaises(ValueError):
                update.select_wheel({"urls": bad}, "pkg", "1.2")

    def test_failed_comparison_restores_pins_metadata_and_previous_engine(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            paths = {prepare.MANIFEST: json.dumps([{"package": "gallery_dl"}]),
                     Path("gradle/libs.versions.toml"): 'galleryDl = "1.0"\n',
                     Path("THIRD_PARTY_NOTICES.md"): "| gallery-dl, in the separately run image engine | 1.0 | GPL-2.0-only |\n",
                     check.ENGINE: "previous engine"}
            for path, data in paths.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_text(data)
            spec = dict(package="gallery_dl", submodule="third_party/gallery-dl", version="1.1", commit="b" * 40)
            calls = []
            def git(path, *args):
                calls.append(args)
                return "a" * 40 if args[0] == "rev-parse" else ""
            def failure():
                (root / check.ENGINE).write_bytes(b"mismatching engine")
                raise ValueError("different bytes")
            with patch.object(update, "git", git):
                with self.assertRaisesRegex(ValueError, "different bytes"):
                    update.apply_update(root, [spec], [spec], failure)
            for path, data in paths.items():
                self.assertEqual(data, (root / path).read_text())
            self.assertIn(("checkout", "--detach", "b" * 40), calls)
            self.assertIn(("checkout", "--detach", "a" * 40), calls)


if __name__ == "__main__":
    unittest.main()
