package io.github.originalrecipe1.unfurlit.data.extractor.twitter

import io.github.originalrecipe1.unfurlit.data.extractor.gallerydl.GalleryDlJsonParser
import io.github.originalrecipe1.unfurlit.data.extractor.ytdlp.YtDlpJsonParser
import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TwitterPhotoSupplementTest {
    @Test
    fun mixedPostKeepsSelectedVideoAndAddsPhotoWithItsHeaders() = runBlocking {
        val original = videoResult()
        val gallery = galleryResult()
        val result = TwitterPhotoSupplement.appendPhotos(
            POST,
            original,
            extractGallery = {
                assertEquals(CANONICAL, it)
                gallery
            },
            onFailure = { throw AssertionError(it) },
        )

        assertEquals(2, result.media.size)
        assertSame(original.media.single(), result.media[0])
        assertSame(gallery.media.first(), result.media[1])
        assertEquals(mapOf("Referer" to "https://x.com/"), (result.media[1] as ExtractedMedia.Image).source.headers)
        // Source URL (including share parameters), title, author, thumbnail and
        // all other metadata stay attached to the original extraction result.
        assertEquals(original, result.copy(media = original.media))
    }

    @Test
    fun skipsGalleryVideoVariantsAndAudioAndDeduplicatesPhotos() = runBlocking {
        val gallery = galleryResult()
        val photo = gallery.media.first()
        val secondPhoto = (photo as ExtractedMedia.Image).copy(source = photo.source.copy(url = "https://pbs.twimg.com/media/second.jpg"))
        val original = videoResult().let { it.copy(media = it.media + photo) }
        val audio = ExtractedMedia.Audio(photo.source.copy(url = "https://example.com/audio.mp3"), null, null)
        val result = TwitterPhotoSupplement.appendPhotos(
            POST, original,
            extractGallery = { gallery.copy(media = gallery.media + photo + secondPhoto + secondPhoto + audio) },
            onFailure = { throw AssertionError(it) },
        )
        assertEquals(original.media + secondPhoto, result.media)
    }

    @Test
    fun videoOnlyPostKeepsOriginalResult() = runBlocking {
        val original = videoResult()
        assertSame(original, TwitterPhotoSupplement.appendPhotos(
            POST, original,
            extractGallery = { galleryResult().let { it.copy(media = it.media.filterIsInstance<ExtractedMedia.Video>()) } },
            onFailure = { throw AssertionError(it) },
        ))
    }

    @Test
    fun supportsPostAliasesAndPhotoOrVideoSuffixes() = runBlocking {
        for (url in listOf(
            POST,
            "https://www.x.com/user/status/1577924293023133696/",
            "https://mobile.x.com/user/status/1577924293023133696",
            "https://twitter.com/user/status/1577924293023133696/photo/1",
            "https://www.twitter.com/user/status/1577924293023133696/video/2?share=copy",
            "https://mobile.twitter.com/user/status/1577924293023133696",
            "http://m.twitter.com/user/status/1577924293023133696",
            "https://X.COM:443/i/web/status/1577924293023133696#media",
            "https://twitter.com/statuses/1577924293023133696",
        )) {
            val requests = mutableListOf<String>()
            TwitterPhotoSupplement.appendPhotos(url, videoResult(), {
                requests += it
                galleryResult()
            }, { throw AssertionError(it) })
            assertEquals(url, listOf(CANONICAL), requests)
        }
    }

    @Test
    fun doesNotLookUpOtherSitesProfilesSpacesOrUnsafeUrls() = runBlocking {
        val original = videoResult()
        for (url in listOf(
            "https://youtube.com/watch?v=123",
            "https://www.reddit.com/comments/123",
            "https://video.twimg.com/video.mp4",
            "https://x.com/user",
            "https://x.com/i/spaces/123",
            "https://x.com/user/status/not-a-number",
            "https://x.com/user/status/123/other",
            "https://x.com.example.org/user/status/123",
            "https://example.org/?url=$POST",
            "https://user@x.com/user/status/123",
            "https://x.com:8443/user/status/123",
            "file:///user/status/123",
            "not a url",
        )) {
            assertSame(url, original, TwitterPhotoSupplement.appendPhotos(url, original,
                extractGallery = { throw AssertionError("Unexpected gallery lookup for $url") },
                onFailure = { throw AssertionError(it) },
            ))
        }
    }

    @Test
    fun photoOnlyAndAudioOnlyResultsNeedNoSupplement() = runBlocking {
        val photo = galleryResult().media.first() as ExtractedMedia.Image
        for (media in listOf(photo, ExtractedMedia.Audio(photo.source, null, null))) {
            val original = videoResult().copy(media = listOf(media))
            assertSame(original, TwitterPhotoSupplement.appendPhotos(POST, original,
                extractGallery = { throw AssertionError("Unexpected gallery lookup") },
                onFailure = { throw AssertionError(it) },
            ))
        }
    }

    @Test
    fun additionalLookupFailureDoesNotDiscardPlayableMedia() = runBlocking {
        val original = videoResult()
        for (error in listOf(
            ExtractionError.AuthenticationRequired, ExtractionError.MediaUnavailable,
            ExtractionError.NetworkFailure, ExtractionError.Timeout,
            ExtractionError.UnsupportedUrl, ExtractionError.ExtractionFailed,
        )) {
            val failure = ExtractionException(error)
            val reported = mutableListOf<Exception>()
            assertSame(original, TwitterPhotoSupplement.appendPhotos(POST, original,
                extractGallery = { throw failure },
                onFailure = { reported += it },
            ))
            assertEquals(listOf(failure), reported)
        }
    }

    @Test
    fun galleryTimeoutKeepsVideoButOuterTimeoutStillCancels() {
        val original = videoResult()
        runBlocking {
            val failures = mutableListOf<Exception>()
            assertSame(original, TwitterPhotoSupplement.appendPhotos(POST, original,
                extractGallery = { withTimeout(1) { awaitCancellation() } },
                onFailure = { failures += it },
            ))
            assertEquals(listOf(TimeoutCancellationException::class.java), failures.map { it.javaClass })
        }
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(1) {
                    TwitterPhotoSupplement.appendPhotos(POST, original,
                        extractGallery = { awaitCancellation() },
                        onFailure = { throw AssertionError("Outer cancellation was swallowed", it) },
                    )
                }
            }
        }
    }

    @Test
    fun cancellationIsPropagated() {
        val cancellation = CancellationException("Viewer closed")
        val failure = assertThrows(CancellationException::class.java) {
            runBlocking {
                TwitterPhotoSupplement.appendPhotos(POST, videoResult(),
                    extractGallery = { throw cancellation },
                    onFailure = { throw AssertionError("Cancellation was swallowed", it) },
                )
            }
        }
        // Coroutine stack recovery can copy the exception across withTimeout.
        assertEquals(cancellation.message, failure.message)
    }

    @Test
    fun combinedResultStillHonorsFiftyItemLimit() = runBlocking {
        val original = videoResult().let { it.copy(media = List(49) { _ -> it.media.single() }) }
        val photo = galleryResult().media.first() as ExtractedMedia.Image
        val result = TwitterPhotoSupplement.appendPhotos(POST, original,
            extractGallery = { galleryResult().copy(media = List(4) { photo.copy(source = photo.source.copy(url = "https://pbs.twimg.com/media/$it.jpg")) }) },
            onFailure = { throw AssertionError(it) },
        )
        assertEquals(50, result.media.size)
        assertEquals(original.media, result.media.take(49))
        assertSame(result, TwitterPhotoSupplement.appendPhotos(POST, result,
            extractGallery = { throw AssertionError("Gallery is already full") },
            onFailure = { throw AssertionError(it) },
        ))
    }

    private fun videoResult() = YtDlpJsonParser.parse(POST, """
        {"extractor_key":"Twitter","title":"A mixed post","uploader":"author",
         "description":"Post text","thumbnail":"https://pbs.twimg.com/thumb.jpg",
         "url":"https://video.twimg.com/selected.mp4","ext":"mp4","format_id":"http-950",
         "vcodec":"avc1","acodec":"mp4a","duration":2.466,
         "http_headers":{"User-Agent":"video-agent","Referer":"https://twitter.com/"}}
    """.trimIndent())

    private fun galleryResult() = GalleryDlJsonParser.parse(POST, """
        {"category":"twitter","title":"Other title","author":"other",
         "items":[
           {"url":"https://pbs.twimg.com/media/FeXpxOyaYAA9L88?format=jpg&name=orig",
            "kind":"image","extension":"jpg","headers":{"Referer":"https://x.com/"}},
           {"url":"https://video.twimg.com/other-resolution.mp4","kind":"video","extension":"mp4"}
         ]}
    """.trimIndent().replace("\n", ""))

    private companion object {
        const val POST = "https://x.com/carrotsprout_/status/1577924293023133696?share=copy"
        const val CANONICAL = "https://x.com/i/web/status/1577924293023133696"
    }
}
