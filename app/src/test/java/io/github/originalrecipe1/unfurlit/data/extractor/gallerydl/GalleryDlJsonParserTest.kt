package io.github.originalrecipe1.unfurlit.data.extractor.gallerydl

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test

class GalleryDlJsonParserTest {
    @Test
    fun parsesGalleryWithMetadataAndRequestHeaders() {
        val result = GalleryDlJsonParser.parse(
            SOURCE,
            "[reddit][warning] a log line before the result\n" + singleLine(
                """
            {"category": "reddit", "title": "Sunset set", "author": "someone",
             "description": "Two photos", "items": [
              {"url": "https://i.redd.it/a.jpg", "kind": "image", "extension": "jpg",
               "headers": {"Referer": "https://www.reddit.com/", "User-Agent": "UA",
                           "Host": "evil.example", "X-Bad": "a\r\nInjected: 1"}},
              {"url": "https://v.redd.it/b.mp4", "kind": "video", "extension": "mp4", "headers": {}},
              {"url": "https://example.com/c.mp3", "kind": "audio", "extension": "mp3", "headers": {}}
            ]}
                """,
            ),
        )

        assertEquals(SOURCE, result.sourceUrl)
        assertEquals("Reddit", result.platform)
        assertEquals("Sunset set", result.title)
        assertEquals("someone", result.author)
        assertEquals("Two photos", result.description)
        assertEquals("https://i.redd.it/a.jpg", result.thumbnailUrl)
        assertEquals(3, result.media.size)
        val image = result.media[0] as ExtractedMedia.Image
        assertEquals(mapOf("Referer" to "https://www.reddit.com/", "User-Agent" to "UA"), image.source.headers)
        assertEquals("video/mp4", (result.media[1] as ExtractedMedia.Video).videoSource.mediaMimeType)
        assertEquals("audio/mpeg", (result.media[2] as ExtractedMedia.Audio).source.mediaMimeType)
    }

    @Test
    fun skipsUnsafeAndUnknownItems() {
        val result = GalleryDlJsonParser.parse(
            SOURCE,
            singleLine(
                """{"category": "someblog", "title": null, "items": [
              {"url": "http://cdn.example/plain.jpg", "kind": "image"},
              {"url": "https://127.0.0.1/local.jpg", "kind": "image"},
              {"url": "https://cdn.example/file.zip", "kind": "archive"},
              {"url": "https://cdn.example/ok.png", "kind": "image", "extension": "png"}
            ]}""",
            ),
        )
        assertEquals(listOf("https://cdn.example/ok.png"), result.media.map { (it as ExtractedMedia.Image).source.url })
        assertEquals("Someblog", result.platform)
        assertNull(result.title)
    }

    @Test
    fun postWithoutViewableMediaIsUnsupported() {
        assertError(ExtractionError.UnsupportedUrl, """{"category": "x", "items": []}""")
        assertError(ExtractionError.UnsupportedUrl, """{"category": "x", "items": [{"url": "https://a.example/x.pdf", "kind": null}]}""")
    }

    @Test
    fun mapsEngineErrors() {
        assertError(ExtractionError.UnsupportedUrl, error("NoExtractorError"))
        assertError(ExtractionError.MediaUnavailable, error("NotFoundError"))
        assertError(ExtractionError.AuthenticationRequired, error("AuthRequired"))
        assertError(ExtractionError.AuthenticationRequired, error("AuthorizationError"))
        assertError(ExtractionError.MediaUnavailable, error("HttpError", 404))
        assertError(ExtractionError.MediaUnavailable, error("HttpError", 410))
        assertError(ExtractionError.AuthenticationRequired, error("HttpError", 403))
        assertError(ExtractionError.NetworkFailure, error("HttpError", 0))
        assertError(ExtractionError.NetworkFailure, error("HttpError", 503))
        assertError(ExtractionError.ExtractionFailed, error("HttpError", 400))
        assertError(ExtractionError.ExtractionFailed, error("ChallengeError", 403))
        assertError(ExtractionError.ExtractionFailed, error("KeyError"))
        assertError(ExtractionError.ExtractionFailed, error("AbortExtraction"))
    }

    @Test
    fun redditNetworkBlocksAreNotSignInFailures() {
        val reddit = ExtractionError.NetworkBlocked("Reddit")
        // Messages from live run 38414484055; its log redacts URLs, so these URLs are examples.
        assertError(reddit, message("AbortExtraction", 0, "\"You've been blocked by network security.\""))
        assertError(reddit, message("HttpError", 403, "'403 Blocked' for 'https://www.reddit.com/r/pics/comments/abc/'"))
        assertError(reddit, message("HttpError", 403, "'403 Blocked' for 'https://oauth.reddit.com/api/info'"))
        // The same reason phrase from an unverified site, and Reddit's other refusals, are unchanged.
        assertError(ExtractionError.AuthenticationRequired,
            message("HttpError", 403, "'403 Blocked' for 'https://reddit.com.example/x'"))
        assertError(ExtractionError.AuthenticationRequired,
            message("HttpError", 403, "'403 Forbidden' for 'https://www.reddit.com/r/pics/'"))
        assertError(ExtractionError.AuthenticationRequired, message("AbortExtraction", 0, "Blocked by the firewall"))
        assertError(ExtractionError.AuthenticationRequired,
            message("AbortExtraction", 0, "blocked by network securityOther"))
        assertError(ExtractionError.AuthenticationRequired,
            message("AuthorizationError", 0, "blocked by network security"))
    }

    @Test
    fun rejectsMissingOrMalformedOutput() {
        assertError(ExtractionError.ExtractionFailed, "")
        assertError(ExtractionError.ExtractionFailed, "Traceback (most recent call last):\n  boom")
        assertError(ExtractionError.ExtractionFailed, "{not json")
        assertError(ExtractionError.ExtractionFailed, "{}")
        assertError(ExtractionError.ExtractionFailed, " ".repeat(2 * 1024 * 1024 + 1))
    }

    @Test
    fun limitsGalleriesToFiftyItems() {
        val items = (1..60).joinToString(",") { """{"url": "https://cdn.example/$it.jpg", "kind": "image"}""" }
        val result = GalleryDlJsonParser.parse(SOURCE, """{"category": "imgur", "items": [$items]}""")
        assertEquals(50, result.media.size)
        assertEquals("Imgur", result.platform)
    }

    /** The engine prints its result as one JSON line. */
    private fun singleLine(json: String) = json.lines().joinToString(" ") { it.trim() }.trim()

    private fun error(type: String, status: Int = 0) =
        """{"error": {"type": "$type", "status": $status, "message": "details"}}"""

    private fun message(type: String, status: Int, message: String) =
        JSONObject().put("error", JSONObject().put("type", type).put("status", status).put("message", message)).toString()

    private fun assertError(expected: ExtractionError, output: String) {
        try {
            GalleryDlJsonParser.parse(SOURCE, output)
            fail("Expected $expected")
        } catch (error: ExtractionException) {
            assertEquals(expected, error.error)
        }
    }

    private companion object {
        const val SOURCE = "https://www.reddit.com/r/pics/comments/abc/sunset/"
    }
}
