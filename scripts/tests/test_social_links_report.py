"""Tests for the live social-link fixture check and report (no device or network)."""
import importlib.util
import contextlib
from dataclasses import replace
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ElementTree

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
        "09-25 10:00:00.500  4242  4262 E YtDlpExtractor: Extraction failed (ExtractionException): stale\n"
        "09-25 10:00:01.000  4242  4260 I SocialLinksTest: reddit-gallery: success: 3 images, from Reddit in 3.2 s\n"
        "09-25 10:00:01.500  4242  4262 E YtDlpExtractor: Extraction failed (YoutubeDLException): "
        "ERROR: [youtube] abc: Sign in to confirm you're not a bot\n"
        "09-25 10:00:02.000  4242  4260 I SocialLinksTest: yt-video: AuthenticationRequired in 6.9 s\n"
        "09-25 10:00:03.000  4242  4260 E TestRunner: at SocialLinksTest.kt:45\n"
        "09-25 10:00:04.000  4242  4260 I SocialLinksTest: missing-page: MediaUnavailable in 1.0 s\n"
    )

    def render(self):
        with tempfile.TemporaryDirectory() as directory:
            device = Path(directory) / "managedDevice" / "debug" / "pixel2Api30"
            device.mkdir(parents=True)
            (device / "TEST-pixel2Api30-_app-.xml").write_text(self.XML, encoding="utf-8")
            (device / "logcat-SocialLinksTest.txt").write_text(self.LOGCAT, encoding="utf-8")
            outcomes = report.read_results(Path(directory))
        return outcomes, report.render(self.CASES[:-1], outcomes)

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
        self.assertEqual("MediaUnavailable", outcomes["missing-page"].observed)

    def test_attaches_the_last_app_failure_log_to_a_failed_case(self):
        outcomes, markdown = self.render()
        self.assertEqual("ERROR: [youtube] abc: Sign in to confirm you're not a bot", outcomes["yt-video"].reason)
        self.assertIsNone(outcomes["reddit-gallery"].reason)
        self.assertIsNone(outcomes["missing-page"].reason)
        self.assertIn("- `yt-video` (BLOCKED): ERROR: [youtube] abc: Sign in to confirm you're not a bot", markdown)

    def test_renders_a_table_per_media_group(self):
        _, markdown = self.render()
        self.assertIn("**PASS 2 · BLOCKED 1 · LOCAL-ONLY 0 · KNOWN 0 · FAIL 2**", markdown)
        self.assertIn("5 selected cases.", markdown)
        self.assertIn("| Video | 0 | 1 | 0 | 0 | 0 |", markdown)
        self.assertIn("| Photos and galleries | 1 | 0 | 0 | 0 | 0 |", markdown)
        self.assertIn("| Mixed media | 0 | 0 | 0 | 0 | 1 |", markdown)
        self.assertIn("| Error handling | 1 | 0 | 0 | 0 | 0 |", markdown)
        self.assertIn("| BLOCKED | [yt-video](https://y/v) | 1 video | AuthenticationRequired — wrong outcome | 6.9 s |",
                      markdown)
        self.assertIn("| PASS | [reddit-gallery](https://r/g) | 3 images | success: 3 images, from Reddit | 3.2 s |",
                      markdown)
        self.assertIn("| PASS | `missing-page` | MediaUnavailable | MediaUnavailable | 1.0 s |", markdown)
        self.assertNotIn("not-selected", markdown)

    def test_reports_a_run_without_results(self):
        self.assertIn("No SocialLinksTest results were found", report.render(self.CASES, {}))


