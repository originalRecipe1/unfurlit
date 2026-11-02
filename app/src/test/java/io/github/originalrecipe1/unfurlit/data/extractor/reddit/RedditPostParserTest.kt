package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.*
import org.junit.Test

class RedditPostParserTest {
    private val page = "https://www.reddit.com/r/test/comments/post123/title/"
    private fun fixture(name: String) = javaClass.getResource("/reddit/$name.html")!!.readText()
    private fun parse(html: String) = RedditPostParser.parse(page, html)

    @Test fun matchesOnlyRequestedPostAndDecodesEntities() {
        val post = parse(fixture("native"))!!
        assertEquals("A \"cold\" drink & sunshine 😀", post.title)
        assertEquals("a&b", post.author)
        assertEquals(RedditContent.Video("https://v.redd.it/video123/HLSPlaylist.m3u8?one=1&two=2"), post.content)
        assertNull(RedditPostParser.parse(page.replace("post123", "absent"), fixture("native")))
        assertNull(parse("<script>${fixture("image")}</script>"))
        assertNull(parse("<!-- ${fixture("image")} -->"))
    }

    @Test fun derivesDashOnlyFromValidatedNativeContentLink() {
        val html = fixture("native").replace(Regex("<shreddit-player.*?</shreddit-player>"), "")
        assertEquals(RedditContent.Video("https://v.redd.it/video123/DASHPlaylist.mpd"), parse(html)!!.content)
        for (url in listOf("http://v.redd.it/video123", "https://v.redd.it/video123/path", "https://v.redd.it@evil.example/video123")) {
            assertNull(parse(html.replace("https://v.redd.it/video123", url)))
        }
        assertNull(parse(fixture("native").replace("src=\"https://v.redd.it/", "src=\"https://evil.example/")))
        assertFalse(RedditPostParser.isManifestUrl("https://v.redd.it.evil.example/id/file.m3u8"))
        assertTrue(RedditPostParser.isManifestUrl("https://v.redd.it/id/DASHPlaylist.mpd"))
    }

    @Test fun galleryUsesSlotOrderDeduplicatesAndCaps() {
        assertEquals(RedditContent.Images(listOf("https://i.redd.it/first.png", "https://i.redd.it/second.jpg")), parse(fixture("gallery"))!!.content)
        val items = (1..55).joinToString("") { "<li slot='page-$it'><img src='https://i.redd.it/photo$it.jpg'></li>" }
        val html = "<shreddit-post id='t3_post123'><gallery-carousel>$items</gallery-carousel></shreddit-post>"
        val images = (parse(html)!!.content as RedditContent.Images).urls
        assertEquals(50, images.size)
        assertEquals("https://i.redd.it/photo50.jpg", images.last())
        assertNull(parse(fixture("gallery").replace("post-id='post123'", "post-id='other'")))
        assertNull(parse(fixture("gallery").replace("page-2", "page-4")))
    }

    @Test fun supportsOriginalAndMappedSingleImage() {
        assertEquals(RedditContent.Images(listOf("https://i.redd.it/photo123.jpg")), parse(fixture("image"))!!.content)
        assertEquals(RedditContent.Images(listOf("https://i.redd.it/photo123.jpg")), parse(fixture("image")
            .replace("https://i.redd.it/photo123.jpg", "https://preview.redd.it/title-v0-photo123.jpg?width=640&amp;auto=webp"))!!.content)
        assertNull(parse(fixture("image").replace("i.redd.it", "i.redd.it.evil.example")))
        assertNull(parse(fixture("image").replace("https://", "http://")))
        assertThrows(ExtractionException::class.java) { parse(fixture("image").replace("i.redd.it", "127.0.0.1")) }
    }

    @Test fun externalLinksAreDecodedAndRedditTargetsNeverReenterTheAdapter() {
        assertEquals(RedditContent.External("https://streamable.com/1aqkw?one=1&two=2"), parse(fixture("external"))!!.content)
        for (url in listOf("https://www.reddit.com/comments/parent", "https://redd.it/parent", "https://www.reddit.com/gallery/post123")) {
            assertNull(parse(fixture("external").replace("https://streamable.com/1aqkw?one=1&amp;two=2", url)))
        }
        val nested = "<shreddit-post id='t3_post123'><shreddit-post id='t3_parent'><shreddit-player src='https://v.redd.it/wrong/file.m3u8'></shreddit-player></shreddit-post></shreddit-post>"
        assertNull(parse(nested))
    }

    @Test fun externalPreviewPlayerDoesNotHideTheOriginalVideoProvider() {
        val html = fixture("external").replace("</shreddit-post>",
            "<shreddit-player src='https://external-preview.redd.it/preview.gif'></shreddit-player></shreddit-post>")
        assertEquals(RedditContent.External("https://streamable.com/1aqkw?one=1&two=2"), parse(html)!!.content)
    }

    @Test fun distinguishesChallengeFromIdBearingShellAndUnknownMarkup() {
        assertNull(parse(fixture("challenge")))
        assertTrue(RedditPostParser.isChallenge(page, fixture("challenge")))
        assertNull(parse(fixture("shell")))
        assertFalse(RedditPostParser.isChallenge(page, fixture("shell")))
        assertFalse(RedditPostParser.isChallenge(page, "<html>new layout</html>"))
        assertTrue(RedditPostParser.isChallenge(page, "<html>You've been blocked by network security.</html>"))
    }
}
