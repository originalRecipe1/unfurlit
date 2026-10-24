package io.github.originalrecipe1.unfurlit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTextParserTest {
    @Test
    fun `accepts a standalone public URL`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlTextParser.firstSupportedUrl("  https://www.youtube.com/watch?v=abc  "),
        )
    }

    @Test
    fun `extracts the first URL from shared prose`() {
        assertEquals(
            "https://www.reddit.com/r/videos/comments/abc/a_post",
            UrlTextParser.firstSupportedUrl(
                "Take a look: https://www.reddit.com/r/videos/comments/abc/a_post. Great clip!",
            ),
        )
    }

    @Test
    fun `removes punctuation wrapped around a shared URL`() {
        assertEquals(
            "https://x.com/example/status/123",
            UrlTextParser.firstSupportedUrl("(https://x.com/example/status/123)"),
        )
    }

    @Test
    fun `keeps directly opened post links unchanged`() {
        // The shapes MainActivity's VIEW filters claim; LinkFiltersTest checks the filters.
        listOf(
            "https://www.instagram.com/reels/Chunk8-jurw/",
            "https://www.instagram.com/instagram/reel/Chunk8-jurw/",
            "https://www.instagram.com/share/p/BACiUUUYQV",
            "https://www.instagram.com/share/reel/BARSSL4rTu",
            "https://vimeo.com/392479337/a52724358e",
            "https://player.vimeo.com/video/108650530",
            "https://www.tumblr.com/mikf123/172687798174",
            "https://mikf123.tumblr.com/post/172687798174/photo-post",
            "https://www.pixiv.net/en/artworks/966412",
            "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g",
            "https://benprunty.bandcamp.com/track/lanius-battle",
        ).forEach { url -> assertEquals(url, UrlTextParser.firstSupportedUrl(url)) }
    }

    @Test
    fun `rejects a non-web link handed to the app`() {
        assertNull(UrlTextParser.firstSupportedUrl("intent://www.instagram.com/reels/Chunk8-jurw/"))
        assertNull(UrlTextParser.firstSupportedUrl("https://localhost/share/p/BACiUUUYQV"))
    }

    @Test
    fun `rejects unsupported and private URLs`() {
        assertNull(UrlTextParser.firstSupportedUrl("Open file:///sdcard/video.mp4"))
        assertNull(UrlTextParser.firstSupportedUrl("See http://127.0.0.1/private"))
        assertNull(UrlTextParser.firstSupportedUrl("There is no link here"))
    }
}
