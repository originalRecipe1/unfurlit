package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import io.github.originalrecipe1.unfurlit.data.network.PublicPageLoader
import io.github.originalrecipe1.unfurlit.data.network.UnsafeNetworkTargetException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
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

    private companion object {
        const val PAGE = "https://www.tumblr.com/sample-blog/172687798174"
    }
}
