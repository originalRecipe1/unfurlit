#!/usr/bin/env python3
"""Summarize a live social-link run, or check its fixture.

Reads app/src/androidTest/assets/social-links.json and the JUnit XML and logcat files of
an instrumented SocialLinksTest run, and prints a Markdown report grouped by media kind
(for example into $GITHUB_STEP_SUMMARY). With --check it only validates the fixture.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "app" / "src" / "androidTest" / "assets" / "social-links.json"
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
GROUPS = (
    ("video", "Video"),
    ("image", "Photos and galleries"),
    ("audio", "Audio"),
    ("mixed", "Mixed media"),
    ("other", "Any media"),
    ("error", "Error handling"),
)


@dataclass
class Outcome:
    passed: bool
    observed: str
    problems: str = ""
    seconds: float | None = None


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
            if parsed:
                outcomes[match.group(1)] = Outcome(False, parsed["observed"], parsed["problems"], seconds)
            else:
                outcomes[match.group(1)] = Outcome(False, line or "failed", seconds=seconds)
    # Logcat has what each case observed, including passing ones, and its extraction time.
    for path in sorted(directory.rglob("*.txt")):
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            logged = LOG_LINE.search(line)
            if logged and logged["id"] in outcomes:
                outcome = outcomes[logged["id"]]
                outcome.observed = logged["observed"]
                outcome.seconds = float(logged["seconds"])
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


def render(cases: list[dict], outcomes: dict[str, Outcome]) -> str:
    ran = [case for case in cases if case["id"] in outcomes]
    lines = ["## Live social links", ""]
    if not ran:
        lines.append(f"No {TEST_CLASS} results were found; the run may have failed before testing.")
        return "\n".join(lines) + "\n"
    passed = sum(outcomes[case["id"]].passed for case in ran)
    lines.append(f"**{passed} of {len(ran)} cases passed.** Media URLs and credentials are never reported.")
    if skipped := len(cases) - len(ran):
        lines.append(f"{skipped} fixture cases did not run.")
    lines += ["", "| Media | Passed |", "| --- | --- |"]
    groups = [(key, title, [case for case in ran if group_of(case) == key]) for key, title in GROUPS]
    for _, title, members in groups:
        if members:
            count = sum(outcomes[case["id"]].passed for case in members)
            lines.append(f"| {title} | {count} of {len(members)} |")
    for _, title, members in groups:
        if not members:
            continue
        lines += ["", f"### {title}", "", "| | Case | Expected | Observed | Time |", "| --- | --- | --- | --- | --- |"]
        for case in members:
            outcome = outcomes[case["id"]]
            link = f"`{case['id']}`" if group_of(case) == "error" else f"[{case['id']}]({case['url']})"
            observed = outcome.observed + (f" — {outcome.problems}" if outcome.problems else "")
            seconds = f"{outcome.seconds:.1f} s" if outcome.seconds is not None else ""
            mark = "✅" if outcome.passed else "❌"
            lines.append(f"| {mark} | {link} | {cell(expectation(case))} | {cell(observed)} | {seconds} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=FIXTURE)
    parser.add_argument("--results", type=Path, default=RESULTS, help="Directory searched for test reports")
    parser.add_argument("--check", action="store_true", help="Only validate the fixture")
    args = parser.parse_args(argv)

    cases = load_cases(args.fixture)
    problems = validate(cases, error_names())
    if args.check or problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            return 1
        print(f"{len(cases)} social-link cases are valid.")
        return 0
    outcomes = read_results(args.results) if args.results.is_dir() else {}
    sys.stdout.write(render(cases, outcomes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
