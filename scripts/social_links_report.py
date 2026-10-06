#!/usr/bin/env python3
"""Summarize a live social-link run, or check its fixture.

Reads app/src/socialLinks/assets/social-links.json and the JUnit XML and logcat files of
an instrumented SocialLinksTest run, and prints a Markdown report grouped by media kind
(for example into $GITHUB_STEP_SUMMARY). Exits nonzero for unexpected failures or
incomplete runs. With --check it only validates the fixture and case selection.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "app" / "src" / "socialLinks" / "assets" / "social-links.json"
RESULTS = ROOT / "app" / "build" / "outputs" / "androidTest-results"
ERRORS = ROOT / "app" / "src" / "main" / "java" / "io" / "github" / "originalrecipe1" / "unfurlit" / "domain" / "model" / "ExtractionError.kt"

TEST_CLASS = "SocialLinksTest"
SUCCESS = "success"
MEDIA_KINDS = ("video", "image", "audio")
KEYS = {"id", "url", "expected", "media", "count", "minCount", "soundtrack"}
ID = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
ERROR_NAME = re.compile(r"data object (\w+)\s*:\s*ExtractionError")
EXCEPTION_PREFIX = re.compile(r"^(?:[a-z]\w*\.)+\w*(?:Error|Exception|Failure):\s*")
# Must match the assertion message in SocialLinksTest.
MESSAGE = re.compile(
    r"^(?P<id>[^:]+): expected \[(?P<expected>.*?)\] but observed \[(?P<observed>.*)\] \((?P<problems>[^()]*)\)$"
)
LOG_LINE = re.compile(
    rf"{TEST_CLASS}[^:]*: (?P<id>[a-z0-9-]+): (?P<observed>.+) in (?P<seconds>\d+(?:\.\d+)?) s\s*$"
)
# The app's own failure log; SafeLog has already removed URLs and secrets from it.
DIAGNOSTIC = re.compile(r"YtDlpExtractor[^:]*: Extraction failed \((?P<type>[^)]*)\): (?P<detail>.*?)\s*$")
MAX_REASON = 300
GROUPS = (
    ("video", "Video"),
    ("image", "Photos and galleries"),
    ("audio", "Audio"),
    ("mixed", "Mixed media"),
    ("other", "Any media"),
    ("error", "Error handling"),
)
STATUSES = ("PASS", "BLOCKED", "KNOWN", "FAIL")
ISSUES_URL = "https://github.com/originalRecipe1/unfurlit/issues/"
# Literal upstream messages, including the curly apostrophe in run 37472226940.
# "Please sign in", private videos, and other authentication failures are NOT blocks.
RUNNER_BLOCK_MESSAGES = tuple(re.compile(r"(?<!\w)" + re.escape(message) + r"(?!\w)") for message in (
    "Sign in to confirm you're not a bot",
    "Sign in to confirm you’re not a bot",
    "blocked by network security",
    "403 Blocked",
))


@dataclass
class Outcome:
    passed: bool
    observed: str
    problems: str = ""
    seconds: float | None = None
    reason: str | None = None


@dataclass(frozen=True)
class KnownIssue:
    number: int
    expected: str
    observed: str
    problems: str
    reason: str | None = None


# These exceptions belong to reporting, never to the public fixture's expectations.
# Match the recorded failure, not just its case ID or broad error category.
KNOWN_ISSUES = {
    "x-mixed-media": KnownIssue(
        37, "2 video+image", "success: 1 video (Progressive), from Twitter",
        "missing image; 1 item instead of 2",
    ),
    "tumblr-photo-post": KnownIssue(
        38, "4 images", "NetworkFailure", "wrong outcome",
        "NetworkFailure | ERROR: [Tumblr] 172687798174: Unable to download webpage: "
        "Remote end closed connection without response "
        "(caused by TransportError('Remote end closed connection without response'))",
    ),
    "pixiv-artwork": KnownIssue(
        39, "1 image", "AuthenticationRequired", "wrong outcome",
        "AuthenticationRequired | gallery-dl AuthenticationError 0: 'refresh-token' required. "
        "Run `gallery-dl oauth:pixiv` to get one.",
    ),
}


@dataclass(frozen=True)
class Classification:
    status: str
    issue: int | None = None

    @property
    def label(self) -> str:
        if self.issue is None:
            return self.status
        url = f"{ISSUES_URL}{self.issue}"
        if self.status == "PASS":
            return f"PASS — [#{self.issue} now passing]({url})"
        return f"[KNOWN (#{self.issue})]({url})"


def classify(case: dict, outcome: Outcome) -> Classification:
    issue = KNOWN_ISSUES.get(case["id"])
    same_expectation = issue is not None and case.get("expected") == SUCCESS and expectation(case) == issue.expected
    if outcome.passed:
        return Classification("PASS", issue.number if same_expectation else None)
    # Only a normal expectation assertion can be excused. A timeout/crash with
    # an authentication message elsewhere in logcat must remain a failure.
    if (outcome.observed == "AuthenticationRequired" and outcome.problems == "wrong outcome"
            and outcome.reason and any(message.search(outcome.reason) for message in RUNNER_BLOCK_MESSAGES)):
        return Classification("BLOCKED")
    if (same_expectation and outcome.observed == issue.observed
            and outcome.problems == issue.problems and outcome.reason == issue.reason):
        return Classification("KNOWN", issue.number)
    return Classification("FAIL")


def complete_outcomes(cases: list[dict], outcomes: dict[str, Outcome]) -> dict[str, Outcome]:
    return {
        case["id"]: outcomes.get(case["id"], Outcome(False, "no test result", "selected case did not complete"))
        for case in cases
    }


def counts_for(cases: list[dict], outcomes: dict[str, Outcome]) -> dict[str, int]:
    complete = complete_outcomes(cases, outcomes)
    counts = Counter(classify(case, complete[case["id"]]).status for case in cases)
    return {status: counts[status] for status in STATUSES}


def select_cases(cases: list[dict], link_ids: str) -> list[dict]:
    if not link_ids.strip():
        return cases
    selected = {name.strip() for name in link_ids.split(",")}
    unknown = selected - {case["id"] for case in cases}
    if unknown:
        raise ValueError(f"Unknown link IDs: {', '.join(sorted(unknown))}")
    return [case for case in cases if case["id"] in selected]


def error_names(source: Path = ERRORS) -> set[str]:
    return set(ERROR_NAME.findall(source.read_text(encoding="utf-8")))


def load_cases(path: Path = FIXTURE) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))


def media_kinds(case: dict) -> list[str]:
    media = case.get("media")
    if media is None:
        return []
    return [media] if isinstance(media, str) else list(media)


def validate(cases: object, errors: set[str]) -> list[str]:
    """Returns every problem in the fixture; an empty list means it is valid."""
    if not isinstance(cases, list) or not cases:
        return ["the fixture must be a non-empty JSON array"]
    problems = []
    seen = set()
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            problems.append(f"entry {index} is not an object")
            continue
        name = case.get("id")
        label = name if isinstance(name, str) else f"entry {index}"
        if not isinstance(name, str) or not ID.match(name):
            problems.append(f"{label}: id must be lowercase words joined by hyphens")
        elif name in seen:
            problems.append(f"{label}: duplicate id")
        seen.add(name)
        if unknown := sorted(set(case) - KEYS):
            problems.append(f"{label}: unknown keys {', '.join(unknown)}")
        if not isinstance(case.get("url"), str) or not case.get("url"):
            problems.append(f"{label}: url is required")
        expected = case.get("expected")
        if expected != SUCCESS and expected not in errors:
            problems.append(f"{label}: expected must be '{SUCCESS}' or one of {', '.join(sorted(errors))}")
        media = case.get("media")
        kinds = [media] if isinstance(media, str) else media
        if media is not None and (
            not isinstance(kinds, list) or not kinds
            or any(kind not in MEDIA_KINDS for kind in kinds) or len(set(kinds)) != len(kinds)
        ):
            problems.append(f"{label}: media must be one or more distinct kinds of {', '.join(MEDIA_KINDS)}")
        for key in ("count", "minCount"):
            value = case.get(key)
            if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value < 1):
                problems.append(f"{label}: {key} must be a positive integer")
        if "count" in case and "minCount" in case:
            problems.append(f"{label}: use either count or minCount")
        if "soundtrack" in case and not isinstance(case["soundtrack"], bool):
            problems.append(f"{label}: soundtrack must be true or false")
        if expected != SUCCESS and (KEYS - {"id", "url", "expected"}) & set(case):
            problems.append(f"{label}: only successful cases can describe media")
    return problems


def group_of(case: dict) -> str:
    if case.get("expected") != SUCCESS:
        return "error"
    kinds = media_kinds(case)
    if len(kinds) > 1:
        return "mixed"
    return kinds[0] if kinds else "other"


def failure_line(element: ElementTree.Element) -> str:
    lines = (element.get("message") or element.text or "").strip().splitlines()
    return EXCEPTION_PREFIX.sub("", lines[0].strip()) if lines else ""


def read_results(directory: Path) -> dict[str, Outcome]:
    """Collects the last reported outcome of each case, keyed by id."""
    outcomes: dict[str, Outcome] = {}
    for path in sorted(directory.rglob("*.xml")):
        try:
            tree = ElementTree.parse(path)
        except ElementTree.ParseError:
            continue
        for testcase in tree.iter("testcase"):
            if not testcase.get("classname", "").endswith(TEST_CLASS):
                continue
            match = re.search(r"\[([^\]]+)\]", testcase.get("name", ""))
            if match is None or testcase.find("skipped") is not None:
                continue
            seconds = float(testcase.get("time") or 0) or None
            failure = testcase.find("failure")
            if failure is None:
                failure = testcase.find("error")
            if failure is None:
                outcomes[match.group(1)] = Outcome(True, "as expected", seconds=seconds)
                continue
            line = failure_line(failure)
            parsed = MESSAGE.match(line)
            if parsed and parsed["id"] == match.group(1):
                outcomes[match.group(1)] = Outcome(False, parsed["observed"], parsed["problems"], seconds)
            else:
                outcomes[match.group(1)] = Outcome(False, line or "failed", seconds=seconds)
    # Logcat has what each case observed, including passing ones, and its extraction time.
    # Cases run one at a time, so the app's last failure log before a case's own line is
    # the reason that case failed.
    for path in sorted(directory.rglob("*.txt")):
        reason = None
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if diagnostic := DIAGNOSTIC.search(line):
                reason = diagnostic["detail"]
                continue
            logged = LOG_LINE.search(line)
            if logged and logged["id"] in outcomes:
                outcome = outcomes[logged["id"]]
                outcome.observed = logged["observed"]
                outcome.seconds = float(logged["seconds"])
                if not outcome.passed and reason:
                    # Classify the complete redacted message; truncate only for display.
                    outcome.reason = reason
            if logged:
                reason = None
    return outcomes


def expectation(case: dict) -> str:
    """Mirrors SocialLinkCase.expectation, without the leading outcome for successes."""
    if case.get("expected") != SUCCESS:
        return case.get("expected", "")
    kinds = [kind for kind in MEDIA_KINDS if kind in media_kinds(case)]
    noun = "+".join(kinds) or None
    if "count" in case:
        items = quantity(case["count"], noun or "item")
    elif "minCount" in case:
        items = "at least " + quantity(case["minCount"], noun or "item")
    else:
        items = noun
    details = [items] if items else []
    if "soundtrack" in case:
        details.append("with soundtrack" if case["soundtrack"] else "without soundtrack")
    return ", ".join(details) or "any media"


def quantity(count: int, noun: str) -> str:
    return f"{count} {noun}" if count == 1 or noun == "audio" or "+" in noun else f"{count} {noun}s"


def cell(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def render(cases: list[dict], outcomes: dict[str, Outcome], run_problems: list[str] | None = None) -> str:
    complete = complete_outcomes(cases, outcomes)
    classifications = {case["id"]: classify(case, complete[case["id"]]) for case in cases}
    counts = counts_for(cases, outcomes)
    lines = ["## Live social links", ""]
    if not outcomes:
        lines.append(f"No {TEST_CLASS} results were found; the run may have failed before testing.")
    lines.append("**" + " · ".join(f"{status} {counts[status]}" for status in STATUSES) + "**")
    lines.append(f"{len(cases)} selected cases. BLOCKED and KNOWN are not passes. Media URLs and credentials are never reported.")
    if missing := sum(case["id"] not in outcomes for case in cases):
        lines.append(f"{missing} selected cases did not complete and count as FAIL.")
    if run_problems:
        lines += ["", "### Test-run failures", "", *[f"- {cell(problem)}" for problem in run_problems]]
    lines += ["", "| Outcome | Count |", "| --- | ---: |"]
    lines += [f"| {status} | {counts[status]} |" for status in STATUSES]
    recovered = [case for case in cases if classifications[case["id"]].status == "PASS" and classifications[case["id"]].issue]
    lines += ["", f"### Known issues now passing ({len(recovered)})", ""]
    if recovered:
        for case in recovered:
            issue = classifications[case["id"]].issue
            lines.append(f"- `{case['id']}`: PASS; review [#{issue}]({ISSUES_URL}{issue}) for closure.")
    else:
        lines.append("None.")
    lines += ["", "| Media | PASS | BLOCKED | KNOWN | FAIL |", "| --- | ---: | ---: | ---: | ---: |"]
    groups = [(key, title, [case for case in cases if group_of(case) == key]) for key, title in GROUPS]
    for _, title, members in groups:
        if members:
            group_counts = counts_for(members, outcomes)
            lines.append(f"| {title} | " + " | ".join(str(group_counts[status]) for status in STATUSES) + " |")
    for _, title, members in groups:
        if not members:
            continue
        lines += ["", f"### {title}", "", "| Outcome | Case | Expected | Observed | Time |", "| --- | --- | --- | --- | --- |"]
        for case in members:
            outcome = complete[case["id"]]
            link = f"`{case['id']}`" if group_of(case) == "error" else f"[{case['id']}]({case['url']})"
            observed = outcome.observed + (f" — {outcome.problems}" if outcome.problems else "")
            seconds = f"{outcome.seconds:.1f} s" if outcome.seconds is not None else ""
            mark = classifications[case["id"]].label
            lines.append(f"| {mark} | {link} | {cell(expectation(case))} | {cell(observed)} | {seconds} |")
    reasons = [(case["id"], complete[case["id"]].reason) for case in cases if complete[case["id"]].reason]
    if reasons:
        lines += ["", "### Failure details", "", "The app's redacted extraction log for each failed case:", ""]
        # Keep SafeLog's <url> placeholders visible instead of letting Markdown drop them as tags.
        lines += [f"- `{name}` ({classifications[name].status}): {reason[:MAX_REASON].replace('<', '&lt;').replace('>', '&gt;')}"
                  for name, reason in reasons]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=FIXTURE)
    parser.add_argument("--results", type=Path, default=RESULTS, help="Directory searched for test reports")
    parser.add_argument("--check", action="store_true", help="Only validate the fixture")
    parser.add_argument("--link-ids", default="", help="Comma-separated selected case IDs; empty means all cases")
    parser.add_argument("--test-step-outcome", choices=("success", "failure", "skipped", "cancelled", ""),
                        help="GitHub outcome of the Gradle test step; detect failures outside case assertions")
    parser.add_argument("--json-output", type=Path, help="Also save classified results as JSON")
    args = parser.parse_args(argv)

    cases = load_cases(args.fixture)
    problems = validate(cases, error_names())
    if not problems:
        try:
            selected = select_cases(cases, args.link_ids)
        except ValueError as error:
            problems.append(str(error))
    if args.check or problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            return 1
        print(f"{len(cases)} social-link cases are valid; {len(selected)} selected.")
        return 0
    outcomes = read_results(args.results) if args.results.is_dir() else {}
    run_problems = []
    if unknown := set(outcomes) - {case["id"] for case in cases}:
        run_problems.append(f"Results contain unknown case IDs: {', '.join(sorted(unknown))}")
    if args.test_step_outcome is not None:
        if args.test_step_outcome not in {"success", "failure"}:
            run_problems.append(f"The test step did not complete: {args.test_step_outcome or 'not run'}")
        elif args.test_step_outcome == "failure" and not any(
                not outcomes[case["id"]].passed for case in selected if case["id"] in outcomes):
            run_problems.append("The test step failed without a recorded case failure; inspect the Gradle/emulator log.")
    counts = counts_for(selected, outcomes)
    if args.json_output:
        complete = complete_outcomes(selected, outcomes)
        payload = {"counts": counts, "run_problems": run_problems, "cases": []}
        for case in selected:
            outcome = complete[case["id"]]
            classification = classify(case, outcome)
            payload["cases"].append({
                "id": case["id"], "status": classification.status, "issue": classification.issue,
                "expected": expectation(case), "observed": outcome.observed, "problems": outcome.problems,
                "seconds": outcome.seconds, "reason": outcome.reason,
            })
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    sys.stdout.write(render(selected, outcomes, run_problems))
    return int(counts["FAIL"] > 0 or bool(run_problems))


if __name__ == "__main__":
    sys.exit(main())
