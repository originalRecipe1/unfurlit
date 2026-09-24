# SPDX-License-Identifier: GPL-2.0-or-later
"""Unfurlit's gallery-dl entry point.

Runs as ``python gallerydl.zip URL`` with gallery-dl and its dependencies inside
the same zip. It never downloads files: it resolves a post's media URLs and
prints exactly one JSON object to stdout:

    {"category": "reddit", "title": ..., "author": ..., "description": ...,
     "items": [{"url": ..., "kind": "image", "extension": "jpg",
                "width": ..., "height": ..., "headers": {...}}]}

or, on failure, ``{"error": {"type": ..., "status": ..., "message": ...}}``.

Unlike gallery-dl's own ``--dump-json``, the output includes the request
headers (such as a Referer) that some image hosts require.

This file imports gallery-dl (GPL-2.0-only), so it is licensed GPL-2.0-or-later
to stay compatible with it. Unfurlit talks to it only through a process.
"""

import json
import os
import sys

MAX_ITEMS = 50
RESOLVE_DEPTH = 2
MAX_TEXT = 16384
MAX_SHORT_TEXT = 512

IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif", "avif", "heic", "heif"}
VIDEO_EXTENSIONS = {"mp4", "webm", "mov", "m4v"}
AUDIO_EXTENSIONS = {"m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "aac"}

# Session headers worth replaying when the app loads the media itself.
FORWARDED_HEADERS = ("User-Agent", "Referer", "Origin")

TITLE_KEYS = ("title", "content", "caption", "description", "text")
AUTHOR_KEYS = ("author", "user", "uploader", "artist", "owner", "username", "blog")
AUTHOR_NAME_KEYS = ("name", "nick", "display_name", "username", "screen_name", "handle")
DESCRIPTION_KEYS = ("description", "selftext", "content", "caption", "text")

# Reddit answers its JSON pages with a "blocked by network security" page to
# clients it does not trust. gallery-dl's own registered client for Reddit's OAuth
# API is the second route when that happens.
REDDIT_BLOCKED = "blocked by network security"
REDDIT_OAUTH_CLIENT_ID = "6N9uN0krSDE-ig"


def media_kind(extension):
    extension = (extension or "").lower()
    if extension in IMAGE_EXTENSIONS:
        return "image"
    if extension in VIDEO_EXTENSIONS:
        return "video"
    if extension in AUDIO_EXTENSIONS:
        return "audio"
    return None


def text_value(value, limit):
    if isinstance(value, str):
        value = value.strip()
        return value[:limit] if value else None
    return None


def first_text(kwdict, keys, limit):
    for key in keys:
        value = text_value(kwdict.get(key), limit)
        if value:
            return value
    return None


def author_name(kwdict):
    for key in AUTHOR_KEYS:
        value = kwdict.get(key)
        if isinstance(value, dict):
            name = first_text(value, AUTHOR_NAME_KEYS, MAX_SHORT_TEXT)
        else:
            name = text_value(value, MAX_SHORT_TEXT)
        if name:
            return name
    return None


def number(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else None


def forwarded_headers(session_headers, file_headers):
    headers = {}
    for name in FORWARDED_HEADERS:
        value = session_headers.get(name) if session_headers is not None else None
        if isinstance(value, str) and value:
            headers[name] = value
    if isinstance(file_headers, dict):
        for name, value in file_headers.items():
            if isinstance(name, str) and isinstance(value, str):
                headers[name] = value
    return headers


class Collector:
    def __init__(self):
        self.category = None
        self.metadata = None
        self.items = []

    @property
    def full(self):
        return len(self.items) >= MAX_ITEMS

    def add_metadata(self, kwdict):
        if self.metadata is None:
            self.metadata = kwdict

    def add_item(self, url, kwdict, session_headers):
        extension = str(kwdict.get("extension") or "").lower()
        kind = media_kind(extension)
        if kind is None or not isinstance(url, str) or not url.startswith("https://"):
            return
        self.add_metadata(kwdict)
        self.items.append({
            "url": url,
            "kind": kind,
            "extension": extension,
            "width": number(kwdict.get("width")),
            "height": number(kwdict.get("height")),
            "headers": forwarded_headers(session_headers, kwdict.get("_http_headers")),
        })

    def result(self):
        metadata = self.metadata or {}
        title = first_text(metadata, TITLE_KEYS, MAX_SHORT_TEXT)
        description = first_text(metadata, DESCRIPTION_KEYS, MAX_TEXT)
        if title and description and description.startswith(title):
            description = None  # e.g. a post's text already shown as its title
        return {
            "category": self.category,
            "title": title,
            "author": author_name(metadata),
            "description": description,
            "items": self.items,
        }


def error_result(exc):
    return {"error": {
        "type": exc.__class__.__name__,
        "status": getattr(exc, "status", 0) or 0,
        "message": str(exc)[:MAX_SHORT_TEXT],
    }}


def configure(config):
    config.clear()  # never read user configuration files
    extractor_options = {
        # The runtime's CA bundle; the one inside the zip would need extracting.
        "verify": os.environ.get("SSL_CERT_FILE") or True,
        "timeout": 20.0,
        "retries": 1,
    }
    for key, value in extractor_options.items():
        config.set(("extractor",), key, value)
    config.set(("cache",), "file", ":memory:")
    config.set(("output",), "mode", "null")


def prepare_reddit(extr):
    """Sets up a session the way yt-dlp does, which Reddit lets read its JSON pages.

    Reddit hands an anonymous session cookie ("loid") to visitors of old.reddit.com;
    without it the JSON pages are often blocked. The over18 cookie opts in to
    age-restricted posts, as yt-dlp does.
    """
    extr.initialize()
    extr.cookies.set("over18", "1", domain=".reddit.com")
    try:
        extr.request("https://old.reddit.com/", fatal=False)
    except Exception:
        pass  # the post request reports any real network problem


def is_reddit_block(exc):
    return exc is not None and REDDIT_BLOCKED in str(exc).lower()


def collect(url):
    from gallery_dl import config, exception, extractor, job

    configure(config)

    class CollectJob(job.DataJob):
        def __init__(self, url, parent=None, file=None, ensure_ascii=True,
                     resolve=RESOLVE_DEPTH):
            job.DataJob.__init__(self, url, parent, None, ensure_ascii, resolve)
            self.collector = parent.collector if parent else Collector()
            if self.collector.category is None:
                self.collector.category = self.extractor.category

        def handle_url(self, url, kwdict):
            session = getattr(self.extractor, "session", None)
            headers = getattr(session, "headers", None)
            self.collector.add_item(url, kwdict, headers)
            if self.collector.full:
                raise exception.StopExtraction()

        def handle_directory(self, kwdict):
            self.collector.add_metadata(kwdict)

    def run():
        extr = extractor.find(url)
        if extr is None:
            raise exception.NoExtractorError()
        if extr.category == "reddit" and extr.subcategory != "image":
            prepare_reddit(extr)
        collector_job = CollectJob(extr)
        collector_job.run()
        return collector_job

    collector_job = run()
    if not collector_job.collector.items and is_reddit_block(collector_job.exception):
        config.set(("extractor", "reddit"), "client-id", REDDIT_OAUTH_CLIENT_ID)
        retry = run()
        if retry.collector.items:
            collector_job = retry  # otherwise Reddit's block stays the reported reason
    # DataJob records the first failure instead of raising it.
    if collector_job.exception is not None and not collector_job.collector.items:
        raise collector_job.exception
    return collector_job.collector.result()


def main(argv):
    if len(argv) != 2:
        print(json.dumps({"error": {"type": "UsageError", "status": 0,
                                    "message": "expected one URL"}}))
        return 2
    try:
        result = collect(argv[1])
    except Exception as exc:  # reported to the app as structured JSON
        result = error_result(exc)
    sys.stdout.write(json.dumps(result, ensure_ascii=True))
    sys.stdout.write("\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
