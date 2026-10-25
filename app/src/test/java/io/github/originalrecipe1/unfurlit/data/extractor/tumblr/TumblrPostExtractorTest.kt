package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.BlockingPlatform
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TumblrPostExtractorTest {
    @Test fun usesThePublicPageWithoutCredentialsAndKeepsTheSourceUrl() = runBlocking {
        val extractor = TumblrPostExtractor { request ->
            assertEquals(PAGE, request.url.toString())
            assertEquals("GET", request.method)
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            javaClass.getResource("/tumblr/photo-post.html")!!.readText()
        }
        val original = "https://sample-blog.tumblr.com/post/172687798174/photo-post?source=share"
        assertEquals(original, extractor.extract(original, PAGE)!!.sourceUrl)
    }

    @Test fun optionalPageFailuresAllowExistingEnginesWithoutAnotherPageAttempt() = runBlocking {
        for (failure in listOf(IOException("DNS lookup failed"), IOException("Call timed out"),
            ExtractionException(ExtractionError.NetworkFailure),
            ExtractionException(ExtractionError.ExtractionFailed),
            ExtractionException(ExtractionError.MediaUnavailable),
            ExtractionException(ExtractionError.AuthenticationRequired))) {
            var calls = 0
            val extractor = TumblrPostExtractor { calls++; throw failure }
            assertNull(extractor.extract(PAGE, PAGE))
            assertEquals(1, calls)
        }
    }

    @Test fun unknownPagesAndSizeLimitsAllowExistingEnginesWithoutRelaxingTheLimits() = runBlocking {
        val state = JSONObject(javaClass.getResource("/tumblr/photo-post.html")!!.readText()
            .substringAfter('>').substringBefore("</script>"))
        val post = state.getJSONObject("PeeprRoute").getJSONObject("initialTimeline").getJSONArray("objects").getJSONObject(0)
        post.put("trail", JSONArray((0..50).map { JSONObject().put("content", JSONArray()) }))
        for (html in listOf("<html>Unknown layout</html>", " ".repeat(PublicPageLoader.MAX_PAGE_BYTES + 1),
            "<script id='___INITIAL_STATE___'>$state</script>")) {
            assertNull(TumblrPostExtractor { html }.extract(PAGE, PAGE))
        }
    }

    @Test fun unsafeDnsRedirectOrMediaRejectionNeverFallsBack() {
        for (failure in listOf(UnsafeNetworkTargetException(), IOException(UnsafeNetworkTargetException()),
            ExtractionException(ExtractionError.UnsupportedUrl, UnsafeNetworkTargetException()))) {
            val extractor = TumblrPostExtractor { throw failure }
            assertSame(failure, assertThrows(Exception::class.java) {
                runBlocking { extractor.extract(PAGE, PAGE) }
            })
        }
        val html = javaClass.getResource("/tumblr/photo-post.html")!!.readText()
            .replace("https://cdn.example/photo-1.png", "https://127.0.0.1/photo.png")
        val failure = assertThrows(ExtractionException::class.java) {
            runBlocking { TumblrPostExtractor { html }.extract(PAGE, PAGE) }
        }
        assertTrue(failure.cause is UnsafeNetworkTargetException)
    }

    @Test fun cancellationRacingWithAnIoFailureDoesNotAllowFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                TumblrPostExtractor {
                    currentCoroutineContext().cancel()
                    throw IOException("Canceled")
                }.extract(PAGE, PAGE)
            }
        }
    }

    @Test(timeout = 5_000) fun requestTimeoutDoesNotAllowFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(50) { TumblrPostExtractor { awaitCancellation() }.extract(PAGE, PAGE) }
            }
        }
    }

    @Test(timeout = 5_000) fun cancellationStopsThePageLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val extractor = TumblrPostExtractor {
            started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        val request = async { extractor.extract(PAGE, PAGE) }
        started.await()
        request.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }

    @Test fun embedOnlyPostUsesTheSitesRouteAndKeepsTheTumblrLinkAndMetadata() = runBlocking {
        val requested = mutableListOf<String>()
        val extractor = TumblrPostExtractor({ url -> requested += url; youTube("YouTube title") }) { embedHtml }
        val result = extractor.extract(EMBED_ORIGINAL, EMBED_PAGE)!!
        assertEquals(listOf("https://www.youtube.com/watch?v=eomhW5MLGWA"), requested)
        assertEquals(EMBED_ORIGINAL, result.sourceUrl)
        assertEquals("Tumblr", result.platform)
        assertEquals("prozdvoices", result.author)
        assertEquals("what recording voice acting sounds like", result.title)
        assertEquals("https://i.ytimg.com/vi/eomhW5MLGWA/hqdefault.jpg", result.thumbnailUrl)
        assertEquals(listOf("https://youtube.example/video"), urls(result.media))
    }

    @Test fun embedFillsOnlyMissingMetadata() = runBlocking {
        val state = embedState()
        val post = post(state)
        post.remove("summary")
        post.put("content", JSONArray().put(post.getJSONArray("content").getJSONObject(0).apply { remove("poster") }))
        val result = TumblrPostExtractor({ youTube("YouTube title") }) { envelope(state) }.extract(EMBED_PAGE, EMBED_PAGE)!!
        assertEquals("YouTube title", result.title)
        assertEquals("YouTube description", result.description)
        assertEquals("https://i.ytimg.com/youtube-thumbnail.jpg", result.thumbnailUrl)
        assertEquals("prozdvoices", result.author)
    }

    @Test fun mixedPostSplicesEachEmbedAtItsPositionInPostOrder() = runBlocking {
        val state = embedState()
        val embed = post(state).getJSONArray("content").getJSONObject(0)
        val vimeo = JSONObject(embed.toString()).put("provider", "vimeo").put("url", "https://vimeo.com/76979871")
        post(state).put("content", JSONArray().put(image("first")).put(embed).put(image("middle")).put(vimeo))
        val requested = mutableListOf<String>()
        val result = TumblrPostExtractor({ url ->
            requested += url
            youTube(url, mediaUrl = "https://video.example/${requested.size}")
        }) { envelope(state) }.extract(EMBED_PAGE, EMBED_PAGE)!!
        assertEquals(listOf("https://www.youtube.com/watch?v=eomhW5MLGWA", "https://vimeo.com/76979871"), requested)
        assertEquals(listOf("https://cdn.example/first.png", "https://video.example/1",
            "https://cdn.example/middle.png", "https://video.example/2"), urls(result.media))
        assertEquals("https://cdn.example/first.png", result.thumbnailUrl)
    }

    @Test fun embedFailureIsReportedInsteadOfFallingBackToTheBlogPage() {
        for (failure in listOf(ExtractionException(ExtractionError.NetworkBlocked(BlockingPlatform.YouTube)),
            ExtractionException(ExtractionError.MediaUnavailable),
            ExtractionException(ExtractionError.UnsupportedUrl, UnsafeNetworkTargetException()))) {
            var pages = 0
            val extractor = TumblrPostExtractor({ throw failure }) { pages++; embedHtml }
            assertSame(failure, assertThrows(ExtractionException::class.java) {
                runBlocking { extractor.extract(EMBED_PAGE, EMBED_PAGE) }
            })
            assertEquals(1, pages)
        }
    }

    @Test fun withoutAnEmbedRouteTheEnginesKeepTheOriginalLink() = runBlocking {
        assertNull(TumblrPostExtractor { embedHtml }.extract(EMBED_PAGE, EMBED_PAGE))
    }

    @Test fun embeddedPlaylistsCannotExceedTheGalleryLimit() {
        val state = embedState()
        val embed = post(state).getJSONArray("content").getJSONObject(0)
        post(state).put("content", JSONArray().put(image("photo")).put(embed))
        val extractor = TumblrPostExtractor({ url ->
            youTube(url).let { it.copy(media = List(50) { _ -> it.media.single() }) }
        }) { envelope(state) }
        val failure = assertThrows(ExtractionException::class.java) {
            runBlocking { extractor.extract(EMBED_PAGE, EMBED_PAGE) }
        }
        assertEquals(ExtractionError.ExtractionFailed, failure.error)
    }

    private fun youTube(title: String, mediaUrl: String = "https://youtube.example/video") = ExtractionResult(
        sourceUrl = EMBED_ORIGINAL, platform = "Youtube", title = title, author = "ProZD",
        description = "YouTube description", thumbnailUrl = "https://i.ytimg.com/youtube-thumbnail.jpg",
        media = listOf(ExtractedMedia.Video(PlaybackSource(mediaUrl, emptyMap(), StreamFormat.Dash, "video/mp4", "137"),
            PlaybackSource("https://youtube.example/audio", emptyMap(), StreamFormat.Dash, "audio/mp4", "140"), 60)),
    )
    private fun embedState() = JSONObject(embedHtml.substringAfter("application/json\">").substringBefore("</script>"))
    private fun post(state: JSONObject) = state.getJSONObject("PeeprRoute").getJSONObject("initialTimeline")
        .getJSONArray("objects").getJSONObject(0)
    private fun envelope(state: JSONObject) = "<script id='___INITIAL_STATE___' type='application/json'>$state</script>"
    private fun image(name: String) = JSONObject().put("type", "image").put("media",
        JSONArray().put(JSONObject().put("url", "https://cdn.example/$name.png").put("type", "image/png")))
    private fun urls(media: List<ExtractedMedia>) = media.map {
        when (it) {
            is ExtractedMedia.Image -> it.source.url
            is ExtractedMedia.Video -> it.videoSource.url
            else -> error("Unexpected audio")
        }
    }
    private val embedHtml = javaClass.getResource("/tumblr/youtube-embed-post.html")!!.readText()

    private companion object {
        const val PAGE = "https://www.tumblr.com/sample-blog/172687798174"
        const val EMBED_PAGE = "https://www.tumblr.com/prozdvoices/673201091169681408"
        const val EMBED_ORIGINAL =
            "https://prozdvoices.tumblr.com/post/673201091169681408/what-recording-voice-acting-sounds-like"
    }
}
