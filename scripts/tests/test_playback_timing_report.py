"""Tests for the LivePlaybackTest timing summary (no device or network)."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "playback_timing_report", Path(__file__).parents[1] / "playback_timing_report.py"
)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


def attempt(first_frame, dropped, outcome="PASS", window=30_000, codec="av1"):
    return {
        "id": "youtube-video", "codec": codec, "maxHeight": 1080, "outcome": outcome,
        "device": "Tablet API 31",
        "timing": {
            "codecs": "av01.0.08M.08", "width": 2048, "height": 872,
            "videoDecoder": "c2.android.av1.decoder", "videoDecoderHardware": False,
            "readyMs": first_frame + 100, "firstFrameMs": first_frame, "windowMs": window,
            "droppedFrames": dropped, "renderedFrames": 700, "rebuffers": 0, "loadErrors": [],
            "streams": [
                {"label": "audio/webm itag 251", "firstByteMs": 900, "kibPerSecond": 30.5},
                {"label": "video/mp4 itag 400", "firstByteMs": 1_000, "kibPerSecond": 150.0},
            ],
        },
    }


class PlaybackTimingReportTest(unittest.TestCase):
    def run_report(self, records):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "attempts.jsonl"
            path.write_text("".join(json.dumps(record) + "\n" for record in records))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, report.main([str(path)]))
            return output.getvalue(), report.load([path], None)

    def test_medians_use_only_complete_passes(self):
        output, rows = self.run_report([
            attempt(9_000, 300), attempt(10_000, 400), attempt(11_000, 500),
            attempt(1_000, 0, outcome="FAIL_403"), attempt(2_000, 0, window=5_000),
        ])
        [median] = report.medians(rows)
        self.assertEqual(3, median["runs"])
        self.assertEqual(10_000, median["firstFrameMs"])
        self.assertEqual(400, median["dropped"])
        self.assertEqual(1_000, median["videoFirstByteMs"])
        self.assertEqual(150.0, median["videoKiBps"])
        self.assertIn("| Tablet API 31 | youtube-video | av1 ≤1080 | av01.0.08M.08 | 2048x872 |", output)

    def test_playback_that_never_started_counts_as_slowest(self):
        stalled = attempt(1_000, 0)
        del stalled["timing"]["readyMs"]
        output, rows = self.run_report([attempt(1_000, 0), attempt(2_000, 0), stalled])
        [median] = report.medians(rows)
        self.assertEqual(2_100, median["readyMs"])
        self.assertIn("> 30 s", output)

    def test_unpaced_attempts_are_summarized_apart(self):
        unpaced = [dict(attempt(1_000, 0), unpaced=True) for _ in range(2)]
        output, rows = self.run_report([attempt(9_000, 0), *unpaced])
        self.assertEqual(
            {("av1 ≤1080", 1), ("av1 ≤1080 unpaced", 2)},
            {(median["setting"], median["runs"]) for median in report.medians(rows)},
        )

    def test_attempt_without_timing_is_listed_not_summarized(self):
        record = attempt(0, 0, outcome="FAIL_EXTRACTION")
        del record["timing"]
        output, rows = self.run_report([record])
        self.assertEqual([], report.medians(rows))
        self.assertIn("FAIL_EXTRACTION", output)


if __name__ == "__main__":
    unittest.main()