# Captured observations and complete SafeLog-redacted error strings from this run.
REFERENCE = json.loads((Path(__file__).parent / "fixtures/social-links-37472226940.json").read_text())
REFERENCE_OUTCOMES = {name: report.Outcome(**value) for name, value in REFERENCE["outcomes"].items()}
REFERENCE_CASES = [case for case in report.load_cases() if case["id"] in REFERENCE_OUTCOMES]
CASES_BY_ID = {case["id"]: case for case in REFERENCE_CASES}
# Exact SafeLog-redacted engine message from run 37477290395, reddit-video.
REDDIT_AUTH_REASON = (
    "AuthenticationRequired | ERROR: [Reddit] 6rrwyj: Account authentication is required. "
    "Use --cookies-from-browser or --cookies for the authentication. "
    "See <url> for how to manually pass cookies"
)


class ClassifierTest(unittest.TestCase):
    def classify(self, name, *, runner=False, **changes):
        outcome = replace(REFERENCE_OUTCOMES[name], **changes)
        return report.classify(CASES_BY_ID[name], outcome, runner=runner)

    def test_exact_reference_run_messages(self):
        self.assertEqual({"PASS": 50, "BLOCKED": 8, "LOCAL-ONLY": 0, "KNOWN": 3, "FAIL": 1},
                         report.counts_for(REFERENCE_CASES, REFERENCE_OUTCOMES))
        blocked = {name for name, outcome in REFERENCE_OUTCOMES.items()
                   if report.classify(CASES_BY_ID[name], outcome).status == "BLOCKED"}
        self.assertEqual({
            "youtube-video", "youtube-short-link", "youtube-big-buck-bunny", "youtube-shorts",
            "reddit-video", "reddit-native-video", "reddit-gallery-share", "reddit-external-streamable",
        }, blocked)
        # The generic sign-in prompt remains a failure during local validation.
        self.assertIn("Please sign in.", REFERENCE_OUTCOMES["youtube-shorts-sign-in-fallback"].reason)
        self.assertEqual("FAIL", self.classify("youtube-shorts-sign-in-fallback").status)
        for name, number in [("x-mixed-media", 37), ("tumblr-photo-post", 38), ("pixiv-artwork", 39)]:
            with self.subTest(name=name):
                self.assertEqual(report.Classification("KNOWN", number), self.classify(name))

    def test_both_bot_message_apostrophes_match(self):
        original = REFERENCE_OUTCOMES["youtube-video"].reason
        self.assertIn("you’re", original)
        for reason in [original, original.replace("you’re", "you're")]:
            with self.subTest(reason=reason):
                self.assertEqual("BLOCKED", self.classify("youtube-video", reason=reason).status)

    def test_exact_reddit_anonymous_non_json_error_from_run_37477290395(self):
        self.assertEqual("BLOCKED", self.classify("reddit-video", reason=REDDIT_AUTH_REASON).status)

    def test_reddit_authentication_pattern_requires_the_extractor_prefix_and_id(self):
        for reason in [
            REDDIT_AUTH_REASON.replace("[Reddit]", "[youtube]"),
            REDDIT_AUTH_REASON.replace("[Reddit]", "[Tumblr]"),
            REDDIT_AUTH_REASON.replace("[Reddit] 6rrwyj: ", ""),
            REDDIT_AUTH_REASON.replace(" 6rrwyj:", ":"),
            REDDIT_AUTH_REASON.replace("is required.", "is requiredOther."),
        ]:
            with self.subTest(reason=reason):
                self.assertEqual("FAIL", self.classify("reddit-video", reason=reason).status)

    def test_local_only_case_keeps_every_outcome_non_gating_only_on_the_runner(self):
        name = "youtube-shorts-sign-in-fallback"
        for outcome in [
            REFERENCE_OUTCOMES[name],
            report.Outcome(True, "success: 1 video"),
            report.Outcome(False, "NetworkFailure", "wrong outcome"),
            report.Outcome(False, "test timed out"),
            report.Outcome(False, "success: 1 image", "missing video"),
        ]:
            with self.subTest(outcome=outcome):
                self.assertEqual("LOCAL-ONLY", report.classify(CASES_BY_ID[name], outcome, runner=True).status)
                self.assertEqual("PASS" if outcome.passed else "FAIL",
                                 report.classify(CASES_BY_ID[name], outcome).status)
        self.assertEqual("FAIL", self.classify("youtube-video", runner=True,
                         reason=REFERENCE_OUTCOMES[name].reason).status)

    def test_missing_local_only_case_is_reported_without_a_case_failure_on_runner(self):
        case = CASES_BY_ID["youtube-shorts-sign-in-fallback"]
        self.assertEqual({"PASS": 0, "BLOCKED": 0, "LOCAL-ONLY": 1, "KNOWN": 0, "FAIL": 0},
                         report.counts_for([case], {}, runner=True))
        markdown = report.render([case], {}, runner=True)
        self.assertIn("1 selected cases did not complete (0 FAIL)", markdown)
        self.assertIn("no test result", markdown)

    def test_other_authentication_failures_are_not_runner_blocks(self):
        for reason in [None, "AuthenticationRequired", "Please sign in.", "Sign in to confirm your age.",
                       "This is a private video; login required", "HTTP Error 403: Forbidden",
                       "Sign in to confirm you're not a botany expert", "403 BlockedOther",
                       "blocked by network securityOther"]:
            with self.subTest(reason=reason):
                self.assertEqual("FAIL", self.classify("youtube-video", reason=reason).status)

    def test_block_requires_authentication_and_an_expectation_assertion(self):
        self.assertEqual("FAIL", self.classify("youtube-video", observed="NetworkFailure").status)
        self.assertEqual("FAIL", self.classify("youtube-video", problems="").status)
        self.assertEqual("PASS", self.classify("youtube-video", passed=True).status)

    def test_changed_known_issue_failure_remains_unexpected(self):
        changes = [
            ("x-mixed-media", {"observed": "success: 1 image, from Twitter"}),
            ("x-mixed-media", {"problems": "missing image; 1 item instead of 2; unexpected soundtrack"}),
            ("tumblr-photo-post", {"reason": "NetworkFailure | DNS lookup failed"}),
            ("pixiv-artwork", {"reason": "AuthenticationRequired | Account has been suspended"}),
            ("pixiv-artwork", {"reason": None}),
            ("tumblr-photo-post", {"reason": None}),
        ]
        for name, fields in changes:
            with self.subTest(name=name, fields=fields):
                self.assertEqual("FAIL", self.classify(name, **fields).status)
        case = dict(CASES_BY_ID["x-mixed-media"], id="different-mixed-media")
        self.assertEqual("FAIL", report.classify(case, REFERENCE_OUTCOMES["x-mixed-media"]).status)

    def test_known_mapping_does_not_excuse_changed_expectations(self):
        case = dict(CASES_BY_ID["x-mixed-media"], count=3)
        self.assertEqual("FAIL", report.classify(case, REFERENCE_OUTCOMES["x-mixed-media"]).status)

    def test_passing_known_cases_are_flagged_for_issue_review(self):
        outcomes = dict(REFERENCE_OUTCOMES)
        for name in report.KNOWN_ISSUES:
            outcomes[name] = report.Outcome(True, "as expected")
            self.assertEqual("PASS", report.classify(CASES_BY_ID[name], outcomes[name]).status)
        markdown = report.render(REFERENCE_CASES, outcomes)
        self.assertIn("Known issues now passing (3)", markdown)
        for number in [37, 38, 39]:
            self.assertIn(f"review [#{number}]({report.ISSUES_URL}{number}) for closure", markdown)
        self.assertEqual({"PASS": 53, "BLOCKED": 8, "LOCAL-ONLY": 0, "KNOWN": 0, "FAIL": 1},
                         report.counts_for(REFERENCE_CASES, outcomes))

    def test_selected_missing_case_is_a_failure(self):
        case = CASES_BY_ID["pixiv-artwork"]
        self.assertEqual({"PASS": 0, "BLOCKED": 0, "LOCAL-ONLY": 0, "KNOWN": 0, "FAIL": 1}, report.counts_for([case], {}))
        self.assertIn("selected case did not complete", report.render([case], {}))

    def test_classification_uses_the_reason_beyond_the_display_limit(self):
        reason = "diagnostic context " * 30 + REFERENCE_OUTCOMES["youtube-video"].reason
        outcome = replace(REFERENCE_OUTCOMES["youtube-video"], reason=reason)
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            write_reference_run(folder, {"youtube-video": outcome})
            parsed = report.read_results(folder)["youtube-video"]
        self.assertEqual(reason, parsed.reason)
        self.assertEqual("BLOCKED", report.classify(CASES_BY_ID["youtube-video"], parsed).status)


