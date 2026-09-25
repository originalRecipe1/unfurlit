"""Tests for the live social-link fixture check and report (no device or network)."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "social_links_report", Path(__file__).parents[1] / "social_links_report.py"
)
report = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = report  # dataclasses resolve annotations through the module
spec.loader.exec_module(report)

ERRORS = {"UnsupportedUrl", "MediaUnavailable", "AuthenticationRequired"}


class FixtureTest(unittest.TestCase):
    def test_repository_fixture_is_valid(self):
        self.assertEqual([], report.validate(report.load_cases(), report.error_names()))

    def test_error_names_come_from_the_domain_model(self):
        self.assertTrue({"UnsupportedUrl", "MediaUnavailable", "ExtractionFailed"} <= report.error_names())

    def test_reports_invalid_entries(self):
        cases = [
            {"id": "a-video", "url": "https://a/", "expected": "success", "media": "video"},
            {"id": "a-video", "url": "https://b/", "expected": "success"},
            {"id": "Bad Id", "url": "https://c/", "expected": "Missing"},
            {"id": "gif", "url": "https://d/", "expected": "success", "media": ["gif"], "photoCount": 2},
            {"id": "counts", "url": "https://e/", "expected": "success", "count": 0, "minCount": 1},
            {"id": "blocked", "url": "https://f/", "expected": "UnsupportedUrl", "media": "image"},
        ]
        problems = "\n".join(report.validate(cases, ERRORS))
        self.assertIn("a-video: duplicate id", problems)
        self.assertIn("Bad Id: id must be", problems)
        self.assertIn("Bad Id: expected must be", problems)
        self.assertIn("gif: unknown keys photoCount", problems)
        self.assertIn("gif: media must be", problems)
        self.assertIn("counts: count must be a positive integer", problems)
        self.assertIn("counts: use either count or minCount", problems)
        self.assertIn("blocked: only successful cases can describe media", problems)

    def test_expectation_matches_the_instrumented_test_wording(self):
        self.assertEqual("16 images, with soundtrack",
                         report.expectation({"expected": "success", "media": "image", "count": 16, "soundtrack": True}))
        self.assertEqual("2 video+image",
                         report.expectation({"expected": "success", "media": ["video", "image"], "count": 2}))
        self.assertEqual("at least 2 items", report.expectation({"expected": "success", "minCount": 2}))
        self.assertEqual("1 audio", report.expectation({"expected": "success", "media": "audio", "count": 1}))
        self.assertEqual("MediaUnavailable", report.expectation({"expected": "MediaUnavailable"}))


class ReportTest(unittest.TestCase):
    CASES = [
        {"id": "yt-video", "url": "https://y/v", "expected": "success", "media": "video", "count": 1},
        {"id": "reddit-gallery", "url": "https://r/g", "expected": "success", "media": "image", "count": 3},
        {"id": "x-mixed", "url": "https://x/m", "expected": "success", "media": ["image", "video"]},
        {"id": "slow-track", "url": "https://s/t", "expected": "success", "media": "audio"},
        {"id": "missing-page", "url": "https://m/", "expected": "MediaUnavailable"},
        {"id": "not-selected", "url": "https://n/", "expected": "success"},
    ]
    XML = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" tests="6">
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[yt-video]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="7.5">
    <failure>java.lang.AssertionError: yt-video: expected [success: 1 video] but observed [AuthenticationRequired] (wrong outcome)
\tat org.junit.Assert.fail(Assert.java:89)</failure>
  </testcase>
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[reddit-gallery]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="4.0"/>
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[x-mixed]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="3.0">
    <failure message="x-mixed: expected [success: video+image] but observed [success: 1 video (Progressive), from Twitter] (missing image)">trace</failure>
  </testcase>
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[slow-track]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="210.0">
    <failure>org.junit.runners.model.TestTimedOutException: test timed out after 210000 milliseconds</failure>
  </testcase>
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[missing-page]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="1.0"/>
  <testcase name="extractsExpectedMediaOrReportsExpectedFailure[not-selected]"
      classname="io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest" time="0.0">
    <skipped/>
  </testcase>
  <testcase name="launchesApp" classname="io.github.originalrecipe1.unfurlit.MainActivityTest" time="1.0"/>
</testsuite>
"""
    LOGCAT = (
        "09-25 10:00:01.000  4242  4260 I SocialLinksTest: reddit-gallery: success: 3 images, from Reddit in 3.2 s\n"
        "09-25 10:00:02.000  4242  4260 I SocialLinksTest: yt-video: AuthenticationRequired in 6.9 s\n"
        "09-25 10:00:03.000  4242  4260 E TestRunner: at SocialLinksTest.kt:45\n"
    )

    def render(self):
        with tempfile.TemporaryDirectory() as directory:
            device = Path(directory) / "managedDevice" / "debug" / "pixel2Api30"
            device.mkdir(parents=True)
            (device / "TEST-pixel2Api30-_app-.xml").write_text(self.XML, encoding="utf-8")
            (device / "logcat-SocialLinksTest.txt").write_text(self.LOGCAT, encoding="utf-8")
            outcomes = report.read_results(Path(directory))
        return outcomes, report.render(self.CASES, outcomes)

    def test_reads_outcomes_from_junit_xml_and_logcat(self):
        outcomes, _ = self.render()
        self.assertEqual({"yt-video", "reddit-gallery", "x-mixed", "slow-track", "missing-page"}, set(outcomes))
        self.assertEqual((False, "AuthenticationRequired", "wrong outcome", 6.9),
                         (outcomes["yt-video"].passed, outcomes["yt-video"].observed,
                          outcomes["yt-video"].problems, outcomes["yt-video"].seconds))
        self.assertEqual((True, "success: 3 images, from Reddit", 3.2),
                         (outcomes["reddit-gallery"].passed, outcomes["reddit-gallery"].observed,
                          outcomes["reddit-gallery"].seconds))
        self.assertEqual("missing image", outcomes["x-mixed"].problems)
        self.assertEqual("test timed out after 210000 milliseconds", outcomes["slow-track"].observed)
        self.assertEqual("as expected", outcomes["missing-page"].observed)

    def test_renders_a_table_per_media_group(self):
        _, markdown = self.render()
        self.assertIn("**2 of 5 cases passed.**", markdown)
        self.assertIn("1 fixture cases did not run.", markdown)
        self.assertIn("| Video | 0 of 1 |", markdown)
        self.assertIn("| Photos and galleries | 1 of 1 |", markdown)
        self.assertIn("| Mixed media | 0 of 1 |", markdown)
        self.assertIn("| Error handling | 1 of 1 |", markdown)
        self.assertIn("| ❌ | [yt-video](https://y/v) | 1 video | AuthenticationRequired — wrong outcome | 6.9 s |",
                      markdown)
        self.assertIn("| ✅ | [reddit-gallery](https://r/g) | 3 images | success: 3 images, from Reddit | 3.2 s |",
                      markdown)
        self.assertIn("| ✅ | `missing-page` | MediaUnavailable | as expected | 1.0 s |", markdown)
        self.assertNotIn("not-selected", markdown)

    def test_reports_a_run_without_results(self):
        self.assertIn("No SocialLinksTest results were found", report.render(self.CASES, {}))


if __name__ == "__main__":
    unittest.main()
