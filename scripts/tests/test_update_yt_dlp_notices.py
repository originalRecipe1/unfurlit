"""Keep automated extractor updates from leaving stale dependency notices."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("update_yt_dlp_notices", Path(__file__).parents[1] / "update_yt_dlp_notices.py")
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class ExtractorNoticesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "gradle").mkdir()
        (self.root / "third_party/yt-dlp-ejs").mkdir(parents=True)
        (self.root / "gradle/libs.versions.toml").write_text('ytDlpEngine = "2026.09.12"\nytDlpEjs = "0.9.0"\n')
        self.lock = {
            "packages": {
                "": {"dependencies": {"astring": "2.0.0", "meriyah": "7.0.0"}},
                "node_modules/astring": {"version": "2.0.0", "license": "MIT"},
                "node_modules/meriyah": {"version": "7.0.0", "license": "BSD-2-Clause"},
                "node_modules/rollup": {"version": "4.0.0", "license": "MIT", "dev": True},
            },
        }
        self.notices = self.root / "THIRD_PARTY_NOTICES.md"
        self.original = (
            "Other notices are preserved.\n"
            "| yt-dlp | 2026.08.19 | Unlicense |\n"
            "| yt-dlp-ejs, bundled YouTube JavaScript challenge solver | 0.8.0 | Unlicense |\n"
            "| astring, bundled in the EJS solver | 1.9.0 | MIT |\n"
            "| meriyah, bundled in the EJS solver | 6.1.4 | ISC |\n"
        )
        self.notices.write_text(self.original)

    def run_update(self, *args):
        (self.root / "third_party/yt-dlp-ejs/package-lock.json").write_text(json.dumps(self.lock))
        with patch.object(updater, "ROOT", self.root), patch("sys.argv", ["update_yt_dlp_notices.py", *args]):
            updater.main()

    def test_syncs_catalog_versions_and_locked_dependency_versions_and_licenses(self):
        self.run_update()
        self.assertEqual(
            "Other notices are preserved.\n"
            "| yt-dlp | 2026.09.12 | Unlicense |\n"
            "| yt-dlp-ejs, bundled YouTube JavaScript challenge solver | 0.9.0 | Unlicense |\n"
            "| astring, bundled in the EJS solver | 2.0.0 | MIT |\n"
            "| meriyah, bundled in the EJS solver | 7.0.0 | BSD-2-Clause |\n",
            self.notices.read_text(),
        )
        self.run_update("--check")

    def test_check_rejects_stale_notices_without_writing(self):
        with self.assertRaisesRegex(SystemExit, "notices are stale"):
            self.run_update("--check")
        self.assertEqual(self.original, self.notices.read_text())

    def test_new_runtime_dependency_requires_review(self):
        self.lock["packages"]["node_modules/new-parser"] = {"version": "1.0.0", "license": "MIT"}
        with self.assertRaisesRegex(RuntimeError, "runtime dependencies changed; unexpected: node_modules/new-parser"):
            self.run_update()
        self.assertEqual(self.original, self.notices.read_text())

    def test_dev_optional_dependency_is_identified_for_runtime_review(self):
        self.lock["packages"]["node_modules/shared-parser"] = {
            "version": "1.0.0", "license": "MIT", "devOptional": True,
        }
        with self.assertRaisesRegex(RuntimeError, r"shared-parser \(devOptional: may be used at runtime\)"):
            self.run_update()
        self.assertEqual(self.original, self.notices.read_text())

    def test_missing_notice_row_does_not_write_partial_update(self):
        original = self.original.replace("| astring, bundled in the EJS solver | 1.9.0 | MIT |\n", "")
        self.notices.write_text(original)
        with self.assertRaisesRegex(RuntimeError, "exactly one notice row for astring"):
            self.run_update()
        self.assertEqual(original, self.notices.read_text())


if __name__ == "__main__":
    unittest.main()