def write_reference_run(folder, outcomes):
    suite = ElementTree.Element("testsuite")
    for name, outcome in outcomes.items():
        case = ElementTree.SubElement(suite, "testcase", {
            "classname": "io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.SocialLinksTest",
            "name": f"extractsExpectedMediaOrReportsExpectedFailure[{name}]", "time": "1.0",
        })
        if not outcome.passed:
            expectation = report.expectation(CASES_BY_ID[name])
            ElementTree.SubElement(case, "failure").text = (
                f"java.lang.AssertionError: {name}: expected [success: {expectation}] "
                f"but observed [{outcome.observed}] ({outcome.problems})\nstack trace"
            )
        log = ""
        if outcome.reason:
            log += f"10-06 13:40:00.000  1714  1736 E YtDlpExtractor: Extraction failed (ExtractionException): {outcome.reason}\n"
        log += f"10-06 13:40:01.000  1714  1736 I SocialLinksTest: {name}: {outcome.observed} in 1.0 s\n"
        (folder / f"logcat-{name}.txt").write_text(log)
    ElementTree.ElementTree(suite).write(folder / "TEST-results.xml", encoding="utf-8")


class ReportGateTest(unittest.TestCase):
    def invoke(self, outcomes, *, selected="", step="failure", runner=False, retries=None, retry_step="success"):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            initial = folder / "initial"
            initial.mkdir()
            write_reference_run(initial, outcomes)
            retry_args = []
            if retries is not None:
                retry = folder / "retry"
                retry.mkdir()
                write_reference_run(retry, retries)
                retry_args = ["--retry-results", str(retry), "--retry-step-outcome", retry_step]
            output = folder / "classified.json"
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                status = report.main(["--results", str(initial), "--link-ids", selected,
                                      "--test-step-outcome", step, "--json-output", str(output)]
                                     + (["--runner"] if runner else []) + retry_args)
            return status, json.loads(output.read_text()), stdout.getvalue()

    def test_reference_run_preserves_the_unexpected_sign_in_failure(self):
        status, data, markdown = self.invoke(REFERENCE_OUTCOMES)
        self.assertEqual(1, status)
        self.assertEqual({"PASS": 50, "BLOCKED": 8, "LOCAL-ONLY": 0, "KNOWN": 3, "FAIL": 1}, data["counts"])
        self.assertEqual([], data["run_problems"])
        self.assertIn("**PASS 50 · BLOCKED 8 · LOCAL-ONLY 0 · KNOWN 3 · FAIL 1**", markdown)
        self.assertIn("KNOWN (#37)", markdown)

    def test_only_known_and_blocked_failures_do_not_fail_the_gate(self):
        ids = [name for name in REFERENCE_OUTCOMES if name != "youtube-shorts-sign-in-fallback"]
        status, data, _ = self.invoke(REFERENCE_OUTCOMES, selected=",".join(ids))
        self.assertEqual(0, status)
        self.assertEqual({"PASS": 50, "BLOCKED": 8, "LOCAL-ONLY": 0, "KNOWN": 3, "FAIL": 0}, data["counts"])

    def test_runner_gate_accepts_reddit_auth_block_and_reports_local_only_separately(self):
        outcomes = dict(REFERENCE_OUTCOMES)
        outcomes["reddit-video"] = replace(outcomes["reddit-video"], reason=REDDIT_AUTH_REASON)
        status, data, markdown = self.invoke(outcomes, runner=True)
        self.assertEqual(0, status)
        self.assertTrue(data["runner"])
        self.assertEqual({"PASS": 50, "BLOCKED": 8, "LOCAL-ONLY": 1, "KNOWN": 3, "FAIL": 0}, data["counts"])
        self.assertEqual([], data["run_problems"])
        self.assertIn("**PASS 50 · BLOCKED 8 · LOCAL-ONLY 1 · KNOWN 3 · FAIL 0**", markdown)
        local = next(case for case in data["cases"] if case["status"] == "LOCAL-ONLY")
        self.assertEqual("youtube-shorts-sign-in-fallback", local["id"])
        self.assertIn("Please sign in.", local["reason"])
        self.assertIn("local pre-release run", markdown)

    def test_local_only_case_does_not_gate_for_success_failure_or_timeout(self):
        name = "youtube-shorts-sign-in-fallback"
        for outcome in [REFERENCE_OUTCOMES[name], report.Outcome(True, "success: 1 video"),
                        report.Outcome(False, "test timed out")]:
            with self.subTest(outcome=outcome):
                status, data, _ = self.invoke({name: outcome}, selected=name, runner=True,
                                             step="success" if outcome.passed else "failure")
                self.assertEqual(0, status)
                self.assertEqual("LOCAL-ONLY", data["cases"][0]["status"])
                self.assertEqual(outcome.observed, data["cases"][0]["observed"])

    def test_other_generic_sign_in_failures_still_gate_on_the_runner(self):
        outcomes = dict(REFERENCE_OUTCOMES)
        outcomes["youtube-video"] = replace(outcomes["youtube-video"], reason="Please sign in.")
        status, data, _ = self.invoke(outcomes, runner=True)
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["FAIL"])
        self.assertEqual(["youtube-video"], [case["id"] for case in data["cases"] if case["status"] == "FAIL"])

    def test_local_only_case_does_not_hide_a_test_run_failure(self):
        status, data, _ = self.invoke(REFERENCE_OUTCOMES, selected="youtube-shorts-sign-in-fallback",
                                     runner=True, step="cancelled")
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["LOCAL-ONLY"])
        self.assertTrue(data["run_problems"])

    def test_failure_without_a_case_failure_is_an_infrastructure_error(self):
        status, data, _ = self.invoke({"reddit-gallery": REFERENCE_OUTCOMES["reddit-gallery"]}, selected="reddit-gallery")
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["PASS"])
        self.assertIn("failed without a recorded case failure", data["run_problems"][0])

    def test_selected_pass_ignores_intentionally_unselected_cases(self):
        status, data, _ = self.invoke(REFERENCE_OUTCOMES, selected=" reddit-gallery ", step="success")
        self.assertEqual(0, status)
        self.assertEqual({"PASS": 1, "BLOCKED": 0, "LOCAL-ONLY": 0, "KNOWN": 0, "FAIL": 0}, data["counts"])
        self.assertEqual(1, len(data["cases"]))

    def test_missing_reports_and_incomplete_runs_fail(self):
        for outcomes, expected_failures in [({}, 62), ({"reddit-gallery": REFERENCE_OUTCOMES["reddit-gallery"]}, 61)]:
            with self.subTest(results=len(outcomes)):
                status, data, _ = self.invoke(outcomes)
                self.assertEqual(1, status)
                self.assertEqual(expected_failures, data["counts"]["FAIL"])

    def test_skipped_or_cancelled_test_step_is_not_accepted(self):
        for step in ["", "skipped", "cancelled"]:
            with self.subTest(step=step):
                status, data, _ = self.invoke(REFERENCE_OUTCOMES, selected="reddit-video", step=step)
                self.assertEqual(1, status)
                self.assertTrue(data["run_problems"])

    def test_unrecognized_failure_with_auth_log_stays_a_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            write_reference_run(folder, {"youtube-video": REFERENCE_OUTCOMES["youtube-video"]})
            path = folder / "TEST-results.xml"
            tree = ElementTree.parse(path)
            tree.find(".//failure").text = "org.junit.runners.model.TestTimedOutException: test timed out"
            tree.write(path)
            outcome = report.read_results(folder)["youtube-video"]
        self.assertEqual("AuthenticationRequired", outcome.observed)
        self.assertEqual("FAIL", report.classify(CASES_BY_ID["youtube-video"], outcome).status)

    def test_invalid_selection_fails_fixture_check(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, report.main(["--check", "--link-ids", "not-a-fixture-id"]))

    def test_retried_pass_counts_as_pass_and_preserves_the_original_error(self):
        initial = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
        retry = report.Outcome(True, "success: 1 audio, from Mixcloud")
        status, data, markdown = self.invoke({"mixcloud-show": initial}, selected="mixcloud-show", runner=True,
                                             retries={"mixcloud-show": retry})
        self.assertEqual(0, status)
        self.assertEqual({"PASS": 1, "BLOCKED": 0, "LOCAL-ONLY": 0, "KNOWN": 0, "FAIL": 0}, data["counts"])
        self.assertEqual({"retried": 1, "passed": 1}, data["retries"])
        self.assertIn("PASS (retried)", markdown)
        self.assertIn("Retried: **1** cases; **1 PASS (retried)**", markdown)
        self.assertIn("`mixcloud-show` (PASS, initial): timeout", markdown)
        self.assertTrue(data["cases"][0]["retried"])
        attempts = data["cases"][0]["attempts"]
        self.assertEqual("timeout", attempts[0]["reason"])
        self.assertFalse(attempts[0]["passed"])
        self.assertTrue(attempts[1]["passed"])

    def test_two_network_failures_remain_fail_with_both_errors(self):
        initial = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
        retry = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="DNS lookup failed")
        status, data, markdown = self.invoke({"mixcloud-show": initial}, selected="mixcloud-show", runner=True,
                                             retries={"mixcloud-show": retry}, retry_step="failure")
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["FAIL"])
        self.assertEqual({"retried": 1, "passed": 0}, data["retries"])
        self.assertEqual(["timeout", "DNS lookup failed"], [a["reason"] for a in data["cases"][0]["attempts"]])
        self.assertIn("`mixcloud-show` (FAIL, initial): timeout", markdown)
        self.assertIn("`mixcloud-show` (FAIL, retry): DNS lookup failed", markdown)

    def test_different_retry_failure_is_classified_normally_without_counting_as_pass(self):
        for name, retry, expected in [
            ("reddit-video", replace(REFERENCE_OUTCOMES["reddit-video"], reason=REDDIT_AUTH_REASON), "BLOCKED"),
            ("pixiv-artwork", REFERENCE_OUTCOMES["pixiv-artwork"], "KNOWN"),
            ("mixcloud-show", report.Outcome(False, "MediaUnavailable", "wrong outcome", reason="Stream deleted"), "FAIL"),
        ]:
            with self.subTest(name=name):
                initial = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
                status, data, markdown = self.invoke({name: initial}, selected=name, runner=True,
                                                     retries={name: retry}, retry_step="failure")
                self.assertEqual(int(expected == "FAIL"), status)
                self.assertEqual(expected, data["cases"][0]["status"])
                self.assertEqual(0, data["counts"]["PASS"])
                self.assertEqual({"retried": 1, "passed": 0}, data["retries"])
                self.assertIn(f"`{name}` ({expected}, initial): timeout", markdown)
                self.assertEqual(retry.reason, data["cases"][0]["attempts"][1]["reason"])

    def test_tumblr_network_failure_remains_known_when_retry_matches_issue_38(self):
        name = "tumblr-photo-post"
        initial = REFERENCE_OUTCOMES[name]
        self.assertEqual([name], report.network_retry_ids([CASES_BY_ID[name]], {name: initial}))
        status, data, markdown = self.invoke({name: initial}, selected=name, runner=True,
                                             retries={name: initial}, retry_step="failure")
        self.assertEqual(0, status)
        self.assertEqual(1, data["counts"]["KNOWN"])
        self.assertEqual(0, data["counts"]["FAIL"])
        self.assertEqual(38, data["cases"][0]["issue"])
        self.assertEqual({"retried": 1, "passed": 0}, data["retries"])
        self.assertEqual([initial.reason, initial.reason], [a["reason"] for a in data["cases"][0]["attempts"]])
        self.assertIn("KNOWN (#38)", markdown)

    def test_recovered_known_case_is_flagged_even_when_it_needed_a_retry(self):
        name = "tumblr-photo-post"
        status, data, markdown = self.invoke({name: REFERENCE_OUTCOMES[name]}, selected=name, runner=True,
                                             retries={name: report.Outcome(True, "success: 4 images, from Tumblr")})
        self.assertEqual(0, status)
        self.assertEqual(1, data["counts"]["PASS"])
        self.assertIn("PASS (retried) — [#38 now passing]", markdown)
        self.assertIn("review [#38]", markdown)

    def test_missing_retry_result_fails_instead_of_accepting_the_initial_result(self):
        initial = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
        status, data, markdown = self.invoke({"mixcloud-show": initial}, selected="mixcloud-show", runner=True, retries={})
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["FAIL"])
        self.assertEqual("no retry result", data["cases"][0]["observed"])
        self.assertIn("timeout", markdown)

    def test_retry_of_another_outcome_is_rejected_and_cannot_replace_it(self):
        name = "youtube-video"
        status, data, _ = self.invoke({name: REFERENCE_OUTCOMES[name]}, selected=name, runner=True,
                                      retries={name: report.Outcome(True, "success: 1 video")})
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["BLOCKED"])
        self.assertEqual(0, data["counts"]["PASS"])
        self.assertIn("not selected for a network retry", data["run_problems"][0])

    def test_retry_pass_does_not_hide_a_gradle_failure_outside_case_assertions(self):
        initial = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
        status, data, _ = self.invoke({"mixcloud-show": initial}, selected="mixcloud-show", runner=True,
                                      retries={"mixcloud-show": report.Outcome(True, "success: 1 audio")}, retry_step="failure")
        self.assertEqual(1, status)
        self.assertEqual(1, data["counts"]["PASS"])
        self.assertIn("retry step failed without a recorded case failure", data["run_problems"][0])


