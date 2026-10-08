package io.github.originalrecipe1.unfurlit.data.extractor.tumblr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TumblrPhotoExtractorTest {
    @Test fun usesThePublicPageWithoutCredentialsAndKeepsTheSourceUrl() = runBlocking {
        val extractor = TumblrPhotoExtractor { request ->
            assertEquals(PAGE, request.url.toString())
            assertEquals("GET", request.method)
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            javaClass.getResource("/tumblr/photo-post.html")!!.readText()
        }
        val original = "https://sample-blog.tumblr.com/post/172687798174/photo-post?source=share"
        assertEquals(original, extractor.extract(original, PAGE)!!.sourceUrl)
    }

    @Test fun realNetworkFailuresPropagateWithoutRetry() {
        var calls = 0
        val failure = IOException("DNS lookup failed")
        val extractor = TumblrPhotoExtractor { calls++; throw failure }
        assertSame(failure, assertThrows(IOException::class.java) {
            runBlocking { extractor.extract(PAGE, PAGE) }
        })
        assertEquals(1, calls)
    }

    @Test(timeout = 5_000) fun cancellationStopsThePageLookup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val extractor = TumblrPhotoExtractor {
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
