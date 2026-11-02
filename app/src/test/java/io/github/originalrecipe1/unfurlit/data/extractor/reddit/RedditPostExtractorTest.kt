package io.github.originalrecipe1.unfurlit.data.extractor.reddit

import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RedditPostExtractorTest {
    private val page = "https://www.reddit.com/r/test/comments/post123/title/"
    private var bootstraps = 0
    private val session = RedditSession({ bootstraps++; listOf(Cookie.Builder().name("loid").value("anonymous")
        .domain("reddit.com").path("/").secure().build()) }, elapsedMillis = { System.nanoTime() / 1_000_000 })
    private fun fixture(name: String) = javaClass.getResource("/reddit/$name.html")!!.readText()
    private fun video() = ExtractionResult("https://streamable.com/id", "Streamable", "Engine title", "engine author", null, null,
        listOf(ExtractedMedia.Video(PlaybackSource("https://video.example/clip.mp4", emptyMap(), StreamFormat.Progressive, "video/mp4", null), null, 10)))

    @Test fun imagesUseOnePageAndKeepSharedUrlMetadataAndNoPlaybackCookies() = runBlocking {
        var pages = 0
        val extractor = RedditPostExtractor(session, { error("No video") }, { error("No external") }) {
            pages++
            assertEquals("loid=anonymous", it.header("Cookie"))
            assertEquals(RedditSession.USER_AGENT, it.header("User-Agent"))
            fixture("image")
        }
        val original = "https://eddrit.com/r/test/comments/post123/title/?shared=1"
        val result = extractor.extract(original, page)!!
        assertEquals(1, pages)
        assertEquals(original, result.sourceUrl)
        assertEquals("One photo", result.title)
        assertEquals("photographer", result.author)
        assertEquals("Reddit", result.platform)
        val source = (result.media.single() as ExtractedMedia.Image).source
        assertTrue(source.headers.isEmpty())
        assertTrue(source.cookies.isEmpty())
        extractor.extract(original, page)
        assertEquals(1, bootstraps)
    }

    @Test fun loginWallIsReportedImmediatelyWithoutRebuildOrEngineFallback() {
        val login = ExtractionException(ExtractionError.AuthenticationRequired)
        var pages = 0
        val extractor = RedditPostExtractor(session, { error("No video") }, { error("No external") }) {
            pages++
            throw login
        }
        assertSame(login, assertThrows(ExtractionException::class.java) { runBlocking { extractor.extract(page, page) } })
        assertEquals(1, pages)
        assertEquals(1, bootstraps)
    }

    @Test fun challengeRebuildsExactlyOnceAndNeverCallsEngines() {
        var pages = 0
        val extractor = RedditPostExtractor(session, { error("No engine fallback") }, { error("No external") }) { pages++; fixture("challenge") }
        val failure = assertThrows(ExtractionException::class.java) { runBlocking { extractor.extract(page, page) } }
        assertEquals(ExtractionError.NetworkBlocked(BlockingPlatform.Reddit), failure.error)
        assertTrue(failure.cause!!.message!!.contains("blocked by network security")) // Existing CI block classification.
        assertEquals(2, pages)
        assertEquals(2, bootstraps)
    }

    @Test fun challengeCanRecoverAndGenericShellOrUnknownOnlyFallThroughOnce() = runBlocking {
        var pages = 0
        val recovered = RedditPostExtractor(session, { error("No video") }, { error("No external") }) {
            if (++pages == 1) fixture("challenge") else fixture("image")
        }
        assertNotNull(recovered.extract(page, page))
        assertEquals(2, pages)
        assertEquals(2, bootstraps)
        for (html in listOf(fixture("shell"), "<html>unknown</html>")) {
            var calls = 0
            val extractor = RedditPostExtractor(session, { error("No video") }, { error("No external") }) { calls++; html }
            assertNull(extractor.extract(page, page))
            assertEquals(1, calls)
        }
        assertEquals(2, bootstraps)
    }

    @Test fun nativeAndExternalDelegationCarryOnlyTheDiscoveredUrlAndPreservePostMetadata() = runBlocking {
        val native = mutableListOf<String>()
        val external = mutableListOf<String>()
        var html = fixture("native")
        val extractor = RedditPostExtractor(session, { native += it; video() }, { external += it; video() }) { html }
        assertEquals("a&b", extractor.extract(page, page)!!.author)
        assertEquals(listOf("https://v.redd.it/video123/HLSPlaylist.m3u8?one=1&two=2"), native)
        html = fixture("external")
        val result = extractor.extract(page, page)!!
        assertEquals(listOf("https://streamable.com/1aqkw?one=1&two=2"), external)
        assertEquals(page, result.sourceUrl)
        assertEquals("Reddit title", result.title)
        assertEquals("poster", result.author)
        assertFalse(RedditLinks.useHtmlAdapter(page, redditExternal = true)) // Even an external redirect back to this post.
        assertTrue(RedditLinks.useHtmlAdapter(page, redditExternal = false))
        html = fixture("external").replace("https://streamable.com/1aqkw?one=1&amp;two=2", page)
        assertNull(extractor.extract(page, page))
        assertEquals(1, external.size)
    }

    @Test fun unsafeRedirectOrMediaAndCancellationNeverFallThrough() {
        for (error in listOf(UnsafeNetworkTargetException(), IOException(UnsafeNetworkTargetException()),
            ExtractionException(ExtractionError.UnsupportedUrl, UnsafeNetworkTargetException()))) {
            val extractor = RedditPostExtractor(session, { error("No video") }, { error("No external") }) { throw error }
            assertSame(error, assertThrows(Exception::class.java) { runBlocking { extractor.extract(page, page) } })
        }
        val extractor = RedditPostExtractor(session, { error("No video") }, { error("No external") }) {
            fixture("image").replace("i.redd.it", "127.0.0.1")
        }
        assertTrue(assertThrows(ExtractionException::class.java) { runBlocking { extractor.extract(page, page) } }.cause is UnsafeNetworkTargetException)
        assertThrows(CancellationException::class.java) {
            runBlocking {
                RedditPostExtractor(session, { error("No video") }, { error("No external") }) {
                    currentCoroutineContext().cancel()
                    throw IOException("Canceled")
                }.extract(page, page)
            }
        }
    }
}