class RetrySelectionTest(unittest.TestCase):
    def test_selector_includes_known_network_failures_but_no_other_outcome(self):
        outcomes = dict(REFERENCE_OUTCOMES)
        outcomes["mixcloud-show"] = report.Outcome(False, "NetworkFailure", "wrong outcome", reason="timeout")
        outcomes["youtube-shorts-sign-in-fallback"] = report.Outcome(False, "NetworkFailure", "wrong outcome")
        self.assertEqual(["youtube-shorts-sign-in-fallback", "tumblr-photo-post", "mixcloud-show"],
                         report.network_retry_ids(REFERENCE_CASES, outcomes))
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            write_reference_run(folder, outcomes)
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                status = report.main(["--results", str(folder), "--retry-link-ids",
                                      "--link-ids", "tumblr-photo-post,mixcloud-show,youtube-video"])
        self.assertEqual(0, status)
        self.assertEqual("tumblr-photo-post,mixcloud-show\n", stdout.getvalue())

    def test_a_retry_is_never_selected_for_another_retry(self):
        name = "tumblr-photo-post"
        original = {name: REFERENCE_OUTCOMES[name]}
        merged, problems = report.merge_retries([CASES_BY_ID[name]], original, original)
        self.assertEqual([], problems)
        self.assertEqual([], report.network_retry_ids([CASES_BY_ID[name]], merged))

    def test_test_crash_with_a_network_log_is_not_selected(self):
        name = "mixcloud-show"
        self.assertEqual([], report.network_retry_ids([CASES_BY_ID[name]], {
            name: report.Outcome(False, "NetworkFailure", reason="timeout"),
        }))


if __name__ == "__main__":
    unittest.main()
