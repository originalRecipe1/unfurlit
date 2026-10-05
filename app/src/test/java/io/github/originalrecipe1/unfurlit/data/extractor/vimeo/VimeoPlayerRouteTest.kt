package io.github.originalrecipe1.unfurlit.data.extractor.vimeo

import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class VimeoPlayerRouteTest {
    @Test fun rewritesOnlyNumericPagesAndUnlistedHashPaths() {
        mapOf(
            "https://vimeo.com/33951933" to "https://player.vimeo.com/video/33951933",
            "https://www.vimeo.com/33951933/" to "https://player.vimeo.com/video/33951933",
            "http://VIMEO.com/33951933?share=copy#t=3s" to "https://player.vimeo.com/video/33951933",
            "https://vimeo.com:443/33951933" to "https://player.vimeo.com/video/33951933",
            "https://vimeo.com/144579403/ec02229140" to "https://player.vimeo.com/video/144579403?h=ec02229140",
            "https://www.vimeo.com/144579403/ec02229140/?share=copy" to "https://player.vimeo.com/video/144579403?h=ec02229140",
        ).forEach { (input, expected) -> assertEquals(input, expected, VimeoPlayerRoute.playerUrl(input)) }
    }

    @Test fun leavesPlayerChannelsShowcasesOtherPathsAndOtherHostsAlone() {
        listOf(
            "https://player.vimeo.com/video/33951933",
            "https://player.vimeo.com/video/144579403?h=ec02229140",
            "https://vimeo.com/channels/staffpicks/33951933",
            "https://vimeo.com/showcase/123/video/33951933",
            "https://vimeo.com/album/123/video/33951933",
            "https://vimeo.com/groups/travelhd/videos/33951933",
            "https://vimeo.com/ondemand/example/33951933",
            "https://vimeo.com/user123/example",
            "https://vimeo.com/33951933/review/abc",
            "https://vimeo.com/33951933/hash/extra",
            "https://vimeo.com/33951933/hash-with-punctuation",
            "https://vimeo.com/33951933/%2Fother",
            "https://vimeo.com/",
            "https://vimeo.com/not-numeric",
            "https://vimeo.com.example.com/33951933",
            "https://example.com/33951933",
            "https://vimeo.com@evil.example/33951933",
            "https://name@vimeo.com/33951933",
            "https://vimeo.com:8443/33951933",
            "ftp://vimeo.com/33951933",
            "https://vimeo.com/33951933?oversized=" + "x".repeat(8192),
            "not a url",
        ).forEach { assertNull(it, VimeoPlayerRoute.playerUrl(it)) }
    }

    @Test fun successfulPlayerAttemptKeepsOriginalSourceForHistory() = runBlocking {
        val original = "https://vimeo.com/33951933?share=copy"
        val calls = mutableListOf<Pair<String, String>>()
        val result = VimeoPlayerRoute.extract(original) { source, request ->
            calls += source to request
            result(source)
        }
        assertEquals(listOf(original to "https://player.vimeo.com/video/33951933"), calls)
        assertEquals(original, result.sourceUrl)
    }

    @Test fun playerFailureRetriesExactOriginalIncludingUnlistedHash() = runBlocking {
        val original = "https://vimeo.com/144579403/ec02229140?share=copy"
        val calls = mutableListOf<Pair<String, String>>()
        val result = VimeoPlayerRoute.extract(original) { source, request ->
            calls += source to request
            if (calls.size == 1) throw ExtractionException(ExtractionError.ExtractionFailed)
            result(source)
        }
        assertEquals(listOf(original to "https://player.vimeo.com/video/144579403?h=ec02229140", original to original), calls)
        assertEquals(original, result.sourceUrl)
    }

    @Test fun originalFailureIsReportedAfterPlayerFailure() {
        val originalFailure = ExtractionException(ExtractionError.AuthenticationRequired)
        val requests = mutableListOf<String>()
        val failure = assertThrows(ExtractionException::class.java) {
            runBlocking {
                VimeoPlayerRoute.extract("https://vimeo.com/33951933") { _, request ->
                    requests += request
                    if (requests.size == 1) throw ExtractionException(ExtractionError.ExtractionFailed)
                    throw originalFailure
                }
            }
        }
        assertSame(originalFailure, failure)
        assertEquals(listOf("https://player.vimeo.com/video/33951933", "https://vimeo.com/33951933"), requests)
    }

    @Test fun cancellationDoesNotStartAnotherRequest() {
        val cancellation = CancellationException("Viewer closed")
        var attempts = 0
        val failure = assertThrows(CancellationException::class.java) {
            runBlocking {
                VimeoPlayerRoute.extract("https://vimeo.com/33951933") { _, _ ->
                    attempts++
                    throw cancellation
                }
            }
        }
        assertSame(cancellation, failure)
        assertEquals(1, attempts)
    }

    @Test fun otherRoutesAreAttemptedExactlyOnce() = runBlocking {
        for (original in listOf("https://player.vimeo.com/video/33951933", "https://vimeo.com/channels/staffpicks/33951933",
            "https://www.reddit.com/comments/hrrh23", "https://example.com/video")) {
            val calls = mutableListOf<Pair<String, String>>()
            val result = VimeoPlayerRoute.extract(original) { source, request ->
                calls += source to request
                result(source)
            }
            assertEquals(listOf(original to original), calls)
            assertEquals(original, result.sourceUrl)
        }
    }

    private fun result(source: String) = ExtractionResult(source, "Vimeo", "Title", null, null, null, emptyList())
}
