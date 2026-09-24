"""Tests for the bundled gallery-dl entry point's own logic (no network, no gallery-dl)."""

import importlib.util
import pathlib
import unittest

ENTRY = pathlib.Path(__file__).resolve().parents[2] / "app" / "gallery-dl" / "__main__.py"
spec = importlib.util.spec_from_file_location("unfurlit_gallery_dl_entry", ENTRY)
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)


class MediaKindTest(unittest.TestCase):
    def test_classifies_by_extension(self):
        self.assertEqual("image", entry.media_kind("JPG"))
        self.assertEqual("video", entry.media_kind("mp4"))
        self.assertEqual("audio", entry.media_kind("mp3"))
        self.assertIsNone(entry.media_kind("zip"))
        self.assertIsNone(entry.media_kind(None))


class MetadataTest(unittest.TestCase):
    def test_author_from_string_or_nested_user(self):
        self.assertEqual("ann", entry.author_name({"author": " ann "}))
        self.assertEqual("Bob", entry.author_name({"user": {"name": "Bob", "username": "bob"}}))
        self.assertEqual("carol", entry.author_name({"author": {}, "username": "carol"}))
        self.assertIsNone(entry.author_name({"author": 42}))

    def test_text_is_trimmed_and_limited(self):
        self.assertEqual("x" * entry.MAX_SHORT_TEXT,
                         entry.first_text({"title": "x" * 1000}, entry.TITLE_KEYS, entry.MAX_SHORT_TEXT))
        self.assertEqual("caption", entry.first_text({"title": " ", "caption": "caption"},
                                                     entry.TITLE_KEYS, entry.MAX_SHORT_TEXT))

    def test_forwards_only_selected_session_headers_plus_file_headers(self):
        headers = entry.forwarded_headers(
            {"User-Agent": "UA", "Referer": "https://site/", "Cookie": "secret", "Accept": "*/*"},
            {"X-File": "1", "Bad": 3},
        )
        self.assertEqual({"User-Agent": "UA", "Referer": "https://site/", "X-File": "1"}, headers)
        self.assertEqual({}, entry.forwarded_headers(None, None))


class CollectorTest(unittest.TestCase):
    def test_keeps_https_media_and_first_metadata(self):
        collector = entry.Collector()
        collector.category = "reddit"
        collector.add_metadata({"title": "Post", "author": "ann"})
        collector.add_item("https://i.example/a.jpg", {"extension": "jpg", "width": 10, "height": True}, {})
        collector.add_item("http://i.example/b.jpg", {"extension": "jpg"}, {})
        collector.add_item("https://i.example/c.zip", {"extension": "zip"}, {})
        collector.add_metadata({"title": "Later"})
        result = collector.result()
        self.assertEqual("reddit", result["category"])
        self.assertEqual("Post", result["title"])
        self.assertEqual("ann", result["author"])
        self.assertEqual(1, len(result["items"]))
        item = result["items"][0]
        self.assertEqual(("image", "jpg", 10, None), (item["kind"], item["extension"], item["width"], item["height"]))

    def test_is_full_at_the_item_limit(self):
        collector = entry.Collector()
        for index in range(entry.MAX_ITEMS):
            self.assertFalse(collector.full)
            collector.add_item(f"https://i.example/{index}.png", {"extension": "png"}, {})
        self.assertTrue(collector.full)


class ErrorResultTest(unittest.TestCase):
    def test_reports_type_status_and_short_message(self):
        class HttpError(Exception):
            status = 404
        result = entry.error_result(HttpError("x" * 2000))
        self.assertEqual("HttpError", result["error"]["type"])
        self.assertEqual(404, result["error"]["status"])
        self.assertEqual(entry.MAX_SHORT_TEXT, len(result["error"]["message"]))
        self.assertEqual(0, entry.error_result(ValueError("v"))["error"]["status"])


if __name__ == "__main__":
    unittest.main()
