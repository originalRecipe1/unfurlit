package io.github.originalrecipe1.unfurlit.data.extractor.tiktok

import io.github.originalrecipe1.unfurlit.domain.model.ExtractedMedia
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionException
import org.junit.Assert.*
import org.junit.Test

class TikTokPhotoParserTest {
    private val source = "https://vm.tiktok.com/ZGdQaShC6/"
    private val page = "https://www.tiktok.com/@example/video/7273914709152222496"
    private val fixture = javaClass.getResource("/tiktok/photo-post.html")!!.readText()

    @Test fun keepsOrderedPhotosAndSoundtrackSeparate() {
        val result = TikTokPhotoParser.parse(source, page, fixture)
        assertEquals(source, result.sourceUrl)
        assertEquals(3, result.media.size)
        assertEquals(listOf("0", "1", "2"), result.media.map {
            (it as ExtractedMedia.Image).source.url.substringAfter("photo-").substringBefore('.')
        })
        assertEquals("https://cdn.example/sound.mp3", result.backgroundAudio!!.source.url)
        assertEquals(37L, result.backgroundAudio.durationSeconds)
        assertEquals(page, result.backgroundAudio.source.headers["Referer"])
        assertEquals("https://cdn.example/photo-0.webp", result.thumbnailUrl)
    }

    @Test fun recognizesOnlyTikTokPhotoPostUrls() {
        assertEquals(page, TikTokPhotoParser.canonicalPage(
            "https://www.tiktok.com/@example/photo/7273914709152222496?share=1"))
        listOf("https://evil.example/@example/photo/123", "https://www.tiktok.com.evil.example/@example/photo/123",
            "https://www.tiktok.com/@example/video/123", "https://www.tiktok.com/@example",
            "http://www.tiktok.com/@example/photo/123").forEach {
            assertNull(TikTokPhotoParser.canonicalPage(it))
        }
    }

    @Test fun rejectsUnsafePhotoAndAudioUrls() {
        assertError(ExtractionError.UnsupportedUrl, fixture.replace("https://cdn.example/photo-1.webp", "https://127.0.0.1/a"))
        assertError(ExtractionError.UnsupportedUrl, fixture.replace("https://cdn.example/sound.mp3", "http://cdn.example/sound.mp3"))
    }

    @Test fun supportsSilentPhotosWithoutInventingAnAudioPage() {
        val result = TikTokPhotoParser.parse(source, page,
            fixture.replace("https://cdn.example/sound.mp3", ""))
        assertEquals(3, result.media.size)
        assertNull(result.backgroundAudio)
    }

    @Test fun rejectsWrongPostAndMissingPageData() {
        assertError(ExtractionError.ExtractionFailed, fixture.replace("7273914709152222496", "999"))
        assertError(ExtractionError.ExtractionFailed, "<html>Sign-in challenge</html>")
        assertError(ExtractionError.ExtractionFailed, fixture.replace("\"images\": [", "\"images\": [{},"))
    }

    @Test fun classifiesPrivateAndUnavailablePosts() {
        assertError(ExtractionError.AuthenticationRequired, fixture.replace("\"statusCode\": 0", "\"statusCode\": 10222"))
        assertError(ExtractionError.MediaUnavailable, fixture.replace("\"statusCode\": 0", "\"statusCode\": 10202"))
    }

    @Test fun limitsPageSize() {
        assertError(ExtractionError.ExtractionFailed, " ".repeat(TikTokPhotoParser.MAX_PAGE_BYTES + 1))
    }

    private fun assertError(expected: ExtractionError, html: String) {
        val error = assertThrows(ExtractionException::class.java) { TikTokPhotoParser.parse(source, page, html) }
        assertEquals(expected, error.error)
    }
}
