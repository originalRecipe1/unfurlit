package io.github.originalrecipe1.unfurlit.data.extractor.pixiv

import io.github.originalrecipe1.unfurlit.data.extractor.pixiv.PixivArtworkParserTest.Companion.PAGE
import io.github.originalrecipe1.unfurlit.data.extractor.pixiv.PixivArtworkParserTest.Companion.body
import io.github.originalrecipe1.unfurlit.data.extractor.pixiv.PixivArtworkParserTest.Companion.imageUrl
import io.github.originalrecipe1.unfurlit.data.extractor.pixiv.PixivArtworkParserTest.Companion.pages
import io.github.originalrecipe1.unfurlit.data.extractor.pixiv.PixivArtworkParserTest.Companion.response
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PixivArtworkExtractorTest {
    @Test fun singleImageUsesOneAnonymousWebRequestAndKeepsTheSourceUrl() = runBlocking {
        var calls = 0
        val extractor = PixivArtworkExtractor { request ->
            calls++
            assertEquals("https://www.pixiv.net/ajax/illust/966412", request.url.toString())
            assertEquals("GET", request.method)
            assertEquals("https://www.pixiv.net/", request.header("Referer"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            response(body())
        }
        val original = "$PAGE?lang=en"
        assertEquals(original, extractor.extract(original, PAGE).sourceUrl)
        assertEquals(1, calls)
    }

    @Test fun multipageArtworkFetchesItsPagesOnlyOnce() = runBlocking {
        val paths = mutableListOf<String>()
        val extractor = PixivArtworkExtractor { request ->
            paths += request.url.encodedPath
            if (paths.size == 1) response(body().put("pageCount", 2)) else pages(listOf(imageUrl(0), imageUrl(1)))
        }
        assertEquals(2, extractor.extract(PAGE, PAGE).media.size)
        assertEquals(listOf("/ajax/illust/966412", "/ajax/illust/966412/pages"), paths)
    }

    @Test fun doesNotFetchAnimationPagesOrTryTheAuthenticatedMobileApi() {
        var calls = 0
        val extractor = PixivArtworkExtractor { calls++; response(body().put("illustType", 2)) }
        val error = assertThrows(ExtractionException::class.java) { runBlocking { extractor.extract(PAGE, PAGE) } }
        assertEquals(ExtractionError.UnsupportedUrl, error.error)
        assertEquals(1, calls)
    }

    @Test fun preservesNetworkAndUnavailableErrorsWithoutRetriesOrLoginMisclassification() {
        for (failure in listOf(IOException("DNS lookup failed"), ExtractionException(ExtractionError.MediaUnavailable),
            ExtractionException(ExtractionError.AuthenticationRequired))) {
            var calls = 0
            val extractor = PixivArtworkExtractor { calls++; throw failure }
            assertSame(failure, assertThrows(Exception::class.java) { runBlocking { extractor.extract(PAGE, PAGE) } })
            assertEquals(1, calls)
        }
    }

    @Test(timeout = 5_000) fun cancellationStopsEitherMetadataOrPagesWithoutAnotherLookup() = runBlocking {
        for (cancelAt in 1..2) {
            var calls = 0
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val extractor = PixivArtworkExtractor {
                if (++calls == cancelAt) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                response(body().put("pageCount", 2))
            }
            val request = async { extractor.extract(PAGE, PAGE) }
            started.await()
            request.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertEquals(cancelAt, calls)
        }
    }
}
