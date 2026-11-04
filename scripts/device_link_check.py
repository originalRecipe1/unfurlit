#!/usr/bin/env python3
"""Open release-check links in the installed Unfurlit app over adb and record what happens.

Each link is shared to the app with an ACTION_SEND intent, as the share sheet does. The
script then polls the UI tree (uiautomator) and the app's media session until the link
shows media or an error, and records:

- video: playback starts and advances; an AudioTrack plays for the app's uid (sound);
  with the player paused, the media session's fast-forward and rewind (15 s / 5 s) move
  the position by that much, and playback resumes from there. uiautomator cannot dump a
  paused player (the UI never goes idle), so the seek uses the session, not the buttons.
- image / gallery: the viewer shows an image, with "Item 1 of N" for galleries; each
  gallery page is screenshotted after a swipe so the order can be compared with Reddit.
- blocked: the expected error title is shown; tapping Open link leaves the app.

Screenshots and a JSON and Markdown report go to --out. Results still need a look at the
screenshots: the script cannot tell whether an image is the right one.

Usage:
  python3 -I scripts/device_link_check.py --serial SERIAL --out DIR [--only id,id]
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

PACKAGE = "io.github.originalrecipe1.unfurlit"
ACTIVITY = f"{PACKAGE}/.MainActivity"
LOADING = "Extracting stream information…"
# English error titles from res/values/strings.xml; the app has no translations.
ERROR_TITLES = (
    "No viewable media found",
    "Media unavailable",
    "Sign-in may be needed",
    "blocked this request",
    "Couldn’t reach this site",
    "This took too long",
    "Couldn’t open this media",
)
IMAGE_FAILED = "Could not load this image."
GALLERY = re.compile(r"^Item (\d+) of (\d+)$")
NODE = re.compile(r"<node [^>]*>")
ATTR = re.compile(r'([\w-]+)="([^"]*)"')
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
SESSION = re.compile(
    rf"package={re.escape(PACKAGE)}\b.*?state=PlaybackState \{{state=(\d+), position=(-?\d+)", re.S
)
STATE_PLAYING = 3
STATE_PAUSED = 2


@dataclass(frozen=True)
class Case:
    id: str
    url: str
    kind: str  # video, image, gallery, blocked, error
    count: int = 1
    sound: bool | None = None  # None: don't check
    expect: str = ""  # error title for blocked/error cases


REDDIT = [
    Case("reddit-video", "https://www.reddit.com/r/videos/comments/6rrwyj/that_small_heart_attack/", "video", sound=True),
    Case("reddit-native-video", "https://www.reddit.com/r/aww/comments/90bu6w/heat_index_was_110_degrees_so_we_offered_him_a/", "video", sound=False),
    Case("reddit-external-imgur", "https://www.reddit.com/r/MadeMeSmile/comments/6t7wi5/wait_for_it/", "video"),
    Case("reddit-external-streamable", "https://www.reddit.com/r/videos/comments/6t7sg9/comedians_hilarious_joke_about_the_guam_flag/", "video"),
    Case("reddit-gallery", "https://www.reddit.com/gallery/hrrh23", "gallery", count=3),
    Case("reddit-gallery-share", "https://www.reddit.com/r/woodworking/s/TT8dsTXe9V", "gallery", count=4),
    Case("reddit-gallery-short", "https://redd.it/hrrh23", "gallery", count=3),
    Case("reddit-image-earthporn", "https://www.reddit.com/r/EarthPorn/comments/7t0swm/so_glad_i_got_up_for_this_15f_sunrise_theres_only/", "image"),
    Case("reddit-image-pics", "https://www.reddit.com/r/pics/comments/haucpf/ive_found_a_few_funny_memories_during_lockdown/", "image"),
    Case("reddit-image-post", "https://www.reddit.com/r/RobloxArt/comments/15ko0qu/", "blocked", expect="Reddit blocked this request"),
]
SMOKE = [
    Case("youtube-video", "https://www.youtube.com/watch?v=eRsGyueVLvQ", "video", sound=True),
    Case("youtube-shorts", "https://www.youtube.com/shorts/18NGQq7p3LY", "video", sound=True),
    Case("instagram-reel", "https://www.instagram.com/reel/Chunk8-jurw/", "video", sound=False),  # video-only formats
    Case("tiktok-video", "https://www.tiktok.com/@hankgreen1/video/7047596209028074758", "video"),
    Case("x-mixed-media", "https://x.com/carrotsprout_/status/1577924293023133696", "gallery", count=2),
    Case("vimeo-video", "https://vimeo.com/108650530", "video", sound=True),
    Case("non-media-page", "https://example.com/", "error", expect="No viewable media found"),
]
CASES = REDDIT + SMOKE


@dataclass
class Node:
    text: str
    desc: str
    rid: str
    cls: str
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


@dataclass
class Result:
    id: str
    url: str
    kind: str
    status: str = "FAIL"
    seconds: float | None = None
    notes: list[str] = field(default_factory=list)
    screenshots: list[str] = field(default_factory=list)


class Device:
    def __init__(self, adb: str, serial: str):
        self.base = [adb, "-s", serial]

    def run(self, *args: str, timeout: float = 60) -> str:
        done = subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=timeout)
        return done.stdout

    def shell(self, *args: str, timeout: float = 60) -> str:
        return self.run("shell", *args, timeout=timeout)

    def share(self, url: str) -> None:
        # NEW_TASK | CLEAR_TASK: a fresh viewer each time; the process (and its Reddit session) stays.
        self.shell(
            "am", "start", "-f", "0x10008000", "-a", "android.intent.action.SEND", "-t", "text/plain",
            "--es", "android.intent.extra.TEXT", url, "-n", ACTIVITY,
        )

    def nodes(self) -> list[Node]:
        """The UI tree, or [] when uiautomator can't dump it (a UI that never goes idle)."""
        self.shell("rm", "-f", "/sdcard/unfurlit-ui.xml")
        out = self.shell("uiautomator", "dump", "/sdcard/unfurlit-ui.xml", timeout=30)
        if "dumped" not in out:
            return []
        xml = self.run("exec-out", "cat", "/sdcard/unfurlit-ui.xml")
        found = []
        for raw in NODE.findall(xml):
            a = dict(ATTR.findall(raw))
            b = BOUNDS.match(a.get("bounds", ""))
            found.append(Node(
                unescape(a.get("text", "")), unescape(a.get("content-desc", "")),
                a.get("resource-id", "").split("/")[-1], a.get("class", "").split(".")[-1],
                tuple(map(int, b.groups())) if b else (0, 0, 0, 0),
            ))
        return found

    def session(self) -> tuple[int, int] | None:
        m = SESSION.search(self.shell("dumpsys", "media_session"))
        return (int(m.group(1)), int(m.group(2))) if m else None

    def uid(self) -> str:
        m = re.search(r"userId=(\d+)", self.shell("dumpsys", "package", PACKAGE))
        return m.group(1) if m else ""

    def audio_started(self, uid: str) -> bool:
        audio = self.shell("dumpsys", "audio")
        return any(
            f"u/pid:{uid}/" in line and "state:started" in line and "AudioTrack" in line
            for line in audio.splitlines()
        )

    def focus_package(self) -> str:
        out = self.shell("dumpsys", "window")
        m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([\w.]+)/", out) or re.search(r"mFocusedApp=.*? ([\w.]+)/", out)
        return m.group(1) if m else ""

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def key(self, code: str) -> None:
        self.shell("input", "keyevent", code)

    def screenshot(self, path: Path) -> None:
        data = subprocess.run(self.base + ["exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
        path.write_bytes(data)


def unescape(value: str) -> str:
    return (value.replace("&quot;", '"').replace("&apos;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&#10;", "\n").replace("&amp;", "&"))


def error_text(nodes: list[Node]) -> str | None:
    for n in nodes:
        if any(title in n.text for title in ERROR_TITLES):
            return n.text
    return None


def gallery_position(nodes: list[Node]) -> tuple[int, int] | None:
    for n in nodes:
        m = GALLERY.match(n.desc)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def media_image(nodes: list[Node]) -> Node | None:
    images = [n for n in nodes if n.cls == "ImageView" and n.desc]
    return images[0] if images else None


def wait_ready(dev: Device, case: Case, timeout: float) -> tuple[str, list[Node], float]:
    """Poll until the viewer shows media or an error. Returns (state, nodes, seconds)."""
    start = time.monotonic()
    nodes: list[Node] = []
    while time.monotonic() - start < timeout:
        if case.kind == "video":
            s = dev.session()
            if s and s[0] == STATE_PLAYING:
                return "video", [], time.monotonic() - start
            if s:  # buffering: no error screen; skip the UI dump, which can take 12 s
                time.sleep(0.3)
                continue
        nodes = dev.nodes()
        texts = [n.text for n in nodes]
        if error_text(nodes):
            return "error", nodes, time.monotonic() - start
        if LOADING not in texts and any(n.text == "Open link" for n in nodes):
            if gallery_position(nodes) or media_image(nodes):
                return "image", nodes, time.monotonic() - start
        time.sleep(1)
    return "timeout", nodes, time.monotonic() - start


def paused_position(dev: Device) -> tuple[int, int]:
    """Pause and return (state, position): a playing session's position is a stale snapshot."""
    dev.key("KEYCODE_MEDIA_PAUSE")
    time.sleep(1)
    return dev.session() or (0, 0)


def check_video(dev: Device, case: Case, result: Result, uid: str, shots: Path) -> None:
    ok = True
    # Sample the AudioTrack while it plays: some clips (a 3 s Short) end within seconds.
    sound = False
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        sound = sound or dev.audio_started(uid)
        time.sleep(0.3)
    shot = shots / f"{case.id}.png"
    dev.screenshot(shot)
    result.screenshots.append(shot.name)
    state, p1 = paused_position(dev)
    if p1 <= 500:
        result.notes.append(f"position did not advance ({p1} ms)")
        ok = False
    else:
        result.notes.append(f"played to {p1 / 1000:.1f} s" + (" (ended)" if state == 1 else ""))
    if case.sound is not None:
        ok = ok and sound == case.sound
    result.notes.append("AudioTrack playing" if sound else "no AudioTrack")

    dev.key("KEYCODE_MEDIA_FAST_FORWARD")
    time.sleep(1.5)
    p2 = (dev.session() or (0, 0))[1]
    dev.key("KEYCODE_MEDIA_REWIND")
    time.sleep(1.5)
    p3 = (dev.session() or (0, 0))[1]
    forward, back = p2 - p1, p2 - p3
    result.notes.append(f"seek +{forward / 1000:.1f} s / -{back / 1000:.1f} s")
    # Forward clamps at the end of a short clip; back is exact once there is room for it.
    if not ((forward >= 14000 or (state == 1 or forward > 0)) and (p2 < 5000 or 4500 <= back <= 5500)):
        ok = False
    dev.key("KEYCODE_MEDIA_PLAY")
    time.sleep(3)
    state4, p4 = paused_position(dev)
    if p4 > p3 + 1000:
        result.notes.append(f"resumed to {p4 / 1000:.1f} s")
    elif state4 == 1:
        result.notes.append("ended after seek")
    else:
        result.notes.append(f"did not resume after seek ({state4}, {p4} ms)")
        ok = False
    result.status = "PASS" if ok else "FAIL"


def check_images(dev: Device, case: Case, result: Result, nodes: list[Node], shots: Path) -> None:
    if any(n.text == IMAGE_FAILED for n in nodes):
        result.notes.append("image failed to load")
        return
    position = gallery_position(nodes)
    total = position[1] if position else 1
    marker = next((n for n in nodes if GALLERY.match(n.desc)), None)
    if total != case.count:
        result.notes.append(f"{total} items, expected {case.count}")
    else:
        result.notes.append(f"{total} item{'s' if total > 1 else ''}")
    time.sleep(2)  # let the image finish decoding before the screenshot
    seen = []
    for page in range(1, total + 1):
        shot = shots / (f"{case.id}-{page}.png" if total > 1 else f"{case.id}.png")
        dev.screenshot(shot)
        result.screenshots.append(shot.name)
        current = gallery_position(dev.nodes()) if total > 1 else (1, 1)
        seen.append(current[0] if current else None)
        session = dev.session()
        if session and session[0] == STATE_PLAYING:
            result.notes.append(f"item {page}: video playing")
        if page < total and marker:
            # The position marker sits in the top corner of the media pager; swipe below it.
            width = marker.bounds[2] + 26
            y = marker.bounds[3] + 300
            dev.shell("input", "swipe", str(width * 9 // 10), str(y), str(width // 10), str(y), "300")
            time.sleep(2.5)
    if total > 1:
        result.notes.append(f"pages {seen}")
    if total == case.count and seen == list(range(1, total + 1)):
        result.status = "PASS"


def check_error(dev: Device, case: Case, result: Result, nodes: list[Node], shots: Path) -> None:
    title = error_text(nodes) or ""
    shot = shots / f"{case.id}.png"
    dev.screenshot(shot)
    result.screenshots.append(shot.name)
    result.notes.append(f"error: {title}")
    if case.expect not in title:
        return
    link = next((n for n in nodes if n.text == "Open link"), None)
    if not link:
        result.notes.append("no Open link button")
        return
    dev.tap(*link.center)
    time.sleep(3)
    focus = dev.focus_package()
    after = shots / f"{case.id}-open-link.png"
    dev.screenshot(after)
    result.screenshots.append(after.name)
    result.notes.append(f"Open link -> {focus or 'unknown'}")
    if focus and focus != PACKAGE:
        result.status = "PASS"
    dev.key("KEYCODE_BACK")
    time.sleep(1)


def run_case(dev: Device, case: Case, uid: str, shots: Path, timeout: float) -> Result:
    result = Result(case.id, case.url, case.kind)
    dev.share(case.url)
    state, nodes, seconds = wait_ready(dev, case, timeout)
    result.seconds = round(seconds, 1)
    if state == "timeout":
        result.notes.append(f"no media or error after {timeout:.0f} s")
        shot = shots / f"{case.id}-timeout.png"
        dev.screenshot(shot)
        result.screenshots.append(shot.name)
        return result
    if case.kind in ("blocked", "error"):
        if state == "error":
            check_error(dev, case, result, nodes, shots)
        else:
            result.notes.append(f"expected an error, got {state}")
        return result
    if state == "error":
        result.notes.append(f"error: {error_text(nodes)}")
        shot = shots / f"{case.id}-error.png"
        dev.screenshot(shot)
        result.screenshots.append(shot.name)
        return result
    if case.kind == "video":
        check_video(dev, case, result, uid, shots)
    else:
        check_images(dev, case, result, nodes, shots)
    return result


def markdown(results: list[Result]) -> str:
    lines = ["| Case | Result | Time | Notes |", "| --- | --- | --- | --- |"]
    for r in results:
        lines.append(f"| {r.id} | {r.status} | {r.seconds} s | {'; '.join(r.notes)} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--only", help="comma-separated case ids")
    parser.add_argument("--repeat", type=int, default=1, help="run the selection this many times")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--force-stop", action="store_true", help="kill the app before the first case")
    args = parser.parse_args(argv)

    cases = CASES
    if args.only:
        wanted = args.only.split(",")
        unknown = set(wanted) - {c.id for c in CASES}
        if unknown:
            parser.error(f"unknown case ids: {', '.join(sorted(unknown))}")
        cases = [c for c in CASES if c.id in wanted]
    args.out.mkdir(parents=True, exist_ok=True)
    dev = Device(args.adb, args.serial)
    dev.key("KEYCODE_WAKEUP")
    if args.force_stop:
        dev.shell("am", "force-stop", PACKAGE)
    uid = dev.uid()
    results: list[Result] = []
    for round_ in range(1, args.repeat + 1):
        shots = args.out / (f"round-{round_}" if args.repeat > 1 else ".")
        shots.mkdir(parents=True, exist_ok=True)
        for case in cases:
            r = run_case(dev, case, uid, shots, args.timeout)
            if args.repeat > 1:
                r.id = f"{r.id} (round {round_})"
            print(f"{r.id}: {r.status} in {r.seconds} s — {'; '.join(r.notes)}", flush=True)
            results.append(r)
    (args.out / "results.json").write_text(json.dumps([r.__dict__ for r in results], indent=2) + "\n")
    (args.out / "results.md").write_text(markdown(results))
    return 0 if all(r.status == "PASS" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
