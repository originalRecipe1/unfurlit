#!/usr/bin/env python3
"""Summarize LivePlaybackTest timing runs as Markdown tables.

Reads one or more attempts.jsonl files written by LivePlaybackTest with playSeconds of 30
or more on a debug build, whose records carry the PlaybackTiming report under "timing".
Prints every attempt, then the median per device, video, codec setting and height cap.
Times are milliseconds since the extraction finished.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from pathlib import Path


def load(paths: list[Path], device: str | None) -> list[dict]:
    rows = []
    for path in paths:
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            timing = record.get("timing") or {}
            # YouTube's stream labels start with the MIME type.
            streams = timing.get("streams", [])
            video = next((s for s in streams if s.get("label", "").startswith("video/")), {})
            audio = next((s for s in streams if s.get("label", "").startswith("audio/")), {})
            rows.append({
                "device": record.get("device") or device or "unknown",
                "id": record["id"],
                "setting": record.get("codec", "default") + (
                    f" ≤{record['maxHeight']}" if record.get("maxHeight") else ""
                ),
                "outcome": record["outcome"],
                "codec": timing.get("codecs"),
                "resolution": (
                    f"{timing['width']}x{timing['height']}" if timing.get("width") else None
                ),
                "decoder": timing.get("videoDecoder"),
                "hardware": timing.get("videoDecoderHardware"),
                "videoFirstByteMs": video.get("firstByteMs"),
                # A report without readyMs was written before playback could start.
                "readyMs": timing.get("readyMs", math.inf) if timing else None,
                "firstFrameMs": timing.get("firstFrameMs"),
                "dropped": timing.get("droppedFrames"),
                "rendered": timing.get("renderedFrames"),
                "windowMs": timing.get("windowMs"),
                "rebuffers": timing.get("rebuffers"),
                "rebufferMs": timing.get("rebufferMs"),
                "videoKiBps": video.get("kibPerSecond"),
                "audioKiBps": audio.get("kibPerSecond"),
                "loadErrors": len(timing.get("loadErrors", [])),
            })
    return rows


def cell(value) -> str:
    if value is None:
        return "–"
    if value == math.inf:
        return "> 30 s"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, float):
        return f"{value:.0f}"
    return str(value)


COLUMNS = [
    ("device", "Device"), ("id", "Video"), ("setting", "Setting"), ("codec", "Codec"),
    ("resolution", "Resolution"), ("decoder", "Decoder"), ("hardware", "HW"),
    ("videoFirstByteMs", "Video first byte"), ("readyMs", "Ready"),
    ("firstFrameMs", "First frame"), ("dropped", "Dropped (30 s)"), ("rendered", "Rendered (30 s)"),
    ("rebuffers", "Rebuffers"), ("rebufferMs", "Stalled ms (30 s)"), ("videoKiBps", "Video KiB/s"),
    ("audioKiBps", "Audio KiB/s"), ("loadErrors", "Load errors"), ("outcome", "Outcome"),
]
MEDIAN_KEYS = (
    "videoFirstByteMs", "readyMs", "firstFrameMs", "dropped", "rendered", "rebufferMs", "videoKiBps",
)


def table(rows: list[dict], columns: list[tuple[str, str]]) -> str:
    lines = ["| " + " | ".join(title for _, title in columns) + " |",
             "|" + "|".join("---" for _ in columns) + "|"]
    lines += ["| " + " | ".join(cell(row.get(key)) for key, _ in columns) + " |" for row in rows]
    return "\n".join(lines)


def medians(rows: list[dict]) -> list[dict]:
    groups: dict[tuple, list[dict]] = {}
    for row in rows:
        # Only complete 30-second windows are comparable.
        if row["outcome"] == "PASS" and row["firstFrameMs"] is not None and (row["windowMs"] or 0) >= 30_000:
            groups.setdefault((row["device"], row["id"], row["setting"]), []).append(row)
    result = []
    for (device, video, setting), group in groups.items():
        summary = {"device": device, "id": video, "setting": setting, "runs": len(group)}
        for key in ("codec", "resolution", "decoder", "hardware"):
            values = {row[key] for row in group}
            summary[key] = values.pop() if len(values) == 1 else "mixed"
        for key in MEDIAN_KEYS:
            values = [row[key] for row in group if row[key] is not None]
            summary[key] = statistics.median(values) if values else None
        result.append(summary)
    return result


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("attempts", nargs="+", type=Path, help="attempts.jsonl files")
    parser.add_argument("--device", help="label for records without a device field")
    args = parser.parse_args(argv)
    rows = load(args.attempts, args.device)
    print("### Attempts\n")
    print(table(rows, COLUMNS))
    print("\n### Medians of complete passes\n")
    median_columns = COLUMNS[:7] + [("runs", "Runs")] + [c for c in COLUMNS if c[0] in MEDIAN_KEYS]
    print(table(medians(rows), median_columns))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
