package io.github.originalrecipe1.unfurlit.data.extractor.ytdlp

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult
import io.github.originalrecipe1.unfurlit.domain.model.PlaybackSource
import io.github.originalrecipe1.unfurlit.domain.model.StreamFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDlExtractionTest {
    @Test(timeout = 5_000)
    fun graceExpiryKeepsVideoAndCancelsTheStillRunningGallery() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val failures = mutableListOf<Throwable>()
        val original = videoResult()
        val result = extractWithGalleryDlFallback(POST,
            extractYtDlp = { started.await(); original },
            extractGalleryDl = {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            },
            onFailure = { failures += it },
            photoGraceMillis = 1,
        )
        assertSame(original, result)
        assertTrue(stopped.isCompleted)
        assertTrue(failures.single() is TimeoutCancellationException)
    }

    @Test(timeout = 5_000)
    fun wholeRequestCancellationCancelsBothLookups() = runBlocking {
        val videoStarted = CompletableDeferred<Unit>()
        val galleryStarted = CompletableDeferred<Unit>()
        val videoStopped = CompletableDeferred<Unit>()
        val galleryStopped = CompletableDeferred<Unit>()
        val request = async {
            extractWithGalleryDlFallback(POST,
                extractYtDlp = {
                    videoStarted.complete(Unit)
                    try { awaitCancellation() } finally { videoStopped.complete(Unit) }
                },
                extractGalleryDl = {
                    galleryStarted.complete(Unit)
                    try { awaitCancellation() } finally { galleryStopped.complete(Unit) }
                },
                onFailure = { throw AssertionError("Cancellation must propagate", it) },
            )
        }
        videoStarted.await()
        galleryStarted.await()
        request.cancelAndJoin()
        assertTrue(videoStopped.isCompleted)
        assertTrue(galleryStopped.isCompleted)
    }

    @Test(timeout = 5_000)
    fun eligibleVideoFailureReusesTheInFlightGalleryExactlyOnce() = runBlocking {
        for (error in listOf(ExtractionError.UnsupportedUrl, ExtractionError.ExtractionFailed, ExtractionError.AuthenticationRequired)) {
            val galleryStarted = CompletableDeferred<Unit>()
            val videoFailed = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            val photos = imageResult()
            assertSame(photos, extractWithGalleryDlFallback(POST,
                extractYtDlp = {
                    galleryStarted.await()
                    videoFailed.complete(Unit)
                    throw ExtractionException(error)
                },
                extractGalleryDl = {
                    requests += it
                    galleryStarted.complete(Unit)
                    videoFailed.await()
                    photos
                },
                onFailure = { throw AssertionError(it) },
            ))
            assertEquals(listOf(CANONICAL), requests)
        }
    }

    @Test(timeout = 5_000)
    fun completedGalleryFailureIsNotRestartedAndRetainsFailurePreference() {
        val galleryFailure = ExtractionException(ExtractionError.MediaUnavailable)
        var calls = 0
        val reported = mutableListOf<Throwable>()
        val failure = assertThrows(ExtractionException::class.java) {
            runBlocking {
                val finished = CompletableDeferred<Unit>()
                extractWithGalleryDlFallback(POST,
                    extractYtDlp = { finished.await(); throw ExtractionException(ExtractionError.UnsupportedUrl) },
                    extractGalleryDl = {
                        calls++
                        finished.complete(Unit)
                        throw galleryFailure
                    },
                    onFailure = { reported += it },
                )
            }
        }
        assertSame(galleryFailure, failure)
        assertEquals(1, calls)
        assertEquals(listOf(galleryFailure), reported)
    }

    @Test(timeout = 5_000)
    fun earlyGalleryFailureDoesNotCancelSuccessfulVideoExtraction() = runBlocking {
        val finished = CompletableDeferred<Unit>()
        val original = videoResult()
        val galleryFailure = ExtractionException(ExtractionError.NetworkFailure)
        val failures = mutableListOf<Throwable>()
        assertSame(original, extractWithGalleryDlFallback(POST,
            extractYtDlp = { finished.await(); original },
            extractGalleryDl = { finished.complete(Unit); throw galleryFailure },
            onFailure = { failures += it },
        ))
        assertEquals(listOf(galleryFailure), failures)
    }

    @Test(timeout = 5_000)
    fun galleryPhotosCanFinishBeforeVideoWithoutBeingLost() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val video = videoResult()
        val photos = imageResult()
        val result = extractWithGalleryDlFallback(POST,
            extractYtDlp = { ready.await(); video },
            extractGalleryDl = { ready.complete(Unit); photos },
            onFailure = { throw AssertionError(it) },
        )
        assertEquals(video.copy(media = video.media + photos.media), result)
    }

    @Test(timeout = 5_000)
    fun galleryTimeoutOnFallbackKeepsTheVideoFailureWithoutStartingAnotherLookup() {
        var calls = 0
        val videoFailure = ExtractionException(ExtractionError.UnsupportedUrl)
        val failure = assertThrows(ExtractionException::class.java) {
            runBlocking {
                val started = CompletableDeferred<Unit>()
                extractWithGalleryDlFallback(POST,
                    extractYtDlp = { started.await(); throw videoFailure },
                    extractGalleryDl = {
                        calls++
                        started.complete(Unit)
                        withTimeout(1) { awaitCancellation() }
                    },
                    onFailure = { throw AssertionError(it) },
                )
            }
        }
        assertSame(videoFailure, failure)
        assertEquals(1, calls)
    }

    @Test(timeout = 5_000)
    fun nonVideoSuccessAndIneligibleVideoFailureCancelUnusedGalleryWork() = runBlocking {
        for (failVideo in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val original = imageResult()
            val failure = ExtractionException(ExtractionError.NetworkFailure)
            try {
                val result = extractWithGalleryDlFallback(POST,
                    extractYtDlp = { started.await(); if (failVideo) throw failure else original },
                    extractGalleryDl = {
                        started.complete(Unit)
                        try { awaitCancellation() } finally { stopped.complete(Unit) }
                    },
                    onFailure = { throw AssertionError(it) },
                )
                assertEquals(false, failVideo)
                assertSame(original, result)
            } catch (error: ExtractionException) {
                assertTrue(failVideo)
                assertSame(failure, error)
            }
            assertTrue(stopped.isCompleted)
        }
    }

    @Test(timeout = 5_000)
    fun otherSitesKeepTheSequentialFallbackAndDoNotSupplementSuccessfulVideos() = runBlocking {
        val url = "https://example.com/post/123"
        val original = videoResult()
        assertSame(original, extractWithGalleryDlFallback(url,
            extractYtDlp = { original },
            extractGalleryDl = { throw AssertionError("Unexpected gallery lookup") },
            onFailure = { throw AssertionError(it) },
        ))
        var videoAttempted = false
        var galleryCalls = 0
        val photos = imageResult()
        assertSame(photos, extractWithGalleryDlFallback(url,
            extractYtDlp = { videoAttempted = true; throw ExtractionException(ExtractionError.UnsupportedUrl) },
            extractGalleryDl = {
                assertTrue(videoAttempted)
                assertEquals(url, it)
                galleryCalls++
                photos
            },
            onFailure = { throw AssertionError(it) },
        ))
        assertEquals(1, galleryCalls)
    }

    private fun videoResult() = result(ExtractedMedia.Video(source("https://video.twimg.com/video.mp4"), null, 3))
    private fun imageResult() = result(ExtractedMedia.Image(source("https://pbs.twimg.com/media/photo.jpg")))
    private fun source(url: String) = PlaybackSource(url, emptyMap(), StreamFormat.Progressive, null, null)
    private fun result(media: ExtractedMedia) = ExtractionResult(POST, "Twitter", "Post", "Author", null, null, listOf(media))

    private companion object {
        const val POST = "https://twitter.com/user/status/1577924293023133696"
        const val CANONICAL = "https://x.com/i/web/status/1577924293023133696"
    }
}
