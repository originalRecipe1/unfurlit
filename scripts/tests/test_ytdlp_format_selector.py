"""Exercise the app's selector with the pinned yt-dlp engine, without network access."""

import json
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
# Never write generated Python files into the upstream submodule.
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "third_party/yt-dlp"))
from yt_dlp import YoutubeDL

EXTRACTOR = ROOT / "app/src/main/java/io/github/originalrecipe1/unfurlit/data/extractor/ytdlp/YtDlpMediaExtractor.kt"
definition = EXTRACTOR.read_text().split("const val FORMAT_SELECTOR =", 1)[1].split("const val OUTPUT_TEMPLATE", 1)[0]
SELECTOR = "".join(json.loads(literal) for literal in re.findall(r'"(?:[^"\\]|\\.)*"', definition))
OLD_SELECTOR = "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"


def audio(ident, codec):
    ext = "m4a" if codec in ("aac", "mp4a.40.2", "alac") else "webm" if codec == "opus" else codec
    return dict(format_id=ident, acodec=codec, vcodec="none", ext=ext,
                url=f"https://example.invalid/{ident}")


def video(ident, height, acodec="none", vcodec="avc1"):
    return dict(format_id=ident, height=height, vcodec=vcodec, acodec=acodec,
                ext="webm" if vcodec == "vp9" else "mp4", url=f"https://example.invalid/{ident}")


def select(formats, selector=SELECTOR):
    # yt-dlp hands the selector formats sorted from worst to best.
    with YoutubeDL({"quiet": True, "check_formats": False}, auto_init=False) as ydl:
        return [item["format_id"] for item in ydl._select_formats(formats, ydl.build_format_selector(selector))]


class AndroidAudioFormatSelectionTest(unittest.TestCase):
    def test_bandcamp_prefers_lossy_vorbis_over_lossless(self):
        # The nine formats and ordering offered for Lanius (Battle), 2026-10-05.
        formats = [audio(ident, codec) for ident, codec in [
            ("mp3-128", "mp3"), ("mp3-v0", "mp3"), ("mp3-320", "mp3"),
            ("aac-hi", "aac"), ("vorbis", "vorbis"), ("wav", "wav"),
            ("aiff-lossless", "aiff"), ("flac", "flac"), ("falac", "alac"),
        ]]
        self.assertEqual(["falac"], select(formats, OLD_SELECTOR))
        self.assertEqual(["vorbis"], select(formats))

    def test_only_incompatible_formats_are_not_selected_by_final_fallback(self):
        self.assertEqual([], select([audio("aiff", "aiff"), audio("alac", "alac")]))

    def test_lossless_audio_is_still_selected_when_no_lossy_format_exists(self):
        for formats, expected in [
            ([audio("flac", "flac")], "flac"),
            ([audio("wav", "wav")], "wav"),
            ([audio("wav", "wav"), audio("flac", "flac")], "flac"),
        ]:
            with self.subTest(expected=expected, count=len(formats)):
                self.assertEqual([expected], select(formats))

    def test_split_video_prefers_lossy_audio_but_retains_lossless_fallback(self):
        self.assertEqual(["1080p+opus"], select([
            audio("opus", "opus"), audio("flac", "flac"), video("1080p", 1080),
        ]))
        self.assertEqual(["1080p+flac"], select([
            audio("wav", "wav"), audio("flac", "flac"), video("1080p", 1080),
        ]))

    def test_combined_fallbacks_prefer_lossy_but_accept_lossless_if_needed(self):
        for height in (720, 2160):
            with self.subTest(height=height):
                lossy = video("aac", height, "aac")
                lossless = video("flac", height, "flac")
                self.assertEqual(["aac"], select([lossy, lossless]))
                self.assertEqual(["flac"], select([lossless]))

    def test_lossy_combined_format_precedes_split_lossless_fallback(self):
        formats = [video("720p-aac", 720, "aac"), audio("flac", "flac"), video("1080p", 1080)]
        self.assertEqual(["720p-aac"], select(formats))

    def test_split_video_rejects_incompatible_audio(self):
        formats = [audio("aac", "mp4a.40.2"), audio("alac", "alac"), video("1080p", 1080)]
        self.assertEqual(["1080p+aac"], select(formats))

    def test_combined_1080p_fallback_rejects_incompatible_audio(self):
        formats = [video("720p-aac", 720, "aac"), video("1080p-alac", 1080, "alac")]
        self.assertEqual(["720p-aac"], select(formats))

    def test_uncapped_fallback_also_rejects_incompatible_audio(self):
        formats = [video("2160p-aac", 2160, "aac"), video("2160p-aiff", 2160, "aiff")]
        self.assertEqual(["2160p-aac"], select(formats))

    def test_youtube_separate_stream_selection_is_unchanged(self):
        formats = [audio("140", "mp4a.40.2"), audio("251", "opus"),
                   video("137", 1080), video("248", 1080, vcodec="vp9"),
                   video("315", 2160, vcodec="vp9")]
        self.assertEqual(["248+251"], select(formats))
        self.assertEqual(select(formats, OLD_SELECTOR), select(formats))

    def test_reddit_combined_stream_selection_is_unchanged(self):
        formats = [video("hls-480", 480, "mp4a.40.2"), video("hls-720", 720, "mp4a.40.2")]
        self.assertEqual(["hls-720"], select(formats))
        self.assertEqual(select(formats, OLD_SELECTOR), select(formats))

    def test_unknown_codec_metadata_retains_existing_selection(self):
        formats = [video("480", 480, "aac"), video("720", 720, None)]
        self.assertEqual(["720"], select(formats))
        self.assertEqual(select(formats, OLD_SELECTOR), select(formats))


if __name__ == "__main__":
    unittest.main()
